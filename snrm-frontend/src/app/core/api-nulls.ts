import {
  MetricResult,
  NetworkLink,
  NetworkNode,
  Rate,
  SimulationResults,
  SimulationRun,
  TopologicalMetricsResponse,
} from './models';

/**
 * Absence turned back into null, at the boundary a DTO enters the app.
 *
 * ## The mismatch
 *
 * The API runs `spring.jackson.default-property-inclusion=non_null`, so **a null field is not sent
 * at all**. A baseline run's `scenarioId`, a locally computed run's `importedAt`, a metric's absent
 * confidence bounds, a NETWORK-scoped row's `scopeId` - none of them appear in the JSON. The models
 * in `core/models/` declare every one of those as `T | null`, which is what the design means
 * and what every reader in the app was written against.
 *
 * Both halves are defensible on their own and the gap between them is not. `undefined` fails every
 * `=== null` test and passes every `!== null` one, so the two ends disagree in the worst possible
 * direction: **silently, and inverted.** Three live consequences, all found from one payload:
 *
 * - `restoredRuns` in `run-discard.ts` counts `importedAt !== null`, so every run this installation
 *   computed itself was being reported as a **restored archive result** - a warning line on an
 *   irreversible action (FR-20), attached to runs it is not true of.
 * - `scenarioId === null` is how a **baseline run** is recognised (FR-17). Absent rather than null,
 *   so a baseline run read as a disruption run: the wrong prose, a triangle claimed where the two
 *   curves coincide by definition, and four metrics reported as missing rather than as inapplicable.
 * - `ciLow !== null && ciHigh !== null` is how `ci-value` decides it has an interval to draw, so a
 *   metric with no interval - `CVAR_COST`, and the whole topological suite - was being asked to
 *   render one out of two undefineds.
 *
 * ## Why here, and why not on either end
 *
 * Not on the backend: `non_null` is deliberate and load-bearing elsewhere - `ImportDiagnostic`,
 * `TimeFinding` and `ProjectRestoreService` each document behaviour that depends on it, and the one
 * place the backend needed the opposite it said so locally (`@JsonInclude(ALWAYS)` on
 * `SimulationParams`). Not in each reader either: "remember that this field might be undefined" is a
 * rule that has to be obeyed at every site forever, and `run-discard.spec.ts` shows how that ends -
 * it builds its runs with `importedAt: null` written out, so it passes against a wire shape the app
 * never receives.
 *
 * So it is done once, where the response arrives, and everything downstream may go on believing the
 * models. These functions are pure and have a spec for the same reason `problem-details.ts` does:
 * reading the wire correctly is the part that has to be right.
 */

/** Fields of a run the API omits when they are null. */
type OmissibleRunField =
  | 'scenarioId'
  | 'scenarioName'
  | 'startedAt'
  | 'finishedAt'
  | 'params'
  | 'importedAt'
  | 'sourceRunId'
  | 'unresolvedEventIds';

/** A run as the wire can carry it: every nullable field may simply not be there. */
export type WireRun = Omit<SimulationRun, OmissibleRunField> &
  Partial<Pick<SimulationRun, OmissibleRunField>>;

/** Fields of a metric row the API omits when they are null. */
type OmissibleMetricField = 'id' | 'runId' | 'scopeId' | 'scopeName' | 'ciLow' | 'ciHigh' | 'displayUnit';

/** A metric row as the wire can carry it. */
export type WireMetric = Omit<MetricResult, OmissibleMetricField> &
  Partial<Pick<MetricResult, OmissibleMetricField>>;

/** `GET /simulations/{runId}` as the wire can carry it. */
export type WireResults = Omit<SimulationResults, 'run' | 'metrics'> & {
  readonly run: WireRun;
  readonly metrics?: readonly WireMetric[];
};

/** `GET /networks/{id}/metrics/topological` as the wire can carry it. */
export type WireTopologicalResponse = Omit<TopologicalMetricsResponse, 'metrics'> & {
  readonly metrics: readonly WireMetric[];
};

/**
 * One run, with every omitted field restored to the null the models promise.
 *
 * `unresolvedEventIds` becomes `[]` rather than null, because that is what its own contract says an
 * accepted run carries and no reader of it has a null branch.
 *
 * **`params` may legitimately be null after this**, and that is not the same kind of absence as the
 * rest. The other fields are null because the run has nothing to say; a missing parameter set means
 * the server could not read `params_json` back and said so by omission - the seed, the replication
 * count and the engine version that make a run reproducible are simply not available. A
 * view renders that as unavailable; it must never render it as a zero, and it must not crash on it.
 */
export function normaliseRun(run: WireRun): SimulationRun {
  return {
    ...run,
    scenarioId: run.scenarioId ?? null,
    scenarioName: run.scenarioName ?? null,
    startedAt: run.startedAt ?? null,
    finishedAt: run.finishedAt ?? null,
    params: run.params ?? null,
    importedAt: run.importedAt ?? null,
    sourceRunId: run.sourceRunId ?? null,
    unresolvedEventIds: run.unresolvedEventIds ?? [],
  };
}

/** Every run of a list - `GET /networks/{id}/runs`, which the FR-20 discard is built from. */
export function normaliseRuns(runs: readonly WireRun[]): readonly SimulationRun[] {
  return runs.map(normaliseRun);
}

/**
 * One metric row.
 *
 * `id` stays optional because the model already declares it so; everything else becomes explicit
 * null, which is what "this metric has no interval" and "this row is not scoped to an element" have
 * to look like by the time `ci-value` and the criticality table read them.
 */
export function normaliseMetric(metric: WireMetric): MetricResult {
  return {
    ...metric,
    runId: metric.runId ?? null,
    scopeId: metric.scopeId ?? null,
    scopeName: metric.scopeName ?? null,
    ciLow: metric.ciLow ?? null,
    ciHigh: metric.ciHigh ?? null,
    displayUnit: metric.displayUnit ?? null,
  };
}

export function normaliseMetrics(metrics: readonly WireMetric[]): readonly MetricResult[] {
  return metrics.map(normaliseMetric);
}

// ------------------------------------------------------ nodes and links (FR-22)

/**
 * A rate as the wire can carry it: an **unconstrained** capacity has no `value` at all.
 *
 * `Rate.value === null` means unconstrained and is the test two readings depend on - the
 * property panel's *unconstrained*, and the element charts' *uncapped* versus *no capacity
 * available*, which are two different claims about an arc. Absent rather than null, the
 * `=== null` test fails and an uncapped arc reads as a capped one whose ceiling an outage took to
 * zero: a chart of nulls captioned as an outage, on a link that was never disrupted.
 */
export type WireRate = Omit<Rate, 'value'> & Partial<Pick<Rate, 'value'>>;

/** Fields of a node the API omits when they are null. */
type OmissibleNodeField = 'region' | 'lat' | 'lng' | 'posX' | 'posY' | 'caption';

/** A node as the wire can carry it. */
export type WireNode = Omit<NetworkNode, OmissibleNodeField | 'capacity'> &
  Partial<Pick<NetworkNode, OmissibleNodeField>> & { readonly capacity: WireRate };

/** A link as the wire can carry it: its capacity, and the caption of FR-30. */
export type WireLink = Omit<NetworkLink, 'capacity' | 'caption'> &
  Partial<Pick<NetworkLink, 'caption'>> & { readonly capacity: WireRate };

function normaliseRate(rate: WireRate): Rate {
  return { ...rate, value: rate.value ?? null };
}

/**
 * One node, with every omitted field restored to the null the models promise.
 *
 * `posX`/`posY` are the pair FR-22's network inspector chooses its arrangement by: a node the
 * importer wrote without a layout has neither, and *neither* is what makes the miniature fall back to
 * an echelon arrangement instead of drawing every unplaced node on top of the origin
 * (`features/simulations/mini-map-layout.ts`). Undefined passes the `!== null` test that decides it,
 * so a whole imported network would have drawn as one dot in the corner.
 *
 * `caption` is the fourth occurrence of the same trap and the most consequential of them, because
 * the editor's undo depends on it (FR-30). `element-captions.previousAttributeValue` reads a null
 * caption as "there was none, so undoing this edit means sending the empty string that clears it";
 * an *undefined* one takes the `!== null` branch instead and puts `caption: undefined` in the patch,
 * which `JSON.stringify` drops - so undoing "typed a caption on a node that had none" would leave
 * the caption on the canvas, silently, having reported success. `captionVisible` needs nothing: it
 * is `NOT NULL` in `V10__element_captions.sql`, so it is never omitted.
 */
export function normaliseNode(node: WireNode): NetworkNode {
  return {
    ...node,
    capacity: normaliseRate(node.capacity),
    region: node.region ?? null,
    lat: node.lat ?? null,
    lng: node.lng ?? null,
    posX: node.posX ?? null,
    posY: node.posY ?? null,
    caption: node.caption ?? null,
  };
}

export function normaliseNodes(nodes: readonly WireNode[]): readonly NetworkNode[] {
  return nodes.map(normaliseNode);
}

/** One link - its capacity and, since FR-30, its caption. See {@link normaliseNode} for why. */
export function normaliseLink(link: WireLink): NetworkLink {
  return { ...link, capacity: normaliseRate(link.capacity), caption: link.caption ?? null };
}

export function normaliseLinks(links: readonly WireLink[]): readonly NetworkLink[] {
  return links.map(normaliseLink);
}

/**
 * `GET /simulations/{runId}` whole - the run and its suite together.
 *
 * The curves need nothing: `RUN_TIMESERIES` has no nullable column, so every field of
 * every point is always on the wire.
 */
export function normaliseResults(results: WireResults): SimulationResults {
  return {
    ...results,
    run: normaliseRun(results.run),
    metrics: normaliseMetrics(results.metrics ?? []),
  };
}
