import { LowerCasePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';

import { describeMetric, formatMetricValue } from '../../../core/metric-display';
import { MetricCode, NetworkLink, NetworkNode } from '../../../core/models';
import {
  MiniMapLink,
  MiniMapNode,
  MiniMapSelection,
  WHOLE_NETWORK,
} from '../../simulations/mini-map-layout';
import { NetworkInspectorComponent } from '../../simulations/network-inspector/network-inspector.component';
import {
  ElementKey,
  absenceSentence,
  keyLabel,
  keyOfLink,
  keyOfNode,
  linkLabel,
  matchLink,
  matchNode,
  nodeNames,
} from '../element-matching';
import { ALL_METRICS_HIDDEN } from '../metric-visibility';
import { ComparisonPane, PaneCriticality, paneHasAnswered } from '../side-by-side.store';

/** One row of the pane's topological suite: what it is called, what it reads, what it means. */
interface SuiteRow {
  readonly code: MetricCode;
  readonly label: string;
  readonly formatted: string;
  readonly meaning: string;
}

/** What this pane has to say about the shared selection. */
interface SelectionAnswer {
  /** Which element of this network is lit, if any - what the miniature is told. */
  readonly selection: MiniMapSelection;
  /** True where this network has the selected element. */
  readonly present: boolean;
  /** The sentence shown when it does not, or null. */
  readonly absence: string | null;
}

/** Nothing selected anywhere: the pane draws plain and says nothing about a match. */
const NO_ANSWER: SelectionAnswer = { selection: WHOLE_NETWORK, present: false, absence: null };

/**
 * The default of {@link NetworkPaneComponent.hiddenMetrics} - one shared empty set (FR-31).
 *
 * A constant rather than `new Set()` in the input's default, so an unfiltered pane compares equal to
 * itself between change-detection runs instead of receiving a new identity every time.
 */
const NOTHING_HIDDEN: ReadonlySet<MetricCode> = new Set<MetricCode>();

/**
 * Unique per instance, because a window holds up to twelve of these (FR-25) and the disclosure button's
 * `aria-controls` has to name *this* pane's suite. The same reason `network-actions-menu` counts its
 * own instances now that FR-26 puts one on every row.
 */
let panes = 0;

/**
 * One pane of the side-by-side window - a network's identity, its shape, and its structural numbers
 * (FR-25).
 *
 * > "each drawing that network with the **read-only miniature of FR-22** … above its identity (name,
 * > version, baseline and frozen badges) and its topological suite, so a shape and its structural
 * > numbers are read together."
 *
 * ## The miniature is the dashboard's, imported across features
 *
 * `simulations/network-inspector` draws this pane's picture - the component itself, not a copy of
 * it. This repository already has a rule for a fact two features must agree about
 * (`disruption-overlay.ts → timeline.ts`, and the palette and step geometry this feature's
 * neighbour imports), and FR-25 asks for exactly it in as many words: the same hand-drawn fit of
 * stored coordinates, the same echelon fallback, and Cytoscape out of this bundle for the reason it
 * is out of the dashboard's. A second implementation would be a second place for a node to be drawn
 * at a coordinate the dashboard puts elsewhere, and a second place for somebody to reach for a graph
 * library.
 *
 * What the import needed was a component that draws *a* network rather than *the run's*, and that
 * generalisation was made **in place**: see `network-inspector.component.ts`, where the network, the
 * selection and the tints became inputs and the results dashboard now passes its own. Nothing was
 * lifted to `shared/` - that folder holds `metric-badge`, `ci-value`, `confirm-dialog` and
 * `file-drop`, which are primitives with no opinion about supply networks, and moving the miniature
 * there would have dragged `mini-map-layout.ts` and the editor's palette with it to gain nothing
 * this import does not already give (`mini-map-layout.ts` makes the same argument about
 * `echelon-rules`).
 *
 * This pane passes no element series and no note, so its dots draw plain: there is no run behind a
 * structural comparison, and a tint reading empty-and-fully-available would be a claim about a
 * simulation nobody ran.
 *
 * ## What it says about the shared selection
 *
 * The window holds one {@link ElementKey} and every pane answers it against its own nodes and links
 * (`element-matching.ts`). Where this network has the element, the miniature lights it; where it
 * does not, the pane says so in a sentence naming **this configuration** - "DC-1 is not in Baseline
 * v3" - because that absence is the finding, and a pane that simply failed to highlight anything
 * would look like a pane that had not been clicked.
 *
 * ## The numbers collapse; the picture does not (FR-27)
 *
 * The suite sits behind a disclosure button of this pane's own, and **the miniature never collapses
 * - it is what the window exists to show**. That is why the button is the *suite's* header rather
 * than the pane's: a control in the card header, beside a name and its badges, would read as
 * offering to collapse the pane, and the one thing it will not do is take away the shape. Collapsed,
 * a pane is a title, a miniature and a closed heading; the numbers are one press away.
 *
 * The button is a real `<button>` with `aria-expanded` and an `aria-controls` naming the region it
 * opens - the disclosure semantics `network-actions-menu`'s toggle already uses in this repository,
 * for the same reason: a `<div>` with a click handler is a control a keyboard cannot reach and a
 * screen reader cannot announce the state of. It names *this network* rather than reading
 * "Structural metrics" twelve times over in a row.
 *
 * **Collapsing fetches nothing and cancels nothing.** The region is hidden, not destroyed, and the
 * store's request for the suite was made when the window opened regardless of how this pane looked
 * at the time (`SideBySideStore.toggleSuite`). The state itself belongs to the window and this
 * component holds none of it: {@link suiteCollapsed} is an input and {@link suiteToggled} an output,
 * so the one control above the grid and this header write the same value.
 *
 * ## Which of the numbers, and that is the window's too (FR-31)
 *
 * {@link hiddenMetrics} is the second input of that kind: the checkbox row above the grid chooses
 * which metrics every pane prints, and this pane filters its own two blocks against the one set. It
 * governs both, because both are metrics - the `dl` is the network-scoped rows and the *Most
 * critical nodes* table **is** `NODE_CRITICALITY`, which is per-node and so never appears in the
 * rows beside it. A filter that reached only the first would have left the largest block of figures
 * in every pane outside the control that says it chooses which metrics are shown.
 *
 * Filtering fetches nothing either, for the same reason collapsing does not: `pane.suite` is held
 * whole and this is a view of it. And it defaults to hiding nothing, so a caller that passes no set
 * draws exactly what this component drew before the input existed.
 *
 * ## It is read-only, and there is nothing here to make it otherwise
 *
 * No edit control, no run control, no delete. The frozen badge is *information* - this configuration
 * has been evaluated - and not a gate on anything, because nothing in this window is a
 * thing a freeze could refuse. A disclosure is not an exception: it changes what is on screen and
 * nothing about the configuration.
 */
@Component({
  selector: 'app-network-pane',
  standalone: true,
  imports: [NetworkInspectorComponent, LowerCasePipe],
  templateUrl: './network-pane.component.html',
  styleUrl: './network-pane.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NetworkPaneComponent {
  readonly pane = input.required<ComparisonPane>();

  /** The one selection the window shares, or null. */
  readonly selection = input<ElementKey | null>(null);

  /**
   * Whether this pane's structural suite is closed (FR-27). Expanded is the default it always had.
   *
   * An input rather than a signal of this component's own, because the window's one *Collapse all*
   * and this pane's own header set the same thing, and two owners of one state is two states.
   */
  readonly suiteCollapsed = input(false);

  /**
   * The metric codes this pane must not print (FR-31) - the window's one set, shared by every pane.
   *
   * An input for the same reason {@link suiteCollapsed} is, and defaulting to the empty set for the
   * reason this repository's shared components default their additions: a caller that passes nothing
   * renders byte-identically to what this pane rendered before the checkbox row existed.
   */
  readonly hiddenMetrics = input<ReadonlySet<MetricCode>>(NOTHING_HIDDEN);

  /** An element of this pane was picked, named the way every other pane will look it up. */
  readonly picked = output<ElementKey>();
  /** Empty space in this pane - the window drops the shared selection. */
  readonly cleared = output<void>();
  /** This pane's own header was pressed: open its suite if closed, close it if open (FR-27). */
  readonly suiteToggled = output<void>();

  /** Per instance: up to twelve panes on one page, and a repeated id breaks every `aria-*` at it. */
  private readonly uid = `pane-suite-${++panes}`;
  readonly suiteId = `${this.uid}-region`;
  readonly suiteToggleId = `${this.uid}-toggle`;

  /** `Baseline v3` - the identity a sentence about this configuration names it by. */
  readonly label = computed(() => {
    const network = this.pane().network;
    return network ? `${network.name} v${network.version}` : `Network #${this.pane().networkId}`;
  });

  /** This pane's node names by id - what a link's two ends are resolved through. */
  private readonly names = computed(() => nodeNames(this.pane().nodes));

  /** Where the shared selection stands in *this* network. */
  private readonly answer = computed<SelectionAnswer>(() => {
    const key = this.selection();
    const pane = this.pane();
    // A pane that has not read its network yet has **no answer**, which is not the same as
    // answering *no*: "DC-1 is not in Baseline v3" about a request still in flight is a claimed
    // structural difference that is really a spinner. See `paneHasAnswered`.
    if (key === null || !paneHasAnswered(pane)) {
      return NO_ANSWER;
    }
    if (key.kind === 'node') {
      const match = matchNode<NetworkNode>(key, pane.nodes);
      return match.element
        ? { selection: { kind: 'node', id: match.element.id }, present: true, absence: null }
        : {
            selection: WHOLE_NETWORK,
            present: false,
            absence: absenceSentence(key, this.label(), match, (node) => node.name),
          };
    }
    const match = matchLink<NetworkLink>(key, pane.links, this.names());
    return match.element
      ? { selection: { kind: 'link', id: match.element.id }, present: true, absence: null }
      : {
          selection: WHOLE_NETWORK,
          present: false,
          absence: absenceSentence(key, this.label(), match, (link) =>
            linkLabel(link, this.names()),
          ),
        };
  });

  /** What the miniature is told to light. */
  readonly miniSelection = computed(() => this.answer().selection);

  /** True where this network carries the selected element - the header's tick. */
  readonly present = computed(() => this.answer().present);

  /** "DC-1 is not in Baseline v3." - FR-25's "states plainly where there is none". */
  readonly absence = computed(() => this.answer().absence);

  /** What is lit here, named - so the line reads the same in every pane that has it. */
  readonly selectionLabel = computed(() => {
    const key = this.selection();
    return key === null ? null : keyLabel(key);
  });

  /**
   * The suite, described.
   *
   * Through `core/metric-display.ts`, the same descriptors the dashboard's cards and the metric
   * badges read: twelve panes are twelve readings of one suite, and a table of labels of this pane's own
   * would let one code read two ways on one screen. A code this build has never heard of falls back
   * to its own code and a sentence saying so, because calculators are discovered at runtime.
   *
   * Computed whether or not the suite is on screen, and that is the point: the numbers are held by a
   * collapsed pane, so expanding one is a disclosure rather than a request (FR-27).
   *
   * Filtered by {@link hiddenMetrics} (FR-31), which is a view over the same held rows and costs no
   * request either. The order is the store's - `TOPOLOGICAL_METRIC_CODES` through
   * `metric-visibility.compareMetricCodes`, the comparator the checkbox row is sorted by - so
   * unticking the third box removes the third row.
   */
  readonly suiteRows = computed<readonly SuiteRow[]>(() => {
    const periodLength = this.pane().network?.periodLength;
    const hidden = this.hiddenMetrics();
    return this.pane()
      .suite.filter((metric) => !hidden.has(metric.metricCode))
      .map((metric) => {
        const descriptor = describeMetric(metric.metricCode);
        return {
          code: metric.metricCode,
          label: descriptor.label,
          formatted: formatMetricValue(metric.value, descriptor.format, periodLength),
          meaning: descriptor.meaning,
        };
      });
  });

  /**
   * The *Most critical nodes* table, or nothing where `NODE_CRITICALITY` is unticked (FR-31).
   *
   * The table **is** that metric - per-node, so it never appears among the rows above, which is
   * exactly why it needs saying here rather than being assumed to follow them.
   */
  readonly criticalityRows = computed<readonly PaneCriticality[]>(() =>
    this.hiddenMetrics().has(MetricCode.NODE_CRITICALITY) ? [] : this.pane().criticality,
  );

  /**
   * True where this pane holds numbers and the reader has hidden every one of them (FR-31).
   *
   * Deliberately distinguished from a suite that came back empty: one is a fact about the network,
   * the other a consequence of a control above the grid, and the template prints a different
   * sentence for each ({@link allMetricsHidden}).
   */
  readonly everythingHidden = computed(
    () =>
      this.pane().suite.length > 0 &&
      this.suiteRows().length === 0 &&
      this.criticalityRows().length === 0,
  );

  /** The sentence for that case, from the pure module both surfaces read. */
  readonly allMetricsHidden = ALL_METRICS_HIDDEN;

  /** Per-node criticality as a percentage, matching the editor's metrics panel. */
  percent(value: number): string {
    return `${(value * 100).toFixed(1)}%`;
  }

  onNodePicked(node: MiniMapNode): void {
    this.picked.emit(keyOfNode(node));
  }

  /**
   * A link picked here becomes the pair of endpoint names every other pane will look up.
   *
   * A link whose ends this pane cannot name yields no key, and the click is dropped rather than
   * turned into a selection that matches nothing anywhere while looking exactly like one that does
   * (`keyOfLink`'s note).
   */
  onLinkPicked(link: MiniMapLink): void {
    const key = keyOfLink(link, this.names());
    if (key !== null) {
      this.picked.emit(key);
    }
  }

  /** A criticality row is a second way to reach the same by-name selection. */
  onCriticalityPicked(name: string): void {
    this.picked.emit({ kind: 'node', name });
  }

  onCleared(): void {
    this.cleared.emit();
  }
}
