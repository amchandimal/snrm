/**
 * The simulated half of the metric suite: the eleven metrics computed from the replication
 * traces of a simulation run.
 *
 * <h2>What the whole family agrees on</h2>
 *
 * <p>Eleven calculators over one {@code SimulationTraces}. Six conventions are shared by all of them,
 * stated once here so each class can state only what is its own:
 *
 * <ul>
 *   <li><strong>The disrupted set is what is measured; the baseline set is what it is measured
 *       against.</strong> {@code FILL_RATE}, {@code SERVICE_LEVEL}, {@code MIN_FILL_RATE},
 *       {@code TOTAL_COST}, {@code CVAR_COST}, {@code AVG_INVENTORY} and {@code AVG_PIPELINE}
 *       describe what the network did. {@code TTR}, {@code LOSS_AREA},
 *       {@code DISRUPTION_COST_DELTA} and {@code RESILIENCE_INDEX} are
 *       differences or ratios against the undisrupted twin, which every run includes for
 *       exactly that reason.</li>
 *   <li><strong>Comparisons are paired.</strong> Baseline replication <em>i</em> shares disrupted
 *       replication <em>i</em>'s demand realisation and random outages, because
 *       {@code ReplicationRng} addresses those streams by index. So every difference above is a
 *       paired difference and its confidence interval reflects the disruption rather than the
 *       variance of two independent samples — several-fold tighter for the same replication
 *       count.</li>
 *   <li><strong>Mean and 95% CI, by the normal approximation.</strong> One implementation, in
 *       {@code ReplicationStatistics}, so eleven calculators cannot come to disagree about what an
 *       interval on this suite means. {@code N = 1} publishes the mean and no interval; small
 *       {@code N} publishes an interval that is known to be narrow. {@code CVAR_COST} publishes
 *       none at all, and says why. A deterministic run at {@code N > 1} publishes a
 *       <em>zero-width</em> interval, which is a real answer and not a defect.</li>
 *   <li><strong>A direction is a claim, and {@code NEUTRAL} is one of the answers.</strong>
 *       {@code AVG_INVENTORY} and {@code AVG_PIPELINE} are the two here that decline to rank:
 *       leaner versus more buffered is the trade-off a resilience study
 *       characterises rather than one this suite settles, so the comparison view highlights no cell
 *       in those rows and the radar leaves them out — the treatment {@code DENSITY},
 *       {@code AVG_PATH} and {@code CLUSTERING} already get.</li>
 *   <li><strong>An undefined metric produces no value.</strong> Empty is legitimate where a metric
 *       is undefined for this network, and is not an error, so every
 *       calculator here uses it rather than inventing a zero. A run whose scenario disrupted nothing
 *       returns no {@code TTR} row, which is a different statement from {@code TTR = 0}.</li>
 *   <li><strong>Everything is in periods.</strong> A trace counts steps, and only {@code TTR} is
 *       time-valued; it declares {@link com.snrm.metrics.MetricCalculator#timeValued()} and the
 *       registry attaches the network's period unit. No calculator here imports
 *       {@code TimeUnit}.</li>
 *   <li><strong>Read the trace, nothing else.</strong> No repository, no entity, no engine class —
 *       a calculator sees {@code MetricContext} and returns value objects, which is
 *       what will let the Phase 2 search score thousands of candidates without a
 *       database.</li>
 * </ul>
 *
 * <h2>Why {@code metrics} depends on {@code simulation}</h2>
 *
 * <p>These classes import {@code com.snrm.simulation}'s trace records, and
 * {@code com.snrm.simulation.SimulationService} calls {@code MetricCalculatorRegistry} — so the two
 * packages depend on each other. That is deliberate and is the same relationship
 * {@code com.snrm.scenario} and {@code com.snrm.network} already have. The trace list lives
 * inside {@code MetricContext}, so the dependency is intrinsic; and the cycle is confined to
 * value objects in one direction and to the module edge in the other — no calculator can reach a
 * repository, and no engine class can reach a metric row.
 *
 * <h2>Verifying them</h2>
 *
 * <p>{@code docs/simulation-verification.md} works a three-node chain with one deterministic
 * disruption through the per-period loop by hand, period by period, and derives {@code TTR},
 * {@code LOSS_AREA} and {@code RESILIENCE_INDEX} from that table — then states the exact values the
 * API returns for a run with a fixed seed and one replication.
 * {@code samples/simulation-verification-3-node/} is that network in the canonical import
 * schema. That worked example is what these classes are checked against; a disagreement is
 * a defect in one of the two and never a rounding difference to shrug at.
 *
 * <p>The inventory pair has a <strong>second</strong> worked example, and it needs one: every lead
 * time on the three-node chain is zero, so {@code AVG_PIPELINE} is 0.0 there and the document can say
 * nothing about material in flight. {@code samples/four-echelon-playback/README.md} §6.1 derives
 * {@code AVG_INVENTORY} = 22.0 and {@code AVG_PIPELINE} = 29.0 on a chain of one-period legs, and
 * §8.5 does the same for its disruption run.
 */
package com.snrm.metrics.simulated;
