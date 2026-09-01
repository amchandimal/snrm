package com.snrm.network;

import com.snrm.common.DurationAmount;
import com.snrm.common.DurationDto;
import com.snrm.common.RoundingPolicy;
import com.snrm.common.TimeBasis;
import com.snrm.common.TimeUnit;
import com.snrm.network.TimeValidationInput.DeclaredDuration;
import com.snrm.network.TimeValidationInput.EventWindow;
import com.snrm.scenario.DisruptionEvent;
import com.snrm.scenario.DisruptionScenario;
import com.snrm.scenario.DisruptionScenarioRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The resolution validation: what a network's declared durations become once they are
 * pushed onto its period, and what the period ought to be instead.
 *
 * <p>Converting a fine-grained duration onto a coarse period destroys it silently — a 6-hour lead
 * time on a network stepping in days simply stops existing — and the direction of that error always
 * flatters the network, which a resilience study cannot afford. This service is what makes the loss
 * visible before a run is submitted rather than after: the editor shows the findings as a dismissible
 * banner, the import wizard lists them in its validation report and refuses the errors,
 * and both offer {@link #suggestPeriod} as the remedy.
 *
 * <p><strong>Two entry points, one implementation.</strong> {@link #check} is the checks themselves,
 * over a {@link TimeValidationInput} — a clock and a list of durations, with no notion of where they
 * came from. {@link #validate(long, Long, long, TimeValidationContext)} is the editor's form: it
 * reads a persisted network through the repositories and delegates. The import path calls
 * {@link #check} directly, because it validates a network that has deliberately not been written yet
 * and therefore has no id to query by. Adding a second copy of the arithmetic for that case is the
 * mistake this split exists to prevent.
 *
 * <p><strong>It reports; it never rejects.</strong> Nothing here throws because a duration rounds
 * badly. Every finding is data the caller decides what to do with, which is the only arrangement
 * that lets the same four checks be an error during import and a warning in the editor
 * ({@link TimeValidationContext}).
 *
 * <p><strong>All four checks are about durations.</strong> Rates are rescaled onto the period, never
 * rounded — 120 per week is exactly 17.142857… per day — so there is nothing to lose and
 * nothing to warn about.
 *
 * <p>Reaching into {@code scenario} for events is deliberate: {@link TimeCheck#EVENT_EXCEEDS_HORIZON}
 * compares a scenario's timeline against a network's horizon, and neither side owns the question.
 * Scenarios are project-scoped so that one can be replayed against every variant, which is
 * exactly why the answer differs per (network, scenario) pair and the scenario has to be named in
 * the request.
 */
@Service
@Transactional(readOnly = true)
public class TimeValidationService {

    /**
     * The relative rounding error above which a duration is reported, and the tolerance
     * {@link #suggestPeriod} keeps every duration inside.
     */
    static final double ROUNDING_TOLERANCE = 0.10;

    /**
     * How many times finer than the longest declared duration the period may be before the run is
     * called slow.
     */
    static final double FINENESS_LIMIT = 1000;

    /**
     * Candidate period lengths for {@link #suggestPeriod}, coarsest-first order imposed later.
     *
     * <p>A ladder of the values a person would actually choose, rather than every multiple of every
     * unit: the suggestion is offered to a researcher in a dialog, and "90 minutes" is a
     * usable answer where "5,400 seconds" is not. Values that duplicate a coarser unit are left out
     * — 24 HOUR is 1 DAY and would only make the answer read worse. The greatest common divisor of
     * the declared durations is added to this list at search time, which is what makes an exact fit
     * reachable when no ladder value happens to divide the data.
     */
    private static final List<DurationAmount> CANDIDATE_PERIODS = List.of(
            DurationAmount.of(1, TimeUnit.SECOND),
            DurationAmount.of(5, TimeUnit.SECOND),
            DurationAmount.of(15, TimeUnit.SECOND),
            DurationAmount.of(30, TimeUnit.SECOND),
            DurationAmount.of(1, TimeUnit.MINUTE),
            DurationAmount.of(5, TimeUnit.MINUTE),
            DurationAmount.of(10, TimeUnit.MINUTE),
            DurationAmount.of(15, TimeUnit.MINUTE),
            DurationAmount.of(30, TimeUnit.MINUTE),
            DurationAmount.of(1, TimeUnit.HOUR),
            DurationAmount.of(2, TimeUnit.HOUR),
            DurationAmount.of(3, TimeUnit.HOUR),
            DurationAmount.of(4, TimeUnit.HOUR),
            DurationAmount.of(6, TimeUnit.HOUR),
            DurationAmount.of(8, TimeUnit.HOUR),
            DurationAmount.of(12, TimeUnit.HOUR),
            DurationAmount.of(1, TimeUnit.DAY),
            DurationAmount.of(2, TimeUnit.DAY),
            DurationAmount.of(3, TimeUnit.DAY),
            DurationAmount.of(1, TimeUnit.WEEK),
            DurationAmount.of(2, TimeUnit.WEEK),
            DurationAmount.of(1, TimeUnit.MONTH),
            DurationAmount.of(3, TimeUnit.MONTH),
            DurationAmount.of(6, TimeUnit.MONTH),
            DurationAmount.of(1, TimeUnit.YEAR));

    /**
     * Worst first, then a stable order the banner can rely on across refreshes.
     *
     * <p>The id comparison is null-tolerant: an element validated during import has no id yet
     * and sorting must not be the thing that discovers it.
     */
    private static final Comparator<TimeFinding> WORST_FIRST =
            Comparator.comparing(TimeFinding::severity, Comparator.reverseOrder())
                    .thenComparing(TimeFinding::elementType)
                    .thenComparing(TimeFinding::elementId,
                            Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(TimeFinding::field);

    private final NetworkLookup lookup;
    private final NodeRepository nodes;
    private final LinkRepository links;
    private final DisruptionScenarioRepository scenarios;

    TimeValidationService(NetworkLookup lookup, NodeRepository nodes, LinkRepository links,
            DisruptionScenarioRepository scenarios) {
        this.lookup = lookup;
        this.nodes = nodes;
        this.links = links;
        this.scenarios = scenarios;
    }

    /**
     * Every resolution finding for a network, plus the period that would remove them
     * ({@code GET /api/v1/networks/{id}/time-validation}).
     *
     * @param networkId  the network whose clock is being checked
     * @param scenarioId a scenario of the same project whose events should be checked against this
     *                   network's horizon, or null to check the network alone. Without one, no
     *                   {@link TimeCheck#EVENT_EXCEEDS_HORIZON} finding can be produced.
     * @param ownerId    the calling researcher
     * @param context    which severity rule applies
     * @throws EntityNotFoundException if the network is missing, is not the caller's, or the
     *                                 scenario belongs to another project
     */
    public TimeValidationReport validate(long networkId, Long scenarioId, long ownerId,
            TimeValidationContext context) {
        Network network = lookup.requireNetwork(networkId, ownerId);
        return check(networkId, scenarioId, inputOf(network, eventsOf(network, scenarioId)), context);
    }

    /**
     * The four checks over a clock and a list of durations, with no database behind
     * them — the form the CSV/XLSX import validates a candidate network in.
     *
     * <p>Pure: it reads nothing, writes nothing and throws nothing. Findings are data the caller acts
     * on, which is what lets {@code DURATION_ROUNDS_TO_ZERO} be an error for the import wizard and a
     * warning in the editor from one implementation ({@link TimeValidationContext}).
     *
     * @param networkId  echoed into the report, or null when the network does not exist yet
     * @param scenarioId echoed into the report; null unless events were supplied
     * @param input      the candidate clock and every duration to push onto it
     * @param context    which severity rule applies
     */
    public TimeValidationReport check(Long networkId, Long scenarioId, TimeValidationInput input,
            TimeValidationContext context) {
        TimeBasis basis = TimeBasis.of(input.periodLength(), input.roundingPolicy());
        List<DeclaredDuration> declared = input.durations();

        List<TimeFinding> findings = new ArrayList<>();
        Optional<DurationAmount> suggestion = suggest(declared, input.roundingPolicy());

        for (DeclaredDuration duration : declared) {
            conversionFinding(basis, duration, context, suggestion).ifPresent(findings::add);
        }
        finenessFinding(basis, declared).ifPresent(findings::add);
        for (EventWindow event : input.events()) {
            horizonFinding(basis, input.horizonPeriods(), event).ifPresent(findings::add);
        }
        findings.sort(WORST_FIRST);

        int errors = (int) findings.stream().filter(f -> f.severity() == TimeSeverity.ERROR).count();
        return new TimeValidationReport(networkId, scenarioId, context, dto(input.periodLength()),
                input.roundingPolicy(), input.horizonPeriods(),
                suggestion.map(TimeValidationService::dto).orElse(null),
                errors, findings.size() - errors, findings);
    }

    /**
     * The coarsest period that keeps every declared duration within the 10% tolerance — the
     * "suggest period" action and the settings dialog.
     *
     * <p>Coarsest, not finest, because a finer period is never wrong and always slower: it costs a
     * simulation step per period per replication, so the useful answer is the largest step
     * that still represents the data. Candidates are the ladder above plus the greatest common
     * divisor of the declared durations, capped at the smallest of them — a period longer than the
     * shortest duration cannot represent it whatever the rounding does. The gcd is the usual winner
     * and fits every duration exactly; a ladder value wins when it is coarser and still inside the
     * tolerance, which is the case whenever the declared durations are nearly, but not exactly,
     * commensurable.
     *
     * <p>Evaluated under the network's own rounding policy, since that is what will discretise the
     * durations if the suggestion is accepted.
     *
     * @return the suggested period, or empty when the network declares no positive duration — then
     *         nothing constrains the choice and any suggestion would be invented
     */
    public Optional<DurationAmount> suggestPeriod(long networkId, Long scenarioId, long ownerId) {
        Network network = lookup.requireNetwork(networkId, ownerId);
        return suggestPeriod(inputOf(network, eventsOf(network, scenarioId)));
    }

    /** {@link #suggestPeriod(long, Long, long)} for a network that is not persisted. */
    public Optional<DurationAmount> suggestPeriod(TimeValidationInput input) {
        return suggest(input.durations(), input.roundingPolicy());
    }

    // ------------------------------------------------------------------ the checks

    /**
     * The two conversion checks: a duration that converts to nothing, or one that survives but not
     * intact. Mutually exclusive — a duration that rounds to zero is already 100% wrong, and
     * reporting it twice would just make the banner longer.
     */
    private Optional<TimeFinding> conversionFinding(TimeBasis basis, DeclaredDuration declared,
            TimeValidationContext context, Optional<DurationAmount> suggestion) {
        double exact = basis.toExactPeriods(declared.amount());
        if (exact <= 0) {
            return Optional.empty(); // a zero duration converts to zero periods exactly, as meant
        }
        long periods = basis.toPeriods(declared.amount());
        double errorPercent = (periods - exact) / exact * 100;

        if (periods == 0) {
            TimeSeverity severity = context == TimeValidationContext.IMPORT
                    ? TimeSeverity.ERROR
                    : TimeSeverity.WARNING;
            String threshold = basis.roundingPolicy() == RoundingPolicy.NEAREST
                    ? "shorter than half a period"
                    : "shorter than one period";
            return Optional.of(TimeFinding.ofDuration(declared.type(), declared.id(),
                    declared.name(), declared.field(), TimeCheck.DURATION_ROUNDS_TO_ZERO, severity,
                    dto(declared.amount()), periods, errorPercent,
                    ("%s %s is %s (%s) and will be treated as instantaneous. Use a finer period, "
                            + "or accept %s.%s")
                            .formatted(declared.label(), amount(declared.amount()), threshold,
                                    amount(basis.periodLength()), declared.zeroRemedy(),
                                    suggestionSentence(suggestion))));
        }
        if (Math.abs(errorPercent) > ROUNDING_TOLERANCE * 100) {
            return Optional.of(TimeFinding.ofDuration(declared.type(), declared.id(),
                    declared.name(), declared.field(), TimeCheck.DURATION_ROUNDING_ERROR,
                    TimeSeverity.WARNING, dto(declared.amount()), periods, errorPercent,
                    "%s %s rounds to %d period%s (%s, %+.0f%%).%s".formatted(declared.label(),
                            amount(declared.amount()), periods, periods == 1 ? "" : "s",
                            spanOf(basis, periods), errorPercent,
                            suggestionSentence(suggestion))));
        }
        return Optional.empty();
    }

    /**
     * The fineness check: the period is more than 1000× finer than the longest declared duration, so
     * spanning it costs more than a thousand steps — per replication, of which there are 100 by
     * default. Reported once, against the element that owns that duration.
     */
    private Optional<TimeFinding> finenessFinding(TimeBasis basis, List<DeclaredDuration> declared) {
        DeclaredDuration longest = null;
        for (DeclaredDuration candidate : declared) {
            if (longest == null || seconds(candidate.amount()) > seconds(longest.amount())) {
                longest = candidate;
            }
        }
        if (longest == null) {
            return Optional.empty();
        }
        double exact = basis.toExactPeriods(longest.amount());
        if (exact <= FINENESS_LIMIT) {
            return Optional.empty();
        }
        long needed = (long) Math.ceil(exact);
        return Optional.of(TimeFinding.ofElement(longest.type(), longest.id(), longest.name(),
                longest.field(), TimeCheck.PERIOD_TOO_FINE, TimeSeverity.WARNING,
                dto(longest.amount()), needed,
                ("Horizon of %d periods needed to span %s of %s at a period of %s; "
                        + "simulation may be slow.")
                        .formatted(needed, declaredNoun(longest), amount(longest.amount()),
                                amount(basis.periodLength()))));
    }

    /**
     * {@link TimeCheck#EVENT_EXCEEDS_HORIZON}: an event whose window ends after the run does.
     *
     * <p>The arithmetic is {@link EventWindow}'s, not this method's, because
     * {@code DisruptionScenarioService} refuses to store such an event using the same three numbers
     * — see that record. Here it is a finding; there it is a 422.
     */
    private Optional<TimeFinding> horizonFinding(TimeBasis basis, int horizonPeriods,
            EventWindow event) {
        if (!event.exceeds(basis, horizonPeriods)) {
            return Optional.empty();
        }
        long start = event.startPeriod(basis);
        long window = event.windowPeriods(basis);
        long end = event.endPeriod(basis);
        return Optional.of(TimeFinding.ofElement(TimeElementType.DISRUPTION_EVENT, event.id(),
                event.name(), "window", TimeCheck.EVENT_EXCEEDS_HORIZON, TimeSeverity.ERROR,
                dto(event.duration()), end,
                ("Event starts at period %d and lasts %d, so it ends at period %d — after the "
                        + "horizon of %d. Its recovery is never observed, and any metric over it "
                        + "measures the truncation; extend horizon_periods.")
                        .formatted(start, window, end, horizonPeriods)));
    }

    // -------------------------------------------------------------------- suggestion

    private Optional<DurationAmount> suggest(List<DeclaredDuration> declared, RoundingPolicy policy) {
        List<Double> durations = new ArrayList<>();
        for (DeclaredDuration candidate : declared) {
            double seconds = seconds(candidate.amount());
            if (seconds > 0) {
                durations.add(seconds);
            }
        }
        if (durations.isEmpty()) {
            return Optional.empty();
        }

        double smallest = durations.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
        List<Double> candidates = new ArrayList<>();
        for (DurationAmount period : CANDIDATE_PERIODS) {
            candidates.add(seconds(period));
        }
        gcdSeconds(durations).ifPresent(candidates::add);

        double best = 0;
        for (double candidate : candidates) {
            if (candidate <= best || candidate > smallest) {
                continue; // not an improvement, or too coarse to represent the shortest duration
            }
            if (withinTolerance(durations, candidate, policy)) {
                best = candidate;
            }
        }
        return best > 0 ? Optional.of(normalise(best)) : Optional.empty();
    }

    private static boolean withinTolerance(List<Double> durations, double periodSeconds,
            RoundingPolicy policy) {
        for (double duration : durations) {
            double exact = duration / periodSeconds;
            double rounded = policy.round(exact);
            if (Math.abs(rounded - exact) / exact > ROUNDING_TOLERANCE) {
                return false;
            }
        }
        return true;
    }

    /**
     * The greatest common divisor of the declared durations, in whole seconds.
     *
     * <p>Whole seconds because a gcd over reals is not well defined: 6 hours and 36 hours have a
     * clean common measure, 6 hours and 35.9999 hours have none worth offering. Durations that are
     * not a whole number of seconds are simply excluded from this candidate — the ladder still
     * covers them, and the tolerance check still has the final say.
     *
     * @return the gcd, or empty if it is under a second or the durations are not whole seconds
     */
    private static Optional<Double> gcdSeconds(List<Double> durations) {
        long gcd = 0;
        for (double duration : durations) {
            long whole = Math.round(duration);
            if (Math.abs(duration - whole) > 1e-6 || whole <= 0) {
                return Optional.empty();
            }
            gcd = gcd(gcd, whole);
        }
        return gcd >= 1 ? Optional.of((double) gcd) : Optional.empty();
    }

    private static long gcd(long a, long b) {
        while (b != 0) {
            long remainder = a % b;
            a = b;
            b = remainder;
        }
        return a;
    }

    /**
     * A second count as the coarsest unit that measures it whole — 21,600 becomes 6 HOUR, not 360
     * MINUTE, because the dialog offering it is read by a person.
     */
    private static DurationAmount normalise(double seconds) {
        TimeUnit[] units = TimeUnit.values();
        for (int i = units.length - 1; i >= 0; i--) {
            double value = seconds / units[i].secondsOf();
            if (value >= 1 && Math.abs(value - Math.rint(value)) < 1e-9) {
                return DurationAmount.of(Math.rint(value), units[i]);
            }
        }
        return DurationAmount.of(seconds, TimeUnit.SECOND);
    }

    // ----------------------------------------------------------------- the raw data

    /**
     * A persisted network as a {@link TimeValidationInput}: its clock, and every duration underneath
     * it in the order the banner should list them — nodes, then links, then the named scenario's
     * events.
     */
    private TimeValidationInput inputOf(Network network, List<DisruptionEvent> events) {
        List<DeclaredDuration> declared = new ArrayList<>();
        for (Node node : nodes.findByNetworkId(network.getId())) {
            declared.add(new DeclaredDuration(TimeElementType.NODE, node.getId(), node.getName(),
                    "processingTime", node.getProcessingTime()));
        }
        for (Link link : links.findByNetworkId(network.getId())) {
            declared.add(new DeclaredDuration(TimeElementType.LINK, link.getId(), linkName(link),
                    "leadTime", link.getLeadTime()));
        }
        List<EventWindow> windows = new ArrayList<>();
        for (DisruptionEvent event : events) {
            declared.add(new DeclaredDuration(TimeElementType.DISRUPTION_EVENT, event.getId(),
                    eventName(event), "startOffset", event.getStartOffset()));
            declared.add(new DeclaredDuration(TimeElementType.DISRUPTION_EVENT, event.getId(),
                    eventName(event), "duration", event.getDuration()));
            windows.add(new EventWindow(event.getId(), eventName(event), event.getStartOffset(),
                    event.getDuration()));
        }
        return new TimeValidationInput(network.getPeriodLength(), network.getRoundingPolicy(),
                network.getHorizonPeriods(), declared, windows);
    }

    /**
     * @throws EntityNotFoundException if the scenario is missing or belongs to another project —
     *                                 the same answer for both, following {@link NetworkLookup}
     */
    private List<DisruptionEvent> eventsOf(Network network, Long scenarioId) {
        if (scenarioId == null) {
            return List.of();
        }
        DisruptionScenario scenario = scenarios.findWithEventsById(scenarioId)
                .filter(found -> found.getProject().getId().equals(network.getProject().getId()))
                .orElseThrow(() -> new EntityNotFoundException(
                        "No scenario with id %d in this network's project".formatted(scenarioId)));
        return scenario.getEvents();
    }

    // -------------------------------------------------------------------- rendering

    private static DurationDto dto(DurationAmount amount) {
        return DurationDto.of(amount.getValue(), amount.getUnit());
    }

    private static double seconds(DurationAmount amount) {
        return amount.getUnit().secondsOf(amount.getValue());
    }

    /** "36.0 HOUR" — the value and unit as the user stated them, never restated. */
    private static String amount(DurationAmount amount) {
        return amount.getValue() + " " + amount.getUnit();
    }

    /** What {@code periods} periods of this network amount to, in the period's own unit. */
    private static String spanOf(TimeBasis basis, long periods) {
        return periods * basis.periodValue() + " " + basis.periodUnit();
    }

    /**
     * " Consider a period of 6.0 HOUR." — appended to a finding so the remedy travels with the
     * problem, which is the same period the "suggest period" action offers.
     */
    private static String suggestionSentence(Optional<DurationAmount> suggestion) {
        return suggestion.map(period -> " Consider a period of %s.".formatted(amount(period)))
                .orElse("");
    }

    private static String declaredNoun(DeclaredDuration declared) {
        return declared.type() == TimeElementType.LINK ? "a lead time" : "a duration";
    }

    private static String linkName(Link link) {
        return link.getSourceNode().getName() + " → " + link.getTargetNode().getName();
    }

    /** "NODE 12", "REGION EU-West" — enough for the banner to say which bar it means. */
    private static String eventName(DisruptionEvent event) {
        if (event.getTargetRegion() != null) {
            return event.getTargetType() + " " + event.getTargetRegion();
        }
        return event.getTargetId() == null
                ? event.getTargetType().name()
                : event.getTargetType() + " " + event.getTargetId();
    }

}
