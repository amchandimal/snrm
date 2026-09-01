/**
 * DisruptionScenario, DisruptionEvent, and recovery profiles.
 *
 * <p>The persisted enum is {@link com.snrm.scenario.RecoveryProfileType}; the name
 * {@link com.snrm.scenario.RecoveryProfile} is reserved for the strategy interface returning
 * {@code availability(t)}, which each constant selects through
 * {@link com.snrm.scenario.RecoveryProfiles}. Adding a fourth shape is one {@code @Component} and one
 * enum constant, with no change to the simulation engine.
 *
 * <p>Scenarios are project-scoped, so one scenario can be replayed against every configuration
 * variant — which is what makes the comparison view meaningful.
 *
 * <h2>The engine sees a plan, not entities</h2>
 *
 * <p>{@link com.snrm.scenario.ScenarioPlanFactory} builds a {@link com.snrm.scenario.ScenarioPlan}:
 * the scenario with every target resolved to node and link ids <em>in one network</em>, every window
 * discretised onto that network's clock, and every recovery profile resolved from the enum to the
 * strategy bean. It is to a scenario what {@code NetworkGraphFactory} is to a network, and for the
 * same reason — the Monte Carlo fan-out must not touch a lazy association, and a run must
 * evaluate the scenario as it stood when the run was accepted.
 *
 * <p>That factory is also where the cross-variant question this package could not answer at write
 * time finally is. An event that names a node another network's baseline had resolves to nothing
 * here; the plan records it, and the run is refused (see {@code UnresolvedEventException}). Running
 * it with the event silently dropped would produce a completed result showing a network absorbing a
 * disruption it never received.
 *
 * <h2>Why this package depends on {@code com.snrm.network}</h2>
 *
 * <p>A scenario names no network and an event stores none, yet {@link
 * com.snrm.scenario.DisruptionScenarioService} reads {@code Network}, {@code NodeRepository},
 * {@code LinkRepository} and {@code TimeValidationInput.EventWindow}. That is not the scoping
 * leaking; it is what validating an event costs. An event's target is a {@code node.id}, a
 * {@code link.id} or a {@code node.region} tag, and its window is a pair of real durations that has
 * to fit inside a horizon — none of which exists without a network. So every event write
 * takes a {@code networkId} query parameter naming the network it is authored against, resolves
 * against it, and does not store it.
 *
 * <p>The dependency runs both ways: {@code TimeValidationService} in {@code network} reads scenarios
 * to produce its {@code EVENT_EXCEEDS_HORIZON} finding, for the same reason — whether an event
 * outruns a horizon is a question about the (network, scenario) pair and neither side owns it. The
 * shared arithmetic lives in one place, on {@code TimeValidationInput.EventWindow}, so the banner
 * that reports the overrun and the endpoint that refuses it cannot come to disagree about which
 * period an event ends in.
 */
package com.snrm.scenario;
