package com.snrm.network;

import com.snrm.common.DurationAmount;
import com.snrm.common.RoundingPolicy;
import com.snrm.common.TimeBasis;

import java.util.List;

/**
 * Everything the resolution checks need, with nothing about where it came from: a
 * clock, and the durations to push onto it.
 *
 * <p><strong>Why this type exists.</strong> The same four checks have to run in two places that
 * disagree about what a network is. In the editor they run against rows in the database, reached
 * through repositories. During import they run against a candidate network that has
 * deliberately <em>not</em> been written yet — an import creates nothing until validation passes, so
 * there is no {@code network_id} to query and no ids on the elements. Reducing both callers to this
 * record is what lets {@link TimeValidationService#check} be the single implementation of the
 * checks, rather than the import path growing a second copy of the arithmetic that would drift
 * from the first.
 *
 * <p>Free of JPA on purpose. {@link DeclaredDuration} carries a nullable id precisely because an
 * imported element has none until it is persisted; the editor fills it in so its banner can select
 * the element on the canvas, and the wizard leaves it null and addresses the row by sheet and line
 * number instead.
 *
 * @param periodLength   the candidate network's period — the denominator of every conversion
 * @param roundingPolicy how a remainder is resolved
 * @param horizonPeriods how many periods a run would cover, for the event-window check
 * @param durations      every duration declared anywhere in the network
 * @param events         disruption event windows to check against the horizon; empty during import,
 *                       which carries no scenario
 */
public record TimeValidationInput(
        DurationAmount periodLength,
        RoundingPolicy roundingPolicy,
        int horizonPeriods,
        List<DeclaredDuration> durations,
        List<EventWindow> events) {

    /** Defensive copies, and empty rather than null for both lists. */
    public TimeValidationInput {
        durations = durations == null ? List.of() : List.copyOf(durations);
        events = events == null ? List.of() : List.copyOf(events);
    }

    /** A network with no events to check — the import case. */
    public static TimeValidationInput of(DurationAmount periodLength, RoundingPolicy roundingPolicy,
            int horizonPeriods, List<DeclaredDuration> durations) {
        return new TimeValidationInput(periodLength, roundingPolicy, horizonPeriods, durations,
                List.of());
    }

    /**
     * One declared duration and enough about its owner to point a client at it.
     *
     * @param type   what kind of element declared it
     * @param id     its database id, or null when it does not have one yet (import)
     * @param name   its name — a node's name, a link's endpoints — for the message
     * @param field  the attribute at fault: {@code leadTime}, {@code processingTime},
     *               {@code startOffset}, {@code duration}
     * @param amount the duration as the user stated it, never restated
     */
    public record DeclaredDuration(TimeElementType type, Long id, String name, String field,
            DurationAmount amount) {

        /** How a finding's message names the attribute — "Lead time", "Processing time". */
        String label() {
            return switch (field) {
                case "leadTime" -> "Lead time";
                case "processingTime" -> "Processing time";
                case "startOffset" -> "Event start offset";
                default -> "Event duration";
            };
        }

        /** What accepting a conversion to zero periods actually means for this attribute. */
        String zeroRemedy() {
            return switch (field) {
                case "leadTime" -> "zero transit";
                case "processingTime" -> "no dwell";
                case "startOffset" -> "an event that starts at period 0";
                default -> "an event that lasts no time at all";
            };
        }
    }

    /**
     * A disruption event's timeline, for {@link TimeCheck#EVENT_EXCEEDS_HORIZON}.
     *
     * <p>The offset and the window stay separate because that is how the engine discretises them —
     * an offset becomes the index of the step the event fires in, a duration a count of steps it
     * lasts. Summing first and rounding once would report an end period no simulation
     * uses.
     *
     * <p>The three methods below are the whole of that arithmetic, and they live here rather than in
     * their caller because there are two callers with opposite jobs. {@code TimeValidationService}
     * <em>reports</em> a window that overruns, as one finding among four, for a banner the user may
     * dismiss. {@code DisruptionScenarioService} <em>refuses</em> to store one, because an
     * event the run never finishes observing makes every recovery metric over it a measurement of
     * the truncation. Two answers, one calculation — a second copy would
     * eventually let the editor's banner and the API disagree about which period an event ends in.
     */
    public record EventWindow(Long id, String name, DurationAmount startOffset,
            DurationAmount duration) {

        /** The step the event fires in, on {@code basis}'s clock. */
        public long startPeriod(TimeBasis basis) {
            return basis.toPeriods(startOffset);
        }

        /** How many steps it lasts. May be 0 — a window shorter than the resolution of the clock. */
        public long windowPeriods(TimeBasis basis) {
            return basis.toPeriods(duration);
        }

        /**
         * The first period after the event: {@link #startPeriod} + {@link #windowPeriods}.
         *
         * <p>Discretised as two separate roundings, deliberately — see the record's note.
         */
        public long endPeriod(TimeBasis basis) {
            return startPeriod(basis) + windowPeriods(basis);
        }

        /** True when the event's window runs past a horizon of {@code horizonPeriods} steps. */
        public boolean exceeds(TimeBasis basis, int horizonPeriods) {
            return endPeriod(basis) > horizonPeriods;
        }
    }
}
