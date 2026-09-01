package com.snrm.network;

import com.snrm.common.DurationDto;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One resolution problem found by {@link TimeValidationService}.
 *
 * <p>Structured rather than a sentence, because two clients consume it differently: the editor's
 * banner lists {@link #message()} and uses {@link #elementType()} and {@link #elementId()} to select
 * the element on the canvas when the entry is clicked, while the import wizard groups by
 * {@link #code()} and refuses on {@link #severity()}. The message is written for a human
 * and may be reworded; the code, type, id and severity are the contract.
 *
 * <p>{@link #declaredValue()} and {@link #convertedPeriods()} are the two numbers the finding is
 * really about — what the user said, and what the engine will use — and {@link #errorPercent()} is
 * the gap between them as a proportion of the declared value. It is signed: a lead time rounded up
 * to the next period is positive, one that vanished is −100%. Null fields are omitted from the JSON
 * ({@code spring.jackson.default-property-inclusion=non_null}), which is why the two checks that are
 * not about a single duration's arithmetic — {@link TimeCheck#PERIOD_TOO_FINE} and
 * {@link TimeCheck#EVENT_EXCEEDS_HORIZON} — simply leave the inapplicable parts out.
 *
 * @param elementType      what kind of thing this is about
 * @param elementId        its id, for selecting it on the canvas
 * @param elementName      its name — a node's name, a link's endpoints — for the banner
 * @param field            the attribute at fault: {@code leadTime}, {@code processingTime},
 *                         {@code window}
 * @param code             which of the four checks fired
 * @param severity         whether this is a banner entry or a refusal
 * @param declaredValue    the duration as the user stated it, unit included
 * @param convertedPeriods what the duration becomes on this network's clock, in whole periods
 * @param errorPercent     signed relative error of the conversion, in percent
 * @param message          the sentence the editor shows
 */
@Schema(name = "TimeFinding",
        description = "One resolution problem found when converting a declared duration onto the "
                + "network's period.")
public record TimeFinding(

        @Schema(description = "What kind of element the finding is about.", example = "LINK")
        TimeElementType elementType,

        @Schema(description = "Id of that element; the editor selects it on the canvas. "
                + "Absent for a finding raised during import, where nothing has been persisted yet "
                + "and the wizard addresses the row by sheet and line instead.",
                example = "12", nullable = true)
        Long elementId,

        @Schema(description = "Its name, for display — a node's name, a link's two endpoints.",
                example = "SUP-1 → PLANT-1")
        String elementName,

        @Schema(description = "The attribute at fault.", example = "leadTime")
        String field,

        @Schema(description = "Which of the four checks produced this.",
                example = "DURATION_ROUNDS_TO_ZERO")
        TimeCheck code,

        @Schema(description = "Warning entries appear in the editor banner; errors are refused by "
                + "import.", example = "WARNING")
        TimeSeverity severity,

        @Schema(description = "The duration as the user stated it. Absent where the check is not "
                + "about one duration.", nullable = true)
        DurationDto declaredValue,

        @Schema(description = "What that duration becomes on this network's clock, in whole "
                + "periods. For an event window, the period the event ends in.",
                example = "0", nullable = true)
        Long convertedPeriods,

        @Schema(description = "Signed relative error of the conversion, in percent: +140 means the "
                + "engine will use 2.4× the declared duration, −100 that it vanished. Absent where "
                + "the check is not about one duration's arithmetic.",
                example = "-100.0", nullable = true)
        Double errorPercent,

        @Schema(description = "The sentence the editor's warning banner shows.",
                example = "Lead time 6.0 HOUR is shorter than half a period (1.0 DAY) and will be "
                        + "treated as instantaneous. Use a finer period, or accept zero transit.")
        String message) {

    /**
     * A finding about one declared duration — the first two checks.
     *
     * <p>{@code elementId} is a {@link Long} rather than a {@code long} because an element validated
     * during import has no id yet: nothing is created until validation passes, so the wizard
     * addresses the row by sheet and line number instead and leaves this null.
     */
    static TimeFinding ofDuration(TimeElementType elementType, Long elementId, String elementName,
            String field, TimeCheck code, TimeSeverity severity, DurationDto declaredValue,
            long convertedPeriods, double errorPercent, String message) {
        return new TimeFinding(elementType, elementId, elementName, field, code, severity,
                declaredValue, convertedPeriods, errorPercent, message);
    }

    /** A finding whose subject is the clock or the horizon rather than one duration's arithmetic. */
    static TimeFinding ofElement(TimeElementType elementType, Long elementId, String elementName,
            String field, TimeCheck code, TimeSeverity severity, DurationDto declaredValue,
            Long convertedPeriods, String message) {
        return new TimeFinding(elementType, elementId, elementName, field, code, severity,
                declaredValue, convertedPeriods, null, message);
    }
}
