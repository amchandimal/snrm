import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';

import {
  DisruptionEvent,
  DisruptionEventRequest,
  Id,
  Network,
  Project,
} from '../../core/models';
import { problemMessage } from '../../core/problem-details';
import { ConfirmDialogComponent } from '../../shared/confirm-dialog/confirm-dialog.component';
import { NetworksStore } from '../projects/networks.store';
import { ProjectsStore } from '../projects/projects.store';
import { EventEditorComponent } from './event-editor/event-editor.component';
import { EventTimelineComponent } from './event-timeline/event-timeline.component';
import { ScenarioBuilderStore } from './scenario-builder.store';
import { ScenariosStore } from './scenarios.store';

/** What the right-hand panel is doing: nothing, editing a bar, or drafting a new one. */
type EditorMode = { readonly kind: 'closed' } | { readonly kind: 'edit'; readonly event: DisruptionEvent } | { readonly kind: 'new' };

/**
 * The scenario builder - the timeline, the network it is laid against, and the editor
 * for one bar.
 *
 * > "Scenario builder - timeline: rows are targeted nodes/links/regions, bars are events
 * > (start/duration), with severity and recovery profile per bar."
 *
 * ## The network picker is the load-bearing control on this page
 *
 * A scenario belongs to the project and names no network - that is what lets one
 * disruption story be replayed against every configuration variant, and what makes the comparison
 * view mean anything. But a timeline cannot be drawn without one: an axis counts periods, a row
 * needs a node's name, and whether a window fits inside the run is a question about a horizon.
 *
 * So the network is chosen here, and choosing a different one re-lays the same events against a
 * different clock. **No event is rewritten by that.** The bars move because the conversion moved;
 * the labels do not, because they show what the researcher typed. Watching the same
 * scenario against a 1-day network and a 2-hour one is the fastest way to see what a period change
 * costs, and it is a read-only act.
 *
 * The picked network also travels with every write, as `?networkId=`, because that is where the
 * server resolves the target and measures the window.
 */
@Component({
  selector: 'app-scenario-builder',
  standalone: true,
  imports: [RouterLink, EventTimelineComponent, EventEditorComponent, ConfirmDialogComponent],
  templateUrl: './scenario-builder.component.html',
  styleUrl: './scenario-builder.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ScenarioBuilderComponent implements OnDestroy {
  private readonly route = inject(ActivatedRoute);
  private readonly projects = inject(ProjectsStore);
  private readonly scenarios = inject(ScenariosStore);
  readonly networks = inject(NetworksStore);
  readonly store = inject(ScenarioBuilderStore);

  readonly projectId = signal<Id | null>(null);
  readonly project = signal<Project | null>(null);
  readonly pageError = signal<string | null>(null);

  readonly editor = signal<EditorMode>({ kind: 'closed' });

  constructor() {
    this.route.paramMap.pipe(takeUntilDestroyed()).subscribe((params) => {
      const projectId = Number(params.get('projectId'));
      const scenarioId = Number(params.get('scenarioId'));
      if (!Number.isFinite(projectId) || projectId <= 0 || !Number.isFinite(scenarioId) || scenarioId <= 0) {
        this.pageError.set('That scenario id is not valid.');
        return;
      }
      this.projectId.set(projectId);
      this.editor.set({ kind: 'closed' });
      this.loadProject(projectId);
      this.store.reset();
      this.store.loadScenario(scenarioId);
      // The project's networks are the picker's options; the first one is selected below, once they
      // have arrived, because a timeline with no clock has nothing to draw.
      this.networks.load(projectId);
    });

    // Selecting the default network reacts to the list arriving rather than being a step in the
    // subscription above: the scenario and the network list are independent requests and either may
    // land first. Once something is selected this stops firing, so the user's pick is never
    // overridden by a later refresh of the list.
    effect(
      () => {
        const first = this.networkOptions()[0];
        if (first && this.store.network() === null) {
          this.store.selectNetwork(first.id);
        }
      },
      { allowSignalWrites: true },
    );
  }

  ngOnDestroy(): void {
    this.store.reset();
  }

  /**
   * The networks the timeline can be laid against - the project's, baseline first.
   *
   * Ordered so the default selection is the network most scenarios are written against, without
   * that being a rule anything enforces.
   */
  readonly networkOptions = computed(() =>
    [...this.networks.networks()].sort(
      (a, b) => Number(b.baseline) - Number(a.baseline) || a.name.localeCompare(b.name),
    ),
  );

  readonly selectedNetworkId = computed(() => this.store.network()?.id ?? null);

  /** A DOM `value` property is a string, and `strictTemplates` does not coerce a number into one. */
  readonly selectedNetworkText = computed(() => this.selectedNetworkId()?.toString() ?? '');

  onNetworkPicked(raw: string): void {
    const networkId = Number(raw);
    if (Number.isFinite(networkId) && networkId > 0 && networkId !== this.selectedNetworkId()) {
      // Closing the editor is deliberate: a half-written event validated against one network should
      // not be submitted against another without the user looking at it again.
      this.editor.set({ kind: 'closed' });
      this.store.selectNetwork(networkId);
    }
  }

  /** `52 periods of 1 day` - the clock every bar on this page was placed against. */
  clockLabel(network: Network): string {
    return `${network.horizonPeriods} periods of ${network.periodLength.value} ${network.periodLength.unit.toLowerCase()}`;
  }

  editorLink(network: Network): (string | number)[] {
    return ['/projects', network.projectId, 'networks', network.id, 'editor'];
  }

  scenarioListLink(): (string | number)[] {
    return ['/projects', this.projectId() ?? 0, 'scenarios'];
  }

  // -------------------------------------------------------------------- the editor

  readonly editingEvent = computed(() => {
    const mode = this.editor();
    return mode.kind === 'edit' ? mode.event : null;
  });

  readonly editorOpen = computed(() => this.editor().kind !== 'closed');

  readonly selectedEventId = computed(() => this.editingEvent()?.id ?? null);

  openNew(): void {
    this.editor.set({ kind: 'new' });
  }

  openEvent(event: DisruptionEvent): void {
    this.editor.set({ kind: 'edit', event });
  }

  closeEditor(): void {
    this.editor.set({ kind: 'closed' });
  }

  /**
   * Saves the draft - a create or a full replacement.
   *
   * The panel stays open on failure. `EVENT_TARGET_INVALID` and `EVENT_EXCEEDS_HORIZON` are both
   * things the user fixes in the fields they are already looking at, and closing would
   * throw the draft away along with the reason.
   *
   * The editor emits **one request per target it was aimed at** (FR-16). Nothing on this
   * page aims it - targeting here is the dropdown, which picks exactly one - so the array is always
   * a single request, and the first is the whole of it.
   */
  saveEvent(requests: readonly DisruptionEventRequest[]): void {
    const mode = this.editor();
    const request = requests[0];
    if (mode.kind === 'closed' || !request) {
      return;
    }
    const write$ =
      mode.kind === 'edit'
        ? this.store.replaceEvent(mode.event.id, request)
        : this.store.createEvent(request);

    write$.subscribe({
      next: (event) => {
        this.editor.set({ kind: 'edit', event });
        this.noteEventCount();
      },
      error: () => undefined,
    });
  }

  // ------------------------------------------------------------------ event delete

  readonly pendingDeleteEvent = signal<DisruptionEvent | null>(null);

  askDeleteEvent(eventId: Id): void {
    const event = this.store.events().find((candidate) => candidate.id === eventId);
    if (event) {
      this.pendingDeleteEvent.set(event);
    }
  }

  cancelDeleteEvent(): void {
    this.pendingDeleteEvent.set(null);
  }

  /**
   * A click, not a typed phrase.
   *
   * The typed confirmation is sized to an action with nothing to fall back to - deleting
   * a network takes every result computed against it. One bar is a few fields a researcher can
   * retype, so the friction here is a plain confirm that names what is going.
   */
  confirmDeleteEvent(): void {
    const event = this.pendingDeleteEvent();
    if (!event) {
      return;
    }
    this.store.deleteEvent(event.id).subscribe({
      next: () => {
        this.pendingDeleteEvent.set(null);
        this.closeEditor();
        this.noteEventCount();
      },
      error: () => this.pendingDeleteEvent.set(null),
    });
  }

  readonly deleteEventDetails: readonly string[] = [
    'Only this event. The scenario, its other events and the network are untouched.',
  ];

  /** Keeps the sidebar count honest without refetching the list under the user's pointer. */
  private noteEventCount(): void {
    const scenario = this.store.scenario();
    if (scenario) {
      this.scenarios.noteEventCount(scenario.id, this.store.events().length);
    }
  }

  private loadProject(projectId: Id): void {
    this.projects.get(projectId).subscribe({
      next: (project) => this.project.set(project),
      error: (failure: unknown) => {
        this.project.set(null);
        this.pageError.set(problemMessage(failure, 'Could not load this project.'));
      },
    });
  }
}
