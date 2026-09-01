package com.snrm.scenario;

import java.util.List;

/**
 * A disruption scenario as the simulation engine sees it — the scenario's counterpart to the
 * {@code NetworkGraph} snapshot.
 *
 * <p>The engines are allowed to see two things: an immutable picture of the network, and an
 * immutable picture of what happens to it. This is the second. Everything a replication needs about
 * the scenario is here, already resolved against the network the run evaluates, and nothing here can
 * reach a repository, a JPA entity or a lazy association — which is what makes the replication
 * fan-out safe without a lock and, later, lets the Phase 2 search evaluate a candidate
 * against a scenario set with no database round trip per evaluation.
 *
 * <p><strong>A plan is built against one network.</strong> A scenario is project-scoped so it can be
 * replayed across configuration variants, but its events name node ids, link ids and
 * region tags, which mean different things in different variants. {@link ScenarioPlanFactory}
 * resolves them against the network of the run being submitted, and {@link #unresolvedEventIds()}
 * records the ones that resolved to nothing — the honest answer to the question
 * {@code DisruptionScenarioService} says it cannot answer at write time.
 *
 * @param scenarioId          the {@code disruption_scenario.id} this came from, or <strong>null for
 *                            the baseline run of FR-17</strong> — see {@link #baseline(long)}
 * @param scenarioName        its name, for labelling results; null for a baseline plan
 * @param networkId           the network the targets were resolved against
 * @param events              the events, in start order, with targets resolved and timing in periods
 * @param unresolvedEventIds  events that named a node, link or region this network does not have.
 *                            Not silently dropped: a run whose disruption struck nothing would
 *                            complete and show a network shrugging off a disruption it never
 *                            received, which is the one false negative a resilience study cannot
 *                            afford
 */
public record ScenarioPlan(
        Long scenarioId,
        String scenarioName,
        long networkId,
        List<PlannedEvent> events,
        List<Long> unresolvedEventIds) {

    /** Defensive copies; a plan is shared by every replication of a run. */
    public ScenarioPlan {
        events = events == null ? List.of() : List.copyOf(events);
        unresolvedEventIds = unresolvedEventIds == null ? List.of() : List.copyOf(unresolvedEventIds);
    }

    /**
     * The plan of a <strong>baseline run</strong> — no scenario at all (FR-17).
     *
     * <p>Distinct from {@link #withoutEvents()}, which is the paired undisrupted set of a scenario
     * run and remembers which scenario it is the baseline <em>of</em>. This one came from nothing: a
     * {@code POST /simulations} with no {@code scenarioId}, asking how the configuration performs
     * when nothing goes wrong. It is empty by construction, so {@link #isUndisrupted()} holds and
     * the runner skips the pairing — the undisrupted set of an undisrupted run would be the same
     * replications computed twice.
     */
    public static ScenarioPlan baseline(long networkId) {
        return new ScenarioPlan(null, null, networkId, List.of(), List.of());
    }

    /** Events that can actually change something — the ones a replication needs to draw for. */
    public List<PlannedEvent> effectiveEvents() {
        return events.stream().filter(PlannedEvent::hasEffect).toList();
    }

    /**
     * True when this scenario disrupts nothing at all.
     *
     * <p>A legitimate state, not an error: a scenario with no events is how a researcher measures a
     * network's undisrupted behaviour, and the run then reports a disrupted set identical to its
     * baseline. The metrics defined relative to an onset — {@code TTR},
     * {@code RESILIENCE_INDEX} — have no observation to make and say so by producing no row, which
     * the calculator interface permits ("empty is legitimate where the metric is undefined for this
     * network, and is not an error").
     */
    public boolean isUndisrupted() {
        return effectiveEvents().isEmpty();
    }

    /** The earliest period any event could fire, or {@link Integer#MAX_VALUE} if none can. */
    public long earliestOnset() {
        return effectiveEvents().stream()
                .mapToLong(PlannedEvent::startPeriod)
                .min()
                .orElse(Integer.MAX_VALUE);
    }

    /**
     * A plan with the same events but no disruption — the undisrupted baseline set.
     *
     * <p>"Every simulation automatically includes one undisrupted baseline replication set, since
     * {@code TTR}, {@code LOSS_AREA}, {@code RESILIENCE_INDEX} and {@code DISRUPTION_COST_DELTA} are
     * all defined relative to baseline performance." The baseline differs from the disrupted set in
     * exactly one respect — the events do not occur — so it is the same plan with an empty event
     * list rather than a separately constructed object that could drift from it.
     */
    public ScenarioPlan withoutEvents() {
        return new ScenarioPlan(scenarioId, scenarioName, networkId, List.of(), unresolvedEventIds);
    }
}
