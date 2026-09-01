package com.snrm.simulation;

import com.snrm.common.DomainException;

import java.util.List;

/**
 * A scenario event named a node, link or region that this network does not have.
 *
 * <p><strong>This is the question {@code DisruptionScenarioService} said it could not answer.</strong>
 * That class states the cost of scoping a scenario to the project: "an event written against the
 * baseline holds baseline node ids, and reconciling that across variants is a question the
 * simulation engine will have to answer." This is the answer, and it is a refusal.
 *
 * <p>The alternative — running the scenario with the unresolvable events silently dropped — is the
 * one failure a resilience study cannot afford. The run would complete, the metrics would be
 * computed, and the results would show a network shrugging off a disruption it never received: a
 * false negative that looks exactly like a resilient configuration. {@code RegionService} refuses an
 * empty region at write time for the same reason and says so in the same words.
 *
 * <p>422 with the offending event ids attached, so the scenario editor can highlight the bars that
 * need re-targeting rather than making the researcher find them. The remedy is either to
 * re-target those events against this network or to duplicate the scenario per variant —
 * {@code POST /scenarios/{id}/duplicate} exists for that.
 */
public class UnresolvedEventException extends DomainException {

    private final long networkId;
    private final long scenarioId;
    private final List<Long> eventIds;

    public UnresolvedEventException(long networkId, long scenarioId, List<Long> eventIds) {
        super(("Scenario %d has %d event(s) that name nothing in network %d: %s. A run cannot "
                + "silently drop them — the result would show a network absorbing a disruption it "
                + "never received. Re-target the events against this network, or "
                + "duplicate the scenario for it.")
                .formatted(scenarioId, eventIds.size(), networkId, eventIds));
        this.networkId = networkId;
        this.scenarioId = scenarioId;
        this.eventIds = List.copyOf(eventIds);
    }

    @Override
    public String code() {
        return "EVENT_TARGET_UNRESOLVED";
    }

    public long getNetworkId() {
        return networkId;
    }

    public long getScenarioId() {
        return scenarioId;
    }

    /** The events to highlight in the timeline editor. */
    public List<Long> getEventIds() {
        return eventIds;
    }
}
