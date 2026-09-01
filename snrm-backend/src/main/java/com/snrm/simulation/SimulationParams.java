package com.snrm.simulation;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The fully resolved parameter set of one run — what {@code simulation_run.params_json} holds.
 *
 * <blockquote>"The run stores seed + parameters for exact reproducibility."</blockquote>
 *
 * <p><strong>Resolved, not requested.</strong> Nothing here is null and nothing is a default waiting
 * to be applied: {@link #resolve} takes whatever the client sent, fills every gap from the scenario
 * and from the constants below, and produces this. That is what makes the record a replay
 * instruction rather than a copy of a request — re-running a completed run means reading this back
 * and running it, with no reference to what the scenario says today (a scenario can be edited after
 * a run; {@code DisruptionScenarioService} says so explicitly).
 *
 * <p>{@link #engineVersion} exists for the same reason. A stored parameter set replays exactly only
 * against the engine that wrote it; when the flow construction or a cost convention changes, this
 * string is what lets a reader of an old {@code params_json} know that it did.
 *
 * @param replications               how many disrupted replications to run. The baseline set
 *                                   adds the same number again, paired by index, so a run
 *                                   executes {@code 2 × replications} in total
 * @param seed                       the base seed actually used. Never null here — a scenario with a
 *                                   null seed means "draw a fresh one per run", and this records
 *                                   what was drawn, which is the whole of reproducibility
 * @param horizonPeriods             how many periods the run covers, from the network
 * @param demandNoiseCv              coefficient of variation of the multiplicative demand
 *                                   noise. <strong>0 by default</strong>, so a run is exactly
 *                                   deterministic unless the researcher asks for noise; a hidden
 *                                   default of 0.1 would make every hand-checkable example
 *                                   uncheckable
 * @param timingJitterPeriods        maximum whole-period perturbation of an event's start and
 *                                   duration, drawn uniformly from {@code [−j, +j]}. <strong>0 by
 *                                   default</strong>, for the same reason
 * @param includeRandomFailures      whether the per-period {@code failure_prob} draws
 *                                   apply at all. True by default: a network's own unreliability is
 *                                   part of the network, and a node with {@code failure_prob = 0} —
 *                                   which is the column default — costs nothing either way
 * @param baselineSuppressesFailures whether the undisrupted baseline set <em>also</em> drops the
 *                                   random failures. <strong>False by default</strong>: random
 *                                   outages are a property of the network rather than of the
 *                                   scenario, so leaving them in both sets is what stops
 *                                   {@code DISRUPTION_COST_DELTA} from charging the network's
 *                                   inherent unreliability to the disruption. Setting it true makes
 *                                   the baseline a perfectly reliable network, which is the right
 *                                   comparator for a study asking what the network would do if
 *                                   nothing at all went wrong
 * @param safetyStockPriority        what a unit of safety-stock shortfall is worth relative to a
 *                                   unit of unmet customer demand, in {@code [0,1)}. 0.1 by
 *                                   default — enough to make the network pre-position stock when it
 *                                   is cheap to do so, never enough to make it prefer stock over a
 *                                   customer. 0 switches replenishment off entirely
 * @param unmetDemandPenalty         cost of one unit of unmet demand, or null to price it from the
 *                                   product the customer wanted ({@code product.unit_value},
 *                                   demand-weighted where a customer wants several). This is both
 *                                   the penalty arc's cost in the flow problem and the shortage cost
 *                                   in the trace, deliberately the same number
 * @param quantum                    fixed-point resolution of the flow solve: quantities are carried
 *                                   to {@code 1/quantum} of a unit, because JGraphT's minimum-cost
 *                                   flow takes integer capacities and supplies. 1000 by default
 * @param recordElementTimeseries    whether the run records the per-element series.
 *                                   <strong>True by default, and never null once constructed</strong>
 *                                   — the compact constructor normalises it, which is precisely what
 *                                   makes a {@code params_json} written before this field existed
 *                                   read back as true rather than as a run that declined to record.
 *                                   It is boxed for that one reason; a primitive {@code boolean}
 *                                   would deserialise a missing field to false and misreport every
 *                                   pre-V9 run. What a given run <em>actually</em> holds is answered
 *                                   by {@code GET /simulations/{runId}/timeseries/elements} and its
 *                                   {@code available} flag, not by this field. Recording changes no
 *                                   simulated number, so {@link #ENGINE_VERSION} does not move for it
 * @param engineVersion              which engine produced this run
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SimulationParams(
        int replications,
        long seed,
        int horizonPeriods,
        double demandNoiseCv,
        int timingJitterPeriods,
        boolean includeRandomFailures,
        boolean baselineSuppressesFailures,
        double safetyStockPriority,
        Double unmetDemandPenalty,
        int quantum,
        Boolean recordElementTimeseries,
        String engineVersion) {

    /** N replications, defaulting to 100. Also {@code DisruptionScenario.DEFAULT_REPLICATIONS}. */
    public static final int DEFAULT_REPLICATIONS = 100;

    /** Deterministic demand unless asked otherwise — see the parameter note. */
    public static final double DEFAULT_DEMAND_NOISE_CV = 0;

    /** Deterministic event timing unless asked otherwise. */
    public static final int DEFAULT_TIMING_JITTER = 0;

    /** See {@link #safetyStockPriority}. */
    public static final double DEFAULT_SAFETY_STOCK_PRIORITY = 0.1;

    /** Milli-units. See {@link com.snrm.simulation.Quantiser} for what the residual costs. */
    public static final int DEFAULT_QUANTUM = 1000;

    /**
     * The engine these parameters describe. Bump it when the flow construction, the cost accounting
     * or a stochastic convention changes, so an old {@code params_json} is legible as belonging to a
     * different model rather than replaying silently into a different answer.
     */
    public static final String ENGINE_VERSION = "1.0";

    /** Upper bound on {@code replications}, so one submission cannot exhaust the heap. */
    public static final int MAX_REPLICATIONS = 10_000;

    /** Validates the resolved set; every caller reaches this through {@link #resolve}. */
    public SimulationParams {
        if (replications < 1 || replications > MAX_REPLICATIONS) {
            throw new IllegalArgumentException(
                    "replications must be between 1 and %d, was %d"
                            .formatted(MAX_REPLICATIONS, replications));
        }
        if (horizonPeriods < 1) {
            throw new IllegalArgumentException("horizonPeriods must be at least 1, was "
                    + horizonPeriods);
        }
        if (demandNoiseCv < 0) {
            throw new IllegalArgumentException("demandNoiseCv must not be negative, was "
                    + demandNoiseCv);
        }
        if (timingJitterPeriods < 0) {
            throw new IllegalArgumentException("timingJitterPeriods must not be negative, was "
                    + timingJitterPeriods);
        }
        if (safetyStockPriority < 0 || safetyStockPriority >= 1) {
            throw new IllegalArgumentException(("safetyStockPriority must be in [0,1), was %s. At 1 "
                    + "a unit of safety-stock shortfall would cost as much as a lost customer and "
                    + "the network would hoard rather than serve.")
                    .formatted(safetyStockPriority));
        }
        if (unmetDemandPenalty != null && unmetDemandPenalty < 0) {
            throw new IllegalArgumentException("unmetDemandPenalty must not be negative, was "
                    + unmetDemandPenalty);
        }
        if (quantum < 1) {
            throw new IllegalArgumentException("quantum must be at least 1, was " + quantum);
        }
        // Absent means "written before this flag existed", and the honest reading of such a document
        // is the current default — re-submitting it is what a replay does. See the parameter note.
        recordElementTimeseries = recordElementTimeseries == null || recordElementTimeseries;
        engineVersion = engineVersion == null ? ENGINE_VERSION : engineVersion;
    }

    /**
     * Fills every gap and produces the set the run will actually use.
     *
     * <p>The precedence is request → scenario → constant, and it is deliberate in one place:
     * {@code replications} and {@code seed} live on the scenario so a researcher can set
     * them once and re-submit, but a request may override either for a quick low-replication look
     * without editing the scenario every variant shares.
     *
     * @param requested        what the client sent; every field nullable, and null means "not stated"
     * @param scenarioReplications the scenario's configured count
     * @param scenarioSeed     the scenario's configured seed, or null for "draw one per run"
     * @param horizonPeriods   the network's horizon — never overridable, because a run
     *                         evaluates the network as it stands and the horizon is part of it
     * @param drawnSeed        the seed to record when the scenario has none. Supplied by the caller
     *                         rather than drawn here so this method stays pure and testable
     */
    public static SimulationParams resolve(SimulationParamsDto requested, int scenarioReplications,
            Long scenarioSeed, int horizonPeriods, long drawnSeed) {
        SimulationParamsDto given = requested == null ? SimulationParamsDto.empty() : requested;
        long seed = given.seed() != null ? given.seed()
                : scenarioSeed != null ? scenarioSeed
                : drawnSeed;
        int replications = given.replications() != null ? given.replications()
                : scenarioReplications > 0 ? scenarioReplications
                : DEFAULT_REPLICATIONS;
        return new SimulationParams(
                replications,
                seed,
                horizonPeriods,
                given.demandNoiseCv() != null ? given.demandNoiseCv() : DEFAULT_DEMAND_NOISE_CV,
                given.timingJitterPeriods() != null ? given.timingJitterPeriods()
                        : DEFAULT_TIMING_JITTER,
                given.includeRandomFailures() == null || given.includeRandomFailures(),
                given.baselineSuppressesFailures() != null && given.baselineSuppressesFailures(),
                given.safetyStockPriority() != null ? given.safetyStockPriority()
                        : DEFAULT_SAFETY_STOCK_PRIORITY,
                given.unmetDemandPenalty(),
                given.quantum() != null ? given.quantum() : DEFAULT_QUANTUM,
                given.recordElementTimeseries() == null || given.recordElementTimeseries(),
                ENGINE_VERSION);
    }

    /** Total replications executed, disrupted plus the paired baseline set. */
    public int totalReplications() {
        return replications * 2;
    }

    /** Whether any stochastic element is active at all — the hand-checkable case. */
    public boolean isDeterministic() {
        return demandNoiseCv == 0 && timingJitterPeriods == 0;
    }
}
