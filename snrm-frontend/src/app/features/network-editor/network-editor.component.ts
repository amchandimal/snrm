import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  HostListener,
  computed,
  effect,
  inject,
  input,
  numberAttribute,
  signal,
  viewChild,
} from '@angular/core';
import { Router, RouterLink } from '@angular/router';

import { ConfirmDialogComponent } from '../../shared/confirm-dialog/confirm-dialog.component';
import { ExportFormat, NodeType, TimeBaseRequest } from '../../core/models';
// Moved to `core/` when the results dashboard's period cursor took the arrow keys on the same terms
// (FR-22). The test is unchanged - see `core/text-entry.ts` for why it is shared rather than copied.
import { isTextEntry } from '../../core/text-entry';
import { formatDuration } from '../../core/time-units';
import { NetworkExportService } from '../data-import/network-export.service';
import { DisruptionsPanelComponent } from './disruptions-panel/disruptions-panel.component';
import { DisruptionsStore } from './disruptions.store';
import { EditorRunStore } from './editor-run.store';
import { GraphCanvasComponent } from './graph-canvas/graph-canvas.component';
import { RunPanelComponent } from './run-panel/run-panel.component';
import { EditorPersistenceService } from './editor-persistence.service';
import { ForkPromptComponent } from './fork-prompt/fork-prompt.component';
import { MetricsPanelComponent } from './metrics-panel/metrics-panel.component';
import { ForkRequest, NetworkEditorStore } from './network-editor.store';
import { NodePaletteComponent } from './node-palette/node-palette.component';
import { PlaybackBarComponent } from './playback-bar/playback-bar.component';
import { PlaybackStore } from './playback.store';
import { PropertyPanelComponent } from './property-panel/property-panel.component';
import { TimeSettingsDialogComponent } from './time-settings-dialog/time-settings-dialog.component';
import { TimeWarningBannerComponent } from './time-warning-banner/time-warning-banner.component';
import { TopologicalMetricsStore } from './topological-metrics.store';
import { UnitPreferencesService } from './unit-preferences.service';

/** Which modal the editor currently has open. Only one at a time. */
type EditorDialog = 'none' | 'delete' | 'relayout' | 'leave' | 'time';

/**
 * The network editor (`features/network-editor`).
 *
 * The shell: palette on the left, Cytoscape canvas in the middle, property panel on the right, and
 * a toolbar carrying undo/redo, the auto-layout button and the dirty indicator. It owns the
 * keyboard bindings and the dialogs; every piece of state lives in {@link NetworkEditorStore}.
 *
 * Route params arrive as component inputs via `withComponentInputBinding()` (app.config.ts), so this
 * component never touches `ActivatedRoute`.
 *
 * The store and the persistence service are provided **here**, not in the root injector: the undo
 * stack, the selection and the pending writes belong to one editing session and must not outlive
 * it. A stale undo stack naming another network's ids is worse than no undo stack.
 */
@Component({
  selector: 'app-network-editor',
  standalone: true,
  imports: [
    RouterLink,
    ConfirmDialogComponent,
    DisruptionsPanelComponent,
    ForkPromptComponent,
    GraphCanvasComponent,
    MetricsPanelComponent,
    NodePaletteComponent,
    PlaybackBarComponent,
    PropertyPanelComponent,
    RunPanelComponent,
    TimeSettingsDialogComponent,
    TimeWarningBannerComponent,
  ],
  providers: [
    NetworkEditorStore,
    EditorPersistenceService,
    UnitPreferencesService,
    TopologicalMetricsStore,
    DisruptionsStore,
    EditorRunStore,
    // The playback clock of FR-18. Session-scoped like the stores above
    // - a clock position belongs to the session watching it - and provided here so the single
    // `requestAnimationFrame` loop is cancelled with the route. One owner, three readers: the
    // transport bar over the canvas, the canvas channels themselves, and this component's keyboard
    // bindings. The **speed** itself is not session state: it lives in the root
    // `PlaybackPreferencesService`, which the time-settings dialog writes through the same path.
    PlaybackStore,
  ],
  templateUrl: './network-editor.component.html',
  styleUrl: './network-editor.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NetworkEditorComponent {
  readonly projectId = input.required({ transform: numberAttribute });
  readonly networkId = input.required({ transform: numberAttribute });

  readonly store = inject(NetworkEditorStore);
  readonly persistence = inject(EditorPersistenceService);
  readonly metrics = inject(TopologicalMetricsStore);
  readonly disruptions = inject(DisruptionsStore);
  readonly runs = inject(EditorRunStore);
  /** The playback clock of FR-18 - read here for the transport bar and its keyboard bindings. */
  readonly playback = inject(PlaybackStore);
  readonly exports = inject(NetworkExportService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  private readonly canvas = viewChild(GraphCanvasComponent);
  private readonly panel = viewChild(PropertyPanelComponent);

  readonly dialog = signal<EditorDialog>('none');
  readonly deleteDetails = signal<readonly string[]>([]);
  readonly deleteMessage = signal('');
  readonly preparingDelete = signal(false);

  /** Whether the metrics side panel is showing. Closed by default. */
  readonly metricsOpen = signal(false);

  /** Whether the disruptions panel (FR-16) is showing. Closed by default. */
  readonly disruptionsOpen = signal(false);

  /** Whether the run panel (FR-17) is showing. Closed by default. */
  readonly runOpen = signal(false);

  /** Resolves the "you have unsaved edits" dialog raised by the route guard. */
  private leaveResolver: ((leave: boolean) => void) | null = null;

  /** The auto-layout on load must happen once per network, not on every render. */
  private autoLaidOutFor: number | null = null;

  readonly canDelete = computed(
    () => !this.store.readOnly() && this.store.selectionKind() !== 'none',
  );

  /** The clock, on the toolbar button - "1 d × 52". */
  readonly timeBaseLabel = computed(() => {
    const network = this.store.network();
    return network
      ? `${formatDuration(network.periodLength)} × ${network.horizonPeriods}`
      : 'Time base';
  });

  /** Wording of the dirty indicator. */
  readonly saveLabel = computed(() => {
    switch (this.persistence.status()) {
      case 'saving':
        return 'Saving…';
      case 'unsaved':
        return `Unsaved changes (${this.persistence.pending()})`;
      case 'error':
        return 'Save failed';
      default:
        return 'All changes saved';
    }
  });

  constructor() {
    // `allowSignalWrites` because loading sets the store's state signals synchronously; that is the
    // effect's whole purpose here, not an accident of ordering.
    effect(
      () => {
        const networkId = this.networkId();
        if (Number.isFinite(networkId) && networkId > 0) {
          void this.store.load(networkId);
        }
      },
      { allowSignalWrites: true },
    );

    // Auto-layout for imported networks without coordinates. Once, and only when nothing
    // has been placed - a single manual position means the layout already belongs to the user.
    effect(
      () => {
        const networkId = this.networkId();
        const needsLayout = this.store.needsInitialLayout();
        const canvas = this.canvas();
        if (!needsLayout || !canvas || this.autoLaidOutFor === networkId) {
          return;
        }
        this.autoLaidOutFor = networkId;
        void canvas.runAutoLayout();
      },
      { allowSignalWrites: true },
    );

    // The suite is recomputed only while something is reading it (`NODE_CRITICALITY` costs
    // one maximum flow per node, FR-04). "Something" is now three surfaces: the metrics panel being
    // open, the canvas encoding criticality in node size - which keeps it live with the
    // panel closed - and the **network dashboard**, which is the property panel's empty-selection
    // state and shows the structural suite with or without a run (FR-19).
    //
    // The property panel's aside is always mounted, so "the dashboard is on screen" reduces to the
    // selection being empty. The cost is real and is taken deliberately: deselecting on a
    // thousand-node network now schedules the same recompute opening the metrics panel has always
    // scheduled. It is what makes the dashboard's figures worth reading - they refresh as the
    // network is edited and carry the store's stale marker while a recompute is in flight, rather
    // than describing whatever the network looked like the last time somebody opened a panel.
    effect(
      () =>
        this.metrics.setActive(
          this.metricsOpen()
            || this.metrics.sizeByCriticality()
            || this.store.selectionKind() === 'none',
        ),
      { allowSignalWrites: true },
    );

    // The disruptions panel's two reads - the project's scenario list and this network's region tag
    // catalogue - are deferred until it is first opened (FR-16). Unlike the metric suite
    // this never switches back off: once a scenario is selected, its halos stay on the canvas with
    // the panel closed, because they are a property of the network on screen rather than a mode.
    effect(() => this.disruptions.setActive(this.disruptionsOpen()), { allowSignalWrites: true });

    // The run panel's one deferred read - the shared scenario list - waits for its first opening,
    // like the panels above. A running job keeps polling with the panel closed: the poll belongs to
    // the store, and the toolbar button carries the progress so it is never invisible (FR-17).
    effect(() => this.runs.setActive(this.runOpen()), { allowSignalWrites: true });

    this.destroyRef.onDestroy(() => {
      void this.persistence.flush();
      this.persistence.destroy();
    });
  }

  toggleMetrics(): void {
    this.metricsOpen.update((open) => !open);
  }

  toggleDisruptions(): void {
    this.disruptionsOpen.update((open) => !open);
  }

  toggleRun(): void {
    this.runOpen.update((open) => !open);
  }

  // ------------------------------------------------------------------------ toolbar

  onTypePicked(type: NodeType): void {
    this.store.setLastUsedType(type);
  }

  onElementCreated(): void {
    // The drop opens its property panel with the name field focused.
    this.panel()?.focusName();
  }

  fit(): void {
    this.canvas()?.fit();
  }

  /**
   * Auto-layout, guarded when the network already carries manual coordinates.
   *
   * Manual positions always take precedence once set, so overwriting
   * them has to be something the user says yes to rather than something a button does quietly.
   */
  requestAutoLayout(): void {
    if (this.store.readOnly()) {
      this.store.openForkPrompt();
      return;
    }
    if (this.store.hasManualPositions()) {
      this.dialog.set('relayout');
      return;
    }
    void this.canvas()?.runAutoLayout();
  }

  confirmAutoLayout(): void {
    this.dialog.set('none');
    void this.canvas()?.runAutoLayout();
  }

  saveNow(): void {
    void this.persistence.flush();
  }

  // ------------------------------------------------------------------ export

  /**
   * Why an export cannot run right now, or null. Rendered as a banner beside the store's own errors.
   *
   * Separate from {@link NetworkExportService#error}, which reports a download that failed. This one
   * reports an export that was not attempted, which is a different thing to tell the user.
   */
  readonly exportNote = signal<string | null>(null);

  dismissExportNote(): void {
    this.exportNote.set(null);
  }

  /**
   * Downloads this network in one of the formats.
   *
   * **Pending edits are flushed first.** Canvas edits are debounced and PATCHed every 2 s,
   * so exporting straight away would describe the network as it was up to two seconds ago - and the
   * thing most likely to be in that window is a node the user just dragged, which is precisely what
   * the layout-carrying formats exist to preserve. Exporting a stale layout would look like the export
   * silently losing the arrangement.
   *
   * If the flush fails the export is abandoned rather than run on stale data: the toolbar is already
   * showing the save error and its Discard action, and a file that quietly disagrees with the canvas
   * is worse than no file.
   */
  async exportNetwork(format: ExportFormat): Promise<void> {
    const network = this.store.network();
    if (!network) {
      return;
    }
    this.exportNote.set(null);
    this.exports.dismissError();

    await this.persistence.flush();
    if (this.persistence.dirty()) {
      this.exportNote.set(
        'Some edits could not be saved, so an export would not match what is on the canvas. ' +
          'Use “Save now”, or “Discard” to drop them, and export again.',
      );
      return;
    }
    await this.exports.download(network.id, format);
  }

  // -------------------------------------------------------------- time base

  openTimeSettings(): void {
    this.store.dismissActionError();
    this.dialog.set('time');
  }

  /**
   * Save a new time base, or hand the attempt to the fork prompt.
   *
   * A period change on a network with completed runs is refused by the server, and the store turns
   * that into the fork-to-variant prompt. The settings dialog steps aside when that
   * happens, so the researcher is not looking at two modals stacked on each other; on any other
   * failure it stays open with the message, since the input is still there to correct.
   */
  async applyTimeBase(request: TimeBaseRequest): Promise<void> {
    const saved = await this.store.applyTimeBase(request);
    if (saved || this.store.forkPromptOpen()) {
      this.dialog.set('none');
    }
  }

  discardUnsaved(): void {
    this.persistence.discardPending();
    void this.store.load(this.networkId());
  }

  undo(): void {
    void this.store.undo();
  }

  redo(): void {
    void this.store.redo();
  }

  // ------------------------------------------------------------------------- delete

  /** Builds the dependent-data list before showing the confirm. */
  async requestDelete(): Promise<void> {
    if (!this.canDelete() || this.preparingDelete()) {
      if (this.store.readOnly()) {
        this.store.openForkPrompt();
      }
      return;
    }
    this.preparingDelete.set(true);
    try {
      const impact = await this.store.deletionImpact();
      const parts: string[] = [];
      if (impact.nodeCount) {
        parts.push(`${impact.nodeCount} node${impact.nodeCount === 1 ? '' : 's'}`);
      }
      if (impact.linkCount) {
        parts.push(`${impact.linkCount} link${impact.linkCount === 1 ? '' : 's'}`);
      }
      // Ctrl-Z does bring these back, but as re-created rows with new ids - worth saying plainly,
      // because anything that referenced the old ids outside this editor will not follow.
      this.deleteMessage.set(
        `Delete ${parts.join(' and ')}? Undo restores them, but as new records with new ids.`,
      );
      this.deleteDetails.set(impact.details);
      this.dialog.set('delete');
    } finally {
      this.preparingDelete.set(false);
    }
  }

  async confirmDelete(): Promise<void> {
    this.dialog.set('none');
    await this.store.deleteSelection();
  }

  // ------------------------------------------------- discarding the runs (FR-20)

  /**
   * "Unlock…" on the frozen banner, and the fork prompt's third choice - one entrance each into the
   * same typed confirmation.
   *
   * The store does the deciding: it re-reads the network's runs and either opens the dialog or,
   * while one is still executing, raises the sentence that names it. Nothing here needs to know
   * which, because both outcomes are already on screen.
   */
  requestDiscardRuns(): void {
    void this.store.openDiscardPrompt();
  }

  /**
   * The phrase was typed and the action confirmed.
   *
   * The run panel is reset on success and the playback clock stops with it: the report it holds
   * names a run that no longer exists, and a curve drawn from a deleted run is the one thing worse
   * than an empty panel. `PlaybackStore` follows `EditorRunStore.report`, so clearing that clears
   * the canvas channels too - the store cannot do this itself, because the dependency runs the
   * other way (`EditorRunStore` injects it, FR-17).
   */
  async confirmDiscardRuns(): Promise<void> {
    if (await this.store.discardRuns()) {
      this.runs.reset();
    }
  }

  // --------------------------------------------------------------------- fork prompt

  async onForked(request: ForkRequest): Promise<void> {
    const variant = await this.store.fork(request);
    if (!variant) {
      return;
    }
    await this.router.navigate([
      '/projects',
      variant.projectId,
      'networks',
      variant.id,
      'editor',
    ]);
  }

  // ------------------------------------------------------ leaving, blur, and the tab

  /**
   * The route guard's question (edits flush "on blur").
   *
   * Flushes first and only asks if something is still unsent - which, in practice, means the save
   * failed. Leaving then really does lose the edit, so the question is a real one.
   */
  async confirmLeave(): Promise<boolean> {
    await this.persistence.flush();
    if (!this.persistence.dirty()) {
      return true;
    }
    this.dialog.set('leave');
    return new Promise<boolean>((resolve) => {
      this.leaveResolver = resolve;
    });
  }

  resolveLeave(leave: boolean): void {
    this.dialog.set('none');
    const resolver = this.leaveResolver;
    this.leaveResolver = null;
    resolver?.(leave);
  }

  @HostListener('window:blur')
  onWindowBlur(): void {
    void this.persistence.flush();
  }

  @HostListener('document:visibilitychange')
  onVisibilityChange(): void {
    if (document.visibilityState === 'hidden') {
      void this.persistence.flush();
    }
  }

  /**
   * Last line of defence on a reload or a tab close.
   *
   * A flush cannot be awaited here - the browser will not wait - so this only raises the native
   * "leave site?" prompt. Everything else about the design exists so this path is rarely reached.
   */
  @HostListener('window:beforeunload', ['$event'])
  onBeforeUnload(event: BeforeUnloadEvent): void {
    if (this.persistence.dirty()) {
      event.preventDefault();
      event.returnValue = '';
    }
  }

  // ------------------------------------------------------------------------ keyboard

  /** Ctrl-Z / Ctrl-Y, Delete, Escape, Ctrl-S - and the playback transport (FR-18). */
  @HostListener('document:keydown', ['$event'])
  onKeyDown(event: KeyboardEvent): void {
    const modifier = event.ctrlKey || event.metaKey;
    const typing = isTextEntry(event.target);

    if (modifier && event.key.toLowerCase() === 's') {
      // Allowed while typing: it is the one shortcut a user reaches for mid-edit.
      event.preventDefault();
      this.saveNow();
      return;
    }
    if (this.dialog() !== 'none') {
      return;
    }
    if (typing) {
      if (event.key === 'Escape') {
        (event.target as HTMLElement).blur();
      }
      return;
    }

    if (modifier && event.key.toLowerCase() === 'z' && !event.shiftKey) {
      event.preventDefault();
      this.undo();
      return;
    }
    if (modifier && (event.key.toLowerCase() === 'y' || (event.shiftKey && event.key.toLowerCase() === 'z'))) {
      event.preventDefault();
      this.redo();
      return;
    }
    if (event.key === 'Delete' || event.key === 'Backspace') {
      event.preventDefault();
      void this.requestDelete();
      return;
    }
    // The playback transport of FR-18 - the three keys a video transport has, and only while there
    // is a run to play. `typing` above has already excluded every form control, which is what makes
    // the arrow keys safe: on the transport bar's own scrub slider they belong to the slider, and
    // the browser moves it by exactly the period this would have stepped.
    if (this.playback.enabled()) {
      if (event.key === ' ' || event.key === 'Spacebar') {
        // A focused button owns its own space bar; stealing it would toggle twice.
        if (event.target instanceof HTMLButtonElement) {
          return;
        }
        event.preventDefault();
        this.playback.toggle();
        return;
      }
      if (event.key === 'ArrowRight') {
        event.preventDefault();
        this.playback.stepForward();
        return;
      }
      if (event.key === 'ArrowLeft') {
        event.preventDefault();
        this.playback.stepBack();
        return;
      }
    }
    if (event.key === 'Escape') {
      // A half-drawn link first: abandoning the gesture is what the key most obviously means while
      // a rubber band is following the pointer. Only once there is nothing to abandon does Escape
      // fall through to clearing the selection.
      if (this.canvas()?.cancelDraw()) {
        event.preventDefault();
        return;
      }
      this.store.select([], []);
    }
  }
}
