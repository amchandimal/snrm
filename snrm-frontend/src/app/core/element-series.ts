import { ElementTimeseries, Id, LinkTimeseries, NodeTimeseries } from './models';

/**
 * Reading `GET /simulations/{runId}/timeseries/elements` - the envelope, not the drawing
 * (FR-18, FR-22).
 *
 * ## Why this is in `core/`
 *
 * Two features read this endpoint now. The editor's `PlaybackStore` fetches it once per report to
 * animate the canvas (FR-18); the results dashboard's `RunResultsStore` fetches it the first time a
 * reader scopes the page to an element (FR-22). They are different lifetimes and different stores,
 * and neither should be the other's dependency - but the two **sentences** below are shown to the
 * same researcher on two screens about one run, and `core/lever-changes.ts` and `core/run-discard.ts`
 * are here for exactly that reason: one fact must not be described two ways. `MANUAL-TEST.md` §1544
 * already asserts the editor's two surfaces agree word for word; this is what keeps a third honest.
 *
 * ## `available: false` is the branch, and it is not "the list is empty"
 *
 * Three different runs answer with empty lists and a client must not treat them alike: a
 * run still `QUEUED` or `RUNNING`, and a run that predates `V9__element_timeseries.sql` or was
 * submitted with `recordElementTimeseries: false`, answer **false**; a run that recorded a series
 * answers **true**, possibly with nothing for an element it has no rows for. None of the three is an
 * error - the endpoint never 404s on the series and never 500s on an old run - so every reader
 * branches on the flag and says so in a sentence rather than drawing zeros.
 */

/**
 * What a run with no per-element series says, verbatim, wherever it is said.
 *
 * Pinned by `features/network-editor/playback.store.spec.ts` and read off the screen by
 * `features/network-editor/MANUAL-TEST.md` §T and §U; the results dashboard now shows the same
 * string where its element charts would be (FR-22).
 */
export const ELEMENT_SERIES_UNAVAILABLE =
  'element detail unavailable for this run (recorded before V9)';

/**
 * What a **failed read** says - a different situation, and only one of the two is anybody's fault.
 *
 * A run recorded before the series existed will never have one; a read that failed may succeed on
 * the next attempt, which is why the dashboard lets a later element click retry it.
 */
export const ELEMENT_SERIES_UNREADABLE = 'element detail could not be read for this run';

/** The one path, so two stores cannot fetch the same series from two spellings of it. */
export function elementSeriesPath(runId: Id): string {
  return `/simulations/${runId}/timeseries/elements`;
}

/** A run's per-element series, keyed by element id - the lookup every reader of it wants. */
export interface ElementSeriesIndex {
  readonly nodes: ReadonlyMap<Id, NodeTimeseries>;
  readonly links: ReadonlyMap<Id, LinkTimeseries>;
}

/**
 * Index a run's element series by id.
 *
 * `features/network-editor/playback-channels.indexElements` builds the same two maps and hangs each
 * element's **normalising maximum** off them. That is a channel decision - a gauge is its own stock
 * against its own peak - rather than a reading of the DTO, so it stays in the editor with the rest of
 * the canvas arithmetic and this stays the plain index. The dashboard draws quantities against a
 * fitted axis and has no use for a maximum over the horizon.
 */
export function indexElementSeries(series: ElementTimeseries): ElementSeriesIndex {
  const nodes = new Map<Id, NodeTimeseries>();
  for (const node of series.nodes) {
    nodes.set(node.nodeId, node);
  }
  const links = new Map<Id, LinkTimeseries>();
  for (const link of series.links) {
    links.set(link.linkId, link);
  }
  return { nodes, links };
}
