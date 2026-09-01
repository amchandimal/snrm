/**
 * SimulationEngine: the discrete-time per-period loop, the minimum-cost flow allocation and the
 * Monte Carlo runner, plus the {@link com.snrm.simulation.SimulationRun},
 * {@link com.snrm.simulation.RunTimeseries}, {@link com.snrm.simulation.RunNodeTimeseries} and
 * {@link com.snrm.simulation.RunLinkTimeseries} tables a run is recorded in.
 *
 * <h2>Engine isolation</h2>
 *
 * <p>The engine operates on two immutable snapshots and nothing else: the {@code NetworkGraph} and
 * the {@code ScenarioPlan} of {@code com.snrm.scenario}. That is what makes hundreds of
 * replications safe to run in parallel without a lock, and it is what will let the Phase 2
 * configuration engine call {@link com.snrm.simulation.MonteCarloRunner} directly to score a
 * candidate it will never persist. {@link com.snrm.simulation.SimulationService} and
 * {@link com.snrm.simulation.SimulationRunWriter} are the module's persistence edge and the only
 * classes here that touch a repository.
 *
 * <p>Runs are submitted through the job framework in {@code common}; every run persists its
 * seed and full parameter set, and a completed run is immutable. A run also freezes the
 * network it references — see {@link com.snrm.simulation.SimulationStatus#isNetworkLocking()} and
 * {@link com.snrm.network.NetworkMutationGuard}.
 *
 * <h2>The model, in one place</h2>
 *
 * <p>The loop takes eleven sentences to state. Turning those into arithmetic required
 * decisions those sentences do not settle, and every one is recorded on the class that implements
 * it. They are collected here because a reader of the results needs all of them at once:
 *
 * <ol>
 *   <li><strong>One commodity.</strong> Products are aggregated per node —
 *       {@link com.snrm.simulation.SimulationNetwork}. Multi-commodity min-cost flow is NP-hard and
 *       would break the engine's polynomial-time bound, and every simulated metric plus the
 *       whole of {@code RUN_TIMESERIES} is product-agnostic anyway.
 *       {@code docs/multi-commodity-extension.md} specifies the extension.</li>
 *   <li><strong>Material enters at supply origins</strong> — {@code SUPPLIER} or {@code PLANT} nodes
 *       with no inbound arc, fixed on the intact graph. Verbatim the convention
 *       {@code com.snrm.metrics.topological} already uses, so the two engines cannot disagree about
 *       where a network's supply begins. Every other node's capacity is a throughput ceiling.</li>
 *   <li><strong>A period is one hop for delayed material and a whole path for instantaneous
 *       material.</strong> Zero-lead-time arcs are traversable within the period — this is the
 *       "re-routes optimally" — while a positive lead time lands the shipment as the
 *       target's inventory later. {@link com.snrm.simulation.FlowAllocator} draws the graph that
 *       expresses both at once.</li>
 *   <li><strong>Stock is pulled by an order-up-to level</strong>: safety stock plus one period of
 *       the demand a node covers, netted against what is on hand and in transit. Safety stock alone
 *       would leave a network with lead times unable to move anything at all —
 *       {@code SimulationNetwork.computeReplenishTargets} states why.</li>
 *   <li><strong>The penalty arc is lexicographic, not economic.</strong> It dominates every path
 *       cost, so demand is served whenever it physically can be, and cost is minimised among the
 *       solutions that serve it. Pricing it at the goods' value would make {@code FILL_RATE} move
 *       when a price changed — {@link com.snrm.simulation.FlowAllocator} argues it out. The
 *       shortage's <em>cost</em> is still the goods' value, accounted afterwards.</li>
 *   <li><strong>Availability multiplies, failures are total, and an uncapped element cannot be
 *       partly disrupted</strong> — {@link com.snrm.simulation.AvailabilityModel}.</li>
 *   <li><strong>Randomness is addressed, not consumed.</strong>
 *       {@link com.snrm.simulation.ReplicationRng} derives a generator from
 *       {@code (seed, replication, stream, period)}, so a stored seed reproduces a run across code
 *       changes — and the baseline set can share the disrupted set's demand and outages
 *       exactly, which makes every difference metric a paired difference.</li>
 *   <li><strong>Quantities live on a fixed-point grid</strong>, because JGraphT's minimum-cost flow
 *       takes integer capacities — {@link com.snrm.simulation.Quantiser}, which also states which
 *       way each quantity rounds and why the two directions differ.</li>
 *   <li><strong>The per-element series is folded, not kept</strong> (FR-18). The loop fills
 *       an {@link com.snrm.simulation.ElementTrace} per replication;
 *       {@link com.snrm.simulation.ElementAccumulator} adds it into per-period sums the moment the
 *       replication finishes and drops it, so the element memory is
 *       {@code O(H × (N + E))} whatever the replication count is. Recording reads the loop's own
 *       state and feeds nothing back into it, so it changes no simulated number and does not move
 *       {@code ENGINE_VERSION}. Two of its quantities can be genuinely undefined in a period —
 *       inbound lead where nothing was dispatched, utilisation on an uncapped or fully-out arc —
 *       and travel as {@code NaN} to a SQL {@code NULL} and a JSON {@code null}, never a zero.</li>
 * </ol>
 *
 * <h2>Verifying it</h2>
 *
 * <p>{@code docs/simulation-verification.md} works a three-node chain with one deterministic
 * disruption through this loop by hand, period by period, and derives {@code TTR},
 * {@code LOSS_AREA} and {@code RESILIENCE_INDEX} from the resulting table — then states the exact
 * values the API returns for a fixed seed and one replication.
 * {@code samples/simulation-verification-3-node/} is that network in the canonical import schema.
 * That document is the specification; a disagreement is a defect in one of the two.
 *
 * <h2>Known limits</h2>
 *
 * <ul>
 *   <li>A flow problem is built and solved from scratch in every period of every replication.
 *       "Incremental re-solve only on periods where availability changed" is the known
 *       mitigation, and it is not implemented — there is no measurement yet to justify the
 *       complexity, and the undisrupted baseline set is exactly where it would pay most.</li>
 *   <li>The replenishment policy is myopic: it orders to a fixed target rather than forecasting.
 *       A base-stock policy with demand forecasting is the named extension, and safety stock is
 *       already the Phase 2 lever that compensates.</li>
 *   <li>Backorders are not modelled. Unmet demand in a period is lost, not carried forward — which
 *       is what makes {@code FILL_RATE} a per-period question and keeps the flow problem
 *       memoryless.</li>
 * </ul>
 */
package com.snrm.simulation;
