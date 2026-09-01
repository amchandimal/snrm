import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import {
  DisruptionEvent,
  DisruptionEventRequest,
  DisruptionTargetType,
  Id,
  NetworkLink,
  RegionNodes,
} from '../../../core/models';
import { ConfirmDialogComponent } from '../../../shared/confirm-dialog/confirm-dialog.component';
import {
  AimedTarget,
  EventEditorComponent,
} from '../../scenario-builder/event-editor/event-editor.component';
import { TimelineBar, TimelineRow } from '../../scenario-builder/timeline';
import { eventLine } from '../disruption-overlay';
import { DisruptionsStore } from '../disruptions.store';
import { NetworkEditorStore } from '../network-editor.store';

/** What the panel is showing: the scenario's events, or the editor for one draft. */
type PanelMode =
  | { readonly kind: 'list' }
  /** A new event. `openAs` non-null means the picker opens there instead of on the selection. */
  | { readonly kind: 'new'; readonly openAs: DisruptionTargetType | null }
  | { readonly kind: 'edit'; readonly event: DisruptionEvent };

/**
 * The disruptions panel (FR-16).
 *
 * > "A disruptions panel beside the property and metrics panels selects or creates a scenario in the
 * > project … and lists that scenario's events which resolve against *this* network."
 *
 * ## What it is for
 *
 * An event's target is a node, a link, or a region resolved to the nodes carrying its tag - every one
 * of which is a thing on the canvas. Picking it from a dropdown on another screen is aiming blind.
 * So the target comes from the **selection**: select, press *Add disruption*, and the event editor
 * opens already pointed at it. The timeline in the scenario builder remains the right surface for
 * *when* - a Gantt chart is what a sequence of windows looks like - and this is the right surface for
 * *what*.
 *
 * ## Three things carry it
 *
 * **The event editor is the scenario builder's own component**, not a second one. Severity, window,
 * recovery profile and probability have one implementation, so the two surfaces cannot drift into
 * meaning different things by the same fields. Everything this component adds is aiming and listing.
 *
 * **A region is authored here rather than aimed**, because a region is not a canvas element: there
 * is nothing to select. The tag is chosen in the editor, `GET /networks/{id}/region-nodes` says what
 * it covers, and those nodes light up on the canvas while the choice is being made - which is how a
 * REGION event stops being an abstraction. The resolution is the server's; a client-side filter over
 * `node.region` would be a second implementation of it.
 *
 * **Nothing here edits the network.** A scenario is a different aggregate, so the
 * panel is fully live on a network a simulation run has frozen, and says so rather than leaving the
 * researcher to discover it. That case is not an edge case: a frozen network is exactly one that has
 * already been evaluated, which is when a new question about it is most likely to be worth asking.
 */
@Component({
  selector: 'app-disruptions-panel',
  standalone: true,
  imports: [RouterLink, ConfirmDialogComponent, EventEditorComponent],
  templateUrl: './disruptions-panel.component.html',
  styleUrl: './disruptions-panel.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DisruptionsPanelComponent {
  readonly store = inject(DisruptionsStore);
  readonly editor = inject(NetworkEditorStore);

  readonly mode = signal<PanelMode>({ kind: 'list' });
  /** True while the "new scenario" name field is showing. */
  readonly naming = signal(false);
  readonly draftName = signal('');
  readonly pendingDelete = signal<DisruptionEvent | null>(null);

  readonly scenarios = computed(() => this.store.scenarios.scenarios());
  readonly selectedScenarioText = computed(() => this.store.scenario()?.id.toString() ?? '');

  readonly editorOpen = computed(() => this.mode().kind !== 'list');
  readonly editingEvent = computed(() => {
    const mode = this.mode();
    return mode.kind === 'edit' ? mode.event : null;
  });

  /**
   * The canvas selection as event targets.
   *
   * Live rather than snapshotted when the editor opens: clicking a second node while drafting
   * re-aims the draft, and the window and severity already typed survive it. `EventEditorComponent`
   * is what makes that safe - its re-aiming effect touches the target and nothing else.
   */
  readonly aimedTargets = computed<readonly AimedTarget[]>(() => [
    ...this.editor.selectedNodes().map((node) => ({
      targetType: DisruptionTargetType.NODE,
      targetId: node.id,
      targetRegion: null,
      label: node.name,
    })),
    ...this.editor.selectedLinks().map((link) => ({
      targetType: DisruptionTargetType.LINK,
      targetId: link.id,
      targetRegion: null,
      label: this.linkLabel(link),
    })),
  ]);

  /** What reaches the editor: the live selection for an aimed draft, nothing for a region one. */
  readonly editorAimedAt = computed<readonly AimedTarget[]>(() => {
    const mode = this.mode();
    return mode.kind === 'new' && mode.openAs === null ? this.aimedTargets() : [];
  });

  readonly openAs = computed<DisruptionTargetType | null>(() => {
    const mode = this.mode();
    return mode.kind === 'new' ? mode.openAs : null;
  });

  readonly canAim = computed(() => this.aimedTargets().length > 0);

  /** Why "Add disruption" is unavailable, or null. Shown as the button's title. */
  readonly aimBlocker = computed<string | null>(() => {
    if (!this.store.hasScenario()) {
      return 'Pick or create a scenario first - an event has to live in one.';
    }
    if (!this.canAim()) {
      return 'Select a node or a link on the canvas to aim a disruption at it.';
    }
    return null;
  });

  /** "Add disruption", or "Add disruption to 3 elements" when the selection is wider. */
  readonly addLabel = computed(() => {
    const aimed = this.aimedTargets().length;
    return aimed > 1 ? `Add disruption to ${aimed}` : 'Add disruption';
  });

  /** The full Gantt view of the same scenario - this panel lists, the timeline arranges. */
  readonly timelineLink = computed<(string | number)[] | null>(() => {
    const network = this.editor.network();
    const scenario = this.store.scenario();
    return network && scenario
      ? ['/projects', network.projectId, 'scenarios', scenario.id]
      : null;
  });

  // ------------------------------------------------------------------------ scenarios

  onScenarioPicked(raw: string): void {
    this.closeEditor();
    const id = Number(raw);
    void this.store.selectScenario(Number.isFinite(id) && id > 0 ? id : null);
  }

  startNaming(): void {
    this.draftName.set('');
    this.naming.set(true);
  }

  cancelNaming(): void {
    this.naming.set(false);
  }

  onDraftName(value: string): void {
    this.draftName.set(value);
  }

  async createScenario(): Promise<void> {
    const name = this.draftName().trim();
    if (!name) {
      return;
    }
    const created = await this.store.createScenario(name);
    if (created) {
      this.naming.set(false);
      this.draftName.set('');
    }
  }

  // --------------------------------------------------------------------------- editing

  /** Opens a draft aimed at whatever is selected on the canvas. */
  openAimed(): void {
    if (this.aimBlocker() === null) {
      this.mode.set({ kind: 'new', openAs: null });
    }
  }

  /** Opens a draft on the region picker - nothing on the canvas to aim at. */
  openRegion(): void {
    if (this.store.hasScenario()) {
      this.mode.set({ kind: 'new', openAs: DisruptionTargetType.REGION });
    }
  }

  /** Clicking a listed event: reveal what it strikes, then open it. */
  openEvent(row: TimelineRow, bar: TimelineBar): void {
    this.reveal(row);
    this.mode.set({ kind: 'edit', event: bar.event });
  }

  closeEditor(): void {
    this.mode.set({ kind: 'list' });
    this.store.setDraftRegionNodes([]);
  }

  /**
   * Saves the draft - a create, possibly several, or a full replacement.
   *
   * The panel stays open on failure, like the timeline's: `EVENT_TARGET_INVALID` and
   * `EVENT_EXCEEDS_HORIZON` are both things the user fixes in the fields they are already looking
   * at, and closing would throw the draft away along with the reason.
   */
  async save(requests: readonly DisruptionEventRequest[]): Promise<void> {
    const mode = this.mode();
    const first = requests[0];
    if (mode.kind === 'list' || !first) {
      return;
    }
    const saved =
      mode.kind === 'edit'
        ? await this.store.replaceEvent(mode.event.id, first)
        : await this.store.createEvents(requests);
    if (saved) {
      this.closeEditor();
    }
  }

  /** The editor's region preview, forwarded to the canvas as a highlight. */
  onRegionPreviewed(preview: RegionNodes | null): void {
    this.store.setDraftRegionNodes(preview?.nodes.map((node) => node.id) ?? []);
  }

  // ---------------------------------------------------------------------------- delete

  askDelete(eventId: Id): void {
    const event = this.store.events().find((candidate) => candidate.id === eventId);
    if (event) {
      this.pendingDelete.set(event);
    }
  }

  cancelDelete(): void {
    this.pendingDelete.set(null);
  }

  /**
   * A click, not a typed phrase.
   *
   * The typed confirmation is sized to an action with nothing to fall back to. One event
   * is a few fields a researcher can retype, and - unlike a canvas delete - nothing on the network
   * goes with it.
   */
  async confirmDelete(): Promise<void> {
    const event = this.pendingDelete();
    if (!event) {
      return;
    }
    const deleted = await this.store.deleteEvent(event.id);
    this.pendingDelete.set(null);
    if (deleted) {
      this.closeEditor();
    }
  }

  readonly deleteDetails: readonly string[] = [
    'Only this event. The scenario, its other events and the network are untouched.',
  ];

  readonly deleteMessage = computed(() => {
    const event = this.pendingDelete();
    if (!event) {
      return '';
    }
    return `Delete the ${event.targetType.toLowerCase()} event starting after ${event.startOffset.value} ${event.startOffset.unit.toLowerCase()}?`;
  });

  // --------------------------------------------------------------------------- reading

  /** The hover/row line for one event - the same phrasing the canvas badge carries. */
  line(bar: TimelineBar): string {
    return eventLine(
      bar.event,
      bar,
      bar.event.targetType === DisruptionTargetType.REGION ? bar.event.targetRegion : null,
    );
  }

  /**
   * Selects on the canvas what a listed row strikes (the same gesture the criticality table
   * and the resolution banner use).
   *
   * A region row selects every node the **server** said the tag covers, which is why the store holds
   * that answer rather than the panel filtering `node.region` for it.
   */
  reveal(row: TimelineRow): void {
    const target = row.target;
    if (target.targetType === DisruptionTargetType.REGION) {
      this.editor.select(this.store.regionMembers().get(target.targetRegion ?? '') ?? [], []);
      return;
    }
    if (target.targetId === null) {
      return;
    }
    if (target.targetType === DisruptionTargetType.NODE) {
      this.editor.select([target.targetId], []);
    } else {
      this.editor.select([], [target.targetId]);
    }
  }

  private linkLabel(link: NetworkLink): string {
    const nodes = this.editor.nodes();
    const source = nodes.get(link.sourceNodeId)?.name ?? `#${link.sourceNodeId}`;
    const target = nodes.get(link.targetNodeId)?.name ?? `#${link.targetNodeId}`;
    return `${source} → ${target}`;
  }
}
