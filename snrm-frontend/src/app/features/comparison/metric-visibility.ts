import { describeMetric } from '../../core/metric-display';
import { MetricCode, TOPOLOGICAL_METRIC_CODES } from '../../core/models';

/**
 * Which metrics the side-by-side window is showing, and the order the choice is offered in
 * (FR-25, FR-27, FR-31).
 *
 * > "A checkbox per metric above the grid chooses which of the structural suite the panes print,
 * > with **Select all** and **Select none** beside them. Every metric is shown until the reader says
 * > otherwise, and the choice applies to every pane at once - a comparison in which one pane shows a
 * > figure another has hidden is not a comparison."
 *
 * Free of Angular, of HTTP and of the DOM, like `pane-grid.ts` and `element-matching.ts` beside it.
 * Three things are pinned here because more than one surface has to agree about each:
 *
 * - **The order.** {@link compareMetricCodes} is the one comparator, and it is read twice - once to
 *   order the checkboxes and once to order the rows in every pane (`SideBySideStore.networkScoped`).
 *   Two orders would mean the third checkbox governs the fifth row, which is a filter nobody could
 *   use.
 * - **The list itself.** {@link metricOptions} builds the choice from the codes the panes actually
 *   returned rather than from a table written here, because calculators are discovered at runtime
 * and a checkbox for a metric no pane has is a control that does nothing.
 * - **The wording of the two bulk controls**, and of the sentence a pane prints when the reader has
 *   hidden everything - {@link metricVisibilityControls}, {@link ALL_METRICS_HIDDEN}.
 *
 * `metric-visibility.spec.ts` walks every rule below.
 *
 * ## Hidden is what is stored, not shown
 *
 * The window keeps the set of **hidden** codes, and everything else follows from that one choice.
 * A suite arriving after the reader has ticked something - the suites land one pane at a time
 * (`SideBySideStore`), and a build may compute a code this one has never heard of - is therefore
 * **shown**, because nobody decided to hide it. Storing the shown set instead would have made a
 * newly discovered metric invisible by default, which is a filter deciding for the reader that a
 * number they have never seen is one they did not want.
 *
 * It is the same shape and the same argument as `SideBySideStore.collapsedSuites`, which stores the
 * *collapsed* ids because a pane nobody has collapsed is expanded. A hidden code whose metric no
 * pane returns is inert in exactly the same way a collapsed id for a missing pane is.
 *
 * ## Two buttons, where FR-27 has one - and that is deliberate
 *
 * `collapseAllControl` is a single control whose label names the state pressing it will produce,
 * because a window is normally all-collapsed or all-expanded and one press has to take every pane
 * *somewhere*. A metric filter is the opposite: **mixed is its working state** - five of nine ticked
 * is what using it looks like - and from there a reader wants both "show me everything again" and
 * "clear it, I will pick two" at one press each. A single toggling control would have to make one of
 * those a two-press gesture, and it would be choosing against whichever one the reader wanted. So
 * there are two, each disabled when it would do nothing, and each saying why.
 */

/** One metric the reader can show or hide, described the way the panes label it. */
export interface MetricOption {
  readonly code: MetricCode;
  /** `Density`, `Node criticality` - the descriptor's label, or the code itself. */
  readonly label: string;
  /** What the number means, carried onto the checkbox so the choice is an informed one. */
  readonly meaning: string;
}

/** One of the two bulk controls: what it says, whether it would do anything, and why. */
export interface MetricBulkControl {
  readonly label: string;
  /** True where pressing it would change nothing - every metric already shown, or already hidden. */
  readonly disabled: boolean;
  /** The sentence the control carries, in both states. */
  readonly hint: string;
}

/** What the row of checkboxes says about itself, and the two controls beside it (FR-31). */
export interface MetricVisibilityControls {
  /** `Showing 5 of 9 metrics.` - the count, so nobody has to tally ticks by eye. */
  readonly summary: string;
  readonly selectAll: MetricBulkControl;
  readonly selectNone: MetricBulkControl;
}

/**
 * Where a code sorts in the structural suite - the registry's own order.
 *
 * `TOPOLOGICAL_METRIC_CODES` is the backend's `@Order` transcribed, and a code this build has never
 * heard of sorts **last** rather than being dropped, because calculators are discovered at runtime
 * and a metric the client cannot name is still a metric the reader can see a number for.
 *
 * Exported for the prose and for {@link compareMetricCodes}, which is what both sorts actually call.
 */
export function metricRank(code: MetricCode): number {
  const at = TOPOLOGICAL_METRIC_CODES.indexOf(code);
  return at < 0 ? TOPOLOGICAL_METRIC_CODES.length : at;
}

/**
 * The full order: {@link metricRank}, then the code itself where two share a rank.
 *
 * The tie-break exists for the unranked codes and only for them - two calculators this build has
 * never heard of both sort last, and "last" is not an order. Without it the checkbox row would sort
 * them by name while the panes kept whatever order the response arrived in, which is precisely the
 * case where the *n*-th box would stop governing the *n*-th row. So both sorts call this, and the
 * claim is a fact rather than a convention.
 */
export function compareMetricCodes(a: MetricCode, b: MetricCode): number {
  return metricRank(a) - metricRank(b) || a.localeCompare(b);
}

/**
 * The choice to offer, from the codes the panes actually hold (FR-31).
 *
 * Deduplicated, because twelve panes return twelve copies of one suite and the reader chooses once
 * for the window. Sorted by {@link compareMetricCodes}, so the boxes read in the order the numbers
 * do and two codes this build cannot rank sort last **by code** - the only stable order available
 * for names nothing here knows anything about, and the same one the rows take.
 *
 * Built from the response rather than from `TOPOLOGICAL_METRIC_CODES` directly, and the difference
 * shows in both directions: a build whose backend has an extra calculator offers a box for it, and a
 * window whose panes returned six of the nine known codes offers six boxes rather than three that
 * govern nothing.
 */
export function metricOptions(codes: readonly MetricCode[]): readonly MetricOption[] {
  const seen = new Set<MetricCode>();
  const options: MetricOption[] = [];
  for (const code of codes) {
    if (seen.has(code)) {
      continue;
    }
    seen.add(code);
    const descriptor = describeMetric(code);
    options.push({ code, label: descriptor.label, meaning: descriptor.meaning });
  }
  return options.sort((a, b) => compareMetricCodes(a.code, b.code));
}

/**
 * What the checkbox row says about itself, and what the two controls beside it do (FR-31).
 *
 * @param total how many metrics the window is offering a box for
 * @param shown how many of those are ticked
 */
export function metricVisibilityControls(total: number, shown: number): MetricVisibilityControls {
  const offered = whole(total);
  const ticked = Math.min(whole(shown), offered);
  return {
    summary: summarise(offered, ticked),
    selectAll: {
      label: 'Select all',
      disabled: offered === 0 || ticked === offered,
      hint:
        ticked === offered
          ? 'Every metric is already shown.'
          : `Show all ${offered} metrics in every pane again. Nothing is re-read - each pane ` +
            'already holds its whole suite.',
    },
    selectNone: {
      label: 'Select none',
      disabled: offered === 0 || ticked === 0,
      hint:
        ticked === 0
          ? 'Every metric is already hidden.'
          : 'Hide every metric, leaving each pane its title and its shape - the quickest way to ' +
            'start from nothing and tick back the two you are weighing.',
    },
  };
}

function summarise(total: number, shown: number): string {
  if (total === 0) {
    return 'No metrics to choose from yet - the panes are still reading their suites.';
  }
  if (shown === total) {
    return total === 1 ? 'Showing the one metric.' : `Showing all ${total} metrics.`;
  }
  if (shown === 0) {
    return `Showing none of the ${total} metrics.`;
  }
  return `Showing ${shown} of ${total} metrics.`;
}

/**
 * What a pane prints where the reader has hidden every metric it has (FR-31).
 *
 * Not an empty region, and deliberately not the "no structural metrics were returned" sentence
 * beside it: one of those is a fact about the network and the other is the consequence of a control
 * the reader is holding, and a pane that reported the second as the first would be blaming the data
 * for the filter. It names the remedy in the same breath, because the control that did it is above
 * the grid rather than in this pane.
 */
export const ALL_METRICS_HIDDEN =
  'No metrics are selected, so this pane shows its shape and no numbers. Tick one above, or press ' +
  'Select all.';

/** A count as a whole number of things, however it arrived - the guard `pane-grid.ts` also keeps. */
function whole(count: number): number {
  return Number.isFinite(count) ? Math.max(0, Math.floor(count)) : 0;
}
