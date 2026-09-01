import { ChangeDetectionStrategy, Component, computed, effect, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';

import { MetricCode } from '../../../core/models';
import { NODE_TYPE_PROFILES } from '../../network-editor/echelon-rules';
import {
  ElementKey,
  keyLabel,
  keyNoun,
  matchLink,
  matchNode,
  nodeNames,
  tallyMatches,
} from '../element-matching';
import { MetricOption } from '../metric-visibility';
import { NetworkPaneComponent } from '../network-pane/network-pane.component';
import {
  PANE_LIMIT_SENTENCE,
  overCapNote,
  parsePaneIds,
  suiteDefaultSentence,
} from '../pane-grid';
import { ComparisonPane, SideBySideStore, paneHasAnswered } from '../side-by-side.store';

/**
 * The selected networks, side by side - one pane each, sharing a by-name element selection
 * (FR-25).
 *
 * > "The Compare action opens a new browser window showing one pane per selected network - two panes
 * > split the screen left and right, four make a 2 × 2 - each drawing that network with the
 * > read-only miniature of FR-22, above its identity … The view is structural and read-only, and
 * > points at the metric matrix of FR-10 for the performance question it deliberately does not
 * > answer."
 *
 * ## The ids are in the URL, and that is the whole of the window's state
 *
 * `/projects/:projectId/comparison/structure?ids=3,4,5`. Nothing is handed to this route in memory,
 * which is what makes it a real place: it survives a reload, it can be bookmarked, and it can be
 * mailed to a supervisor who will see the same twelve panes. `window.open` from the FR-23 actions menu
 * is then an ordinary navigation to an ordinary address rather than a channel - the dashboard writes
 * the URL and forgets it.
 *
 * That also means the ids are untrusted input in the ordinary sense, and `pane-grid.parsePaneIds` is
 * where every way a link can be stale or hand-edited has an answer: a network deleted since, a token
 * that is not a number, a repeat, or more ids than the window will draw. None of them is an error
 * page. {@link overCapNote} says what was not drawn, and a pane whose network is no longer in the
 * project says so in its own header.
 *
 * ## Why it sits under `/comparison`
 *
 * Because it is the second reading of one question. `/projects/:id/comparison` is the metric matrix
 * of FR-10 - how these configurations *performed* - and this is how they are *shaped*. The two are
 * deliberately distinct, and this one points at the metric matrix for the performance
 * question it deliberately does not answer, so the link to it is on screen, in one
 * line, rather than left to be inferred. Putting the two side by side in the URL is the cheapest way
 * to make the pair visible to somebody who arrives at one of them from a bookmark.
 *
 * ## The selection is the point of the view
 *
 * One `ElementKey` for the window (`SideBySideStore.selection`), resolved per pane by name. The
 * header states the tally - *DC-1 · in 3 of 4* - because with twelve panes the answer to "is it
 * everywhere?" should not require counting green dots, and the panes that lack it each say so in a
 * sentence naming their own configuration. `element-matching.ts` argues the matching rule; the short
 * version is that ids do not survive a fork and `uq_node` makes a name well defined.
 *
 * ## One collapse control, above the grid (FR-27)
 *
 * The cap is twelve now, and a full window of *expanded* panes is a screen to scroll rather than a
 * comparison. So each pane's suite collapses from its own header, and this component holds the one
 * control that moves all of them: label, state and effect all come from
 * `pane-grid.collapseAllControl`, so the button always names the state it will produce and a mixed
 * window always resolves in one direction. The state itself is `SideBySideStore`'s, which is where
 * the note about why it is not remembered across windows lives.
 *
 * ## Which metrics, chosen once for the window (FR-31)
 *
 * A checkbox per metric sits above the grid with **Select all** and **Select none** beside it, and
 * the choice reaches every pane at once - a comparison in which one pane prints a figure another has
 * hidden is not a comparison, which is the same argument that gives the window one element selection
 * rather than twelve. The boxes are built from the codes the panes actually returned and ordered by
 * the rank the panes list their rows by, so the third box governs the third row
 * (`metric-visibility.ts`). Two controls rather than FR-27's one, because a filter's working state
 * is *mixed* and both destinations should be one press away; the module argues it.
 *
 * Nothing is fetched by any of it. A pane holds its whole suite from the moment the window opened,
 * so this is a view over numbers already here - the same thing the collapse is, and stated in the
 * footer for the same reason.
 *
 * ## Read-only, and there is nothing here that is not
 *
 * No edit, no run, no delete, no write of any kind - the store issues four `GET`s per pane and
 * nothing else. The frozen badge on a pane is information about a configuration, not a gate.
 */
@Component({
  selector: 'app-side-by-side',
  standalone: true,
  imports: [RouterLink, NetworkPaneComponent],
  providers: [SideBySideStore],
  templateUrl: './side-by-side.component.html',
  styleUrl: './side-by-side.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SideBySideComponent {
  readonly store = inject(SideBySideStore);

  /**
   * Route inputs, bound by `withComponentInputBinding()` (see `app.config.ts`).
   *
   * Both arrive as strings because a URL carries strings - the parsing is
   * `pane-grid.parsePaneIds`', which is pure and specced for exactly that reason.
   */
  readonly projectId = input<string>();
  readonly ids = input<string>();

  /** What the link asked for, and what of it could not be honoured. */
  readonly asked = computed(() => parsePaneIds(this.ids()));

  /** One sentence covering a capped, repeated or malformed `?ids=`, or null. */
  readonly askNote = computed(() => overCapNote(this.asked()));

  readonly panes = this.store.panes;
  readonly grid = this.store.grid;
  readonly selection = this.store.selection;

  /** The four types, once for the window - each pane's own legend is off (FR-25's small panes). */
  readonly legend = NODE_TYPE_PROFILES;

  /** The cap, in the same words the actions menu states before the window opens. */
  readonly capSentence = PANE_LIMIT_SENTENCE;

  /** What the one collapse control says and does, from the pure rule (FR-27). */
  readonly collapseAll = this.store.collapseAll;

  /** Whether there is anything to collapse - a window of missing networks has no suites. */
  readonly hasCollapsible = computed(() => this.store.collapsibleCount() > 0);

  /** Why this window opened the way it did: the default follows the pane count (FR-27). */
  readonly defaultNote = computed(() => suiteDefaultSentence(this.panes().length));

  /** The metrics no pane prints, handed to every pane as one set (FR-31). */
  readonly hiddenMetrics = this.store.hiddenMetrics;

  /** What *Select all* / *Select none* say and do, and the count beside them. */
  readonly metricControls = this.store.metricControls;

  /**
   * One checkbox per metric, each with the id its `<label>` points at (FR-31).
   *
   * The id is derived from the **position** rather than from the code, because a metric code is an
   * opaque `VARCHAR(40)` the server owns and a code carrying a space or a quote would
   * produce an id no `for` attribute could name - and sanitising two codes could collide them onto
   * one, which is worse than either. The list is short, stable once the suites have landed, and
   * rebuilt whole whenever it changes, so an index is a sound key for a label association.
   */
  readonly metricChoices = computed<readonly (MetricOption & { inputId: string })[]>(() =>
    this.store
      .metricChoices()
      .map((option, index) => ({ ...option, inputId: `sbs-metric-${index}` })),
  );

  /** `DC-1` / `PLANT-1 → DC-1`, or null. */
  readonly selectionLabel = computed(() => {
    const key = this.selection();
    return key === null ? null : keyLabel(key);
  });

  readonly selectionNoun = computed(() => {
    const key = this.selection();
    return key === null ? null : keyNoun(key);
  });

  /**
   * *In 3 of 4* - how many panes carry the selected element.
   *
   * Counted over the panes that have **loaded a structure**, not over every pane on screen: a pane
   * still reading its nodes has no answer yet, and counting it as an absence would make the number
   * flick as the last request lands, which reads as the tally changing its mind. The pure function
   * states the rule; this computes the booleans it is given.
   */
  readonly tally = computed(() => {
    const key = this.selection();
    if (key === null) {
      return null;
    }
    const answered = this.panes().filter(paneHasAnswered);
    return tallyMatches(answered.map((pane) => hasElement(pane, key)));
  });

  /** True where every pane that has answered carries it - the reader's "no structural difference". */
  readonly inEveryPane = computed(() => {
    const tally = this.tally();
    return tally !== null && tally.of > 0 && tally.present === tally.of;
  });

  /** Route back to the metric matrix of FR-10, in this same window. */
  readonly matrixLink = computed(() => ['/projects', this.projectId() ?? '', 'comparison']);

  constructor() {
    // The route is the subject: a reload, a bookmark and an edited `?ids=` all arrive here and all
    // mean "show these networks". `SideBySideStore.open` is idempotent on the same ask, so a
    // re-emitted parameter does not re-read twelve networks and twelve maximum-flow suites - nor reset a
    // collapse the reader has arranged, since `open` sets the FR-27 default only for a new ask.
    effect(
      () => {
        const projectId = Number(this.projectId());
        const asked = this.asked();
        if (Number.isInteger(projectId) && projectId > 0) {
          // Including an ask with no usable ids: that is a subject too, and it has to replace the
          // panes of the previous one rather than leaving them on screen under a new URL.
          this.store.open(projectId, asked);
        }
      },
      { allowSignalWrites: true },
    );
  }

  onPicked(key: ElementKey): void {
    this.store.select(key);
  }

  onCleared(): void {
    this.store.clearSelection();
  }

  /** One pane's own header (FR-27) - the window owns the state, the pane owns the gesture. */
  onSuiteToggled(pane: ComparisonPane): void {
    this.store.toggleSuite(pane.networkId);
  }

  /** The control above the grid: every pane goes where its label says, never half of them. */
  onCollapseAll(): void {
    this.store.applyCollapseAll();
  }

  /** Whether this metric is printed by every pane (FR-31). A metric nobody hid is shown. */
  isMetricShown(code: MetricCode): boolean {
    return this.store.isMetricShown(code);
  }

  /** One checkbox, applied to every pane - nothing is fetched and nothing is cancelled. */
  onMetricToggled(code: MetricCode): void {
    this.store.toggleMetric(code);
  }

  /** *Select all* and *Select none*: the two destinations of the filter, one press each. */
  onAllMetrics(shown: boolean): void {
    this.store.setAllMetrics(shown);
  }

  /** Whether this pane is drawn as a title and a miniature. */
  isCollapsed(pane: ComparisonPane): boolean {
    return this.store.isSuiteCollapsed(pane.networkId);
  }

  /** Panes are keyed by network id - the one thing about a pane that never changes. */
  paneId(_index: number, pane: ComparisonPane): number {
    return pane.networkId;
  }
}

/** Whether one pane carries the selected element, by the shared matching rule. */
function hasElement(pane: ComparisonPane, key: ElementKey): boolean {
  return key.kind === 'node'
    ? matchNode(key, pane.nodes).element !== null
    : matchLink(key, pane.links, nodeNames(pane.nodes)).element !== null;
}
