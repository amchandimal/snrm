import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';

import {
  DISRUPTION_TARGET_TYPES,
  DisruptionEvent,
  DisruptionEventRequest,
  DisruptionTargetType,
  Duration,
  Id,
  Network,
  NetworkLink,
  NetworkNode,
  RECOVERY_PROFILES,
  RECOVERY_PROFILE_HINT,
  RecoveryProfileType,
  Region,
  RegionNodes,
  TimeUnit,
} from '../../../core/models';
import { RegionNodesService } from '../../../core/region-nodes.service';
import { convertDuration, durationInPeriods, formatDuration } from '../../../core/time-units';
import { UnitChange, UnitValueComponent } from '../../../shared/unit-value/unit-value.component';

/**
 * One thing an event can strike, named - the target half of an event without the event.
 *
 * What the network editor hands over when "Add disruption" is pressed with a canvas selection
 * (FR-16). `label` is how it reads in the aimed summary; the other three are what goes on
 * the wire.
 */
export interface AimedTarget {
  readonly targetType: DisruptionTargetType;
  /** Node or link id; null for a REGION target. */
  readonly targetId: Id | null;
  /** The `node.region` tag; null for a NODE or LINK target. */
  readonly targetRegion: string | null;
  /** A node's name, a link's endpoints, a region tag - never an id. */
  readonly label: string;
}

/**
 * The editor for one bar of the timeline, and for a disruption aimed from the canvas
 * (FR-16).
 *
 * > "bars are events (start/duration), with severity and recovery profile per bar."
 *
 * Everything an event is, in one panel: what it strikes, when it starts, how long it lasts, how hard
 * it hits, how it recovers, and how often it happens at all.
 *
 * ## One component, two surfaces
 *
 * The scenario builder's timeline and the network editor's disruptions panel both host this, which
 * is the point of it being a component rather than a page: severity, window, recovery profile and
 * probability have **one** implementation, so the two surfaces cannot drift into meaning different
 * things by the same fields. Nothing
 * here injects a store; everything it needs arrives as an input, and everything it does leaves as an
 * output. Its one service is the region resolution, which is the server's answer either way.
 *
 * ## Aiming
 *
 * With no {@link aimedAt} the panel picks its target from dropdowns - the timeline's way in, where
 * there is nothing selected to aim at. With targets aimed, the picker is replaced by what they are
 * and the draft applies to **all** of them: one window, one severity, one recovery profile, one
 * event per target. That is why {@link saved} emits an array. Selecting three nodes and disrupting
 * them together is a single act, and making the researcher retype the same window three times is how
 * three events that were meant to be identical end up not being.
 *
 * ## The two duration fields are the shared `app-unit-value`
 *
 * The same component the network editor's property panel uses for lead times and processing times.
 * That is not code reuse for its own sake - it is the rule that a duration is
 * entered as a value *and* a unit, and that picking a different unit **restates** the value rather
 * than reinterpreting the number. "4 weeks" becoming "28 days" is correct; becoming "4 days" is the
 * bug the pair exists to prevent. A new event opens on the network's period unit, which is what
 * `UnitPreferencesService` prescribes for durations.
 *
 * ## Why the panel previews periods
 *
 * The event is stored in its declared units and the engine runs in periods, so the panel restates:
 * "4 wk → period 28 of 52". The restatement is a courtesy and a check - an event whose duration
 * rounds to nothing, or whose window runs past the horizon, is visible here before the save is
 * refused. The authoritative answer is still the server's (`EVENT_EXCEEDS_HORIZON`);
 * this is the same arithmetic run early so the user is not surprised.
 *
 * ## Severity and probability
 *
 * Both are [0,1] on the wire and percentages in the UI, because "0.6" and "60% of capacity gone" are
 * the same fact and only one of them is a sentence. Both get a slider *and* a number box: the slider
 * is for exploring, the box for a value someone derived and needs to enter exactly.
 */
@Component({
  selector: 'app-event-editor',
  standalone: true,
  imports: [UnitValueComponent],
  templateUrl: './event-editor.component.html',
  styleUrl: './event-editor.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EventEditorComponent {
  private readonly regionNodes = inject(RegionNodesService);

  /** The event being edited, or null for a new one. */
  readonly event = input<DisruptionEvent | null>(null);
  readonly network = input.required<Network>();
  readonly nodes = input.required<readonly NetworkNode[]>();
  readonly links = input.required<readonly NetworkLink[]>();
  readonly regions = input.required<readonly Region[]>();
  readonly busy = input(false);
  /** The host's last write failure, shown here because this is where the retry is. */
  readonly error = input<string | null>(null);
  /**
   * Targets the draft is aimed at, from a canvas selection (FR-16). Empty for the
   * timeline's dropdown targeting.
   *
   * Only honoured for a **new** event: re-aiming an event that already exists would be retargeting
   * it, which is a different act and belongs in the picker where it is visible.
   */
  readonly aimedAt = input<readonly AimedTarget[]>([]);
  /**
   * Target type a **new** unaimed draft opens on. Null for the NODE default.
   *
   * The network editor's panel opens a REGION draft this way rather than through {@link aimedAt},
   * and the difference is the whole reason both exist: a region is not a canvas element, so there is
   * nothing to aim at - the tag has to be *chosen*, and choosing it is what the picker and its
   * server-side preview are for (FR-16). The picker stays live either way; this only says
   * where it starts.
   */
  readonly openAs = input<DisruptionTargetType | null>(null);

  /** One request per target the draft is aimed at - see the class note on aiming. */
  readonly saved = output<readonly DisruptionEventRequest[]>();
  readonly deleted = output<Id>();
  readonly cancelled = output<void>();
  /**
   * What the server said the currently picked region tag covers, or null once nothing is picked.
   *
   * The panel below the timeline lists the names and stops there. The network editor's host forwards
   * it to the canvas, so the matched nodes light up *as the tag is chosen* rather than after the
   * event is saved (FR-16) - which is the whole difference between picking a region and
   * seeing what picking it means.
   */
  readonly regionPreviewed = output<RegionNodes | null>();

  readonly targetTypes = DISRUPTION_TARGET_TYPES;
  readonly recoveryProfiles = RECOVERY_PROFILES;
  readonly recoveryHints = RECOVERY_PROFILE_HINT;

  // ------------------------------------------------------------------- the draft

  readonly targetType = signal<DisruptionTargetType>(DisruptionTargetType.NODE);
  readonly targetId = signal<Id | null>(null);
  readonly targetRegion = signal<string | null>(null);
  readonly startOffset = signal<Duration>({ value: 0, unit: TimeUnit.DAY });
  readonly duration = signal<Duration>({ value: 1, unit: TimeUnit.DAY });
  readonly severity = signal(1);
  readonly recoveryProfile = signal<RecoveryProfileType>(RecoveryProfileType.STEP);
  readonly probability = signal(1);

  /** What `GET /networks/{id}/region-nodes` said about the currently picked tag. */
  readonly regionPreview = signal<RegionNodes | null>(null);
  readonly regionPreviewError = signal<string | null>(null);

  constructor() {
    // The panel is reused across bars rather than recreated, so the draft is reloaded whenever the
    // input event changes - including to null, which is the "new event" case.
    effect(
      () => {
        const event = this.event();
        const network = this.network();
        this.setRegionPreview(null);
        this.regionPreviewError.set(null);

        if (event) {
          this.targetType.set(event.targetType);
          this.targetId.set(event.targetId);
          this.targetRegion.set(event.targetRegion);
          this.startOffset.set(event.startOffset);
          this.duration.set(event.duration);
          this.severity.set(event.severity);
          this.recoveryProfile.set(event.recoveryProfile);
          this.probability.set(event.probability);
          if (event.targetType === DisruptionTargetType.REGION && event.targetRegion) {
            this.loadRegionPreview(event.targetRegion);
          }
          return;
        }

        // A new event opens on the network's period unit - the one unit the network has an opinion
        // about, and the one a duration entered in converts exactly.
        const unit = network.periodLength.unit;
        // `untracked`, so a later arrival of the node list - or a canvas selection changing - is not
        // a reason to throw away a draft the user is halfway through. The reset belongs to the event
        // changing, not to its options; re-aiming is the effect below, and it touches only the
        // target.
        untracked(() => this.aimDraft(this.aimedAt(), this.openAs()));
        this.startOffset.set({ value: 0, unit });
        this.duration.set({ value: network.periodLength.value, unit });
        this.severity.set(1);
        this.recoveryProfile.set(RecoveryProfileType.STEP);
        this.probability.set(1);
      },
      { allowSignalWrites: true },
    );

    // Re-aiming while drafting rewrites **only** the target. The canvas selection can change under
    // an open panel - clicking a second node is one click - and losing a half-entered window to that
    // would make the two surfaces fight each other.
    effect(
      () => {
        const aimed = this.aimedAt();
        const opening = this.openAs();
        untracked(() => {
          if (this.event() !== null) {
            return;
          }
          this.aimDraft(aimed, opening);
        });
      },
      { allowSignalWrites: true },
    );
  }

  /**
   * Points the draft at the first aimed target, or opens the picker on `opening`.
   *
   * Only the *first* aimed target: the others never enter the draft's own signals, because the draft
   * is one set of fields and {@link save} is what fans it across the rest. What the panel shows in
   * that case is {@link aimedTargets}, not the picker.
   *
   * The unaimed branch routes through {@link onTargetType} rather than setting the signals itself,
   * so "opened on REGION" and "switched to REGION" cannot end up meaning different things.
   *
   * Reads signals directly; every caller is inside `untracked`, since re-aiming reacts to the aim
   * changing and not to the options a picker happens to offer.
   */
  private aimDraft(aimed: readonly AimedTarget[], opening: DisruptionTargetType | null): void {
    const first = aimed[0];
    if (first) {
      this.targetType.set(first.targetType);
      this.targetId.set(first.targetId);
      this.targetRegion.set(first.targetRegion);
      this.setRegionPreview(null);
      if (first.targetType === DisruptionTargetType.REGION && first.targetRegion) {
        this.loadRegionPreview(first.targetRegion);
      }
      return;
    }
    // Nothing aimed - including "the canvas selection was just cleared under an open draft", which
    // falls back to the picker holding whatever it was last aimed at rather than blocking. The draft
    // is still a valid event; only the way its target is stated has changed.
    this.onTargetType(opening ?? DisruptionTargetType.NODE);
  }

  readonly isNew = computed(() => this.event() === null);
  readonly title = computed(() => (this.isNew() ? 'New disruption event' : 'Disruption event'));

  /**
   * The targets in force - non-empty only while drafting something aimed from the canvas.
   *
   * Everything downstream keys off this rather than off `aimedAt()` directly, so "aimed at an event
   * that already exists" is unrepresentable rather than merely unhandled.
   */
  readonly aimedTargets = computed<readonly AimedTarget[]>(() =>
    this.isNew() ? this.aimedAt() : [],
  );

  /** True when the target came from the canvas, so the pickers stand down. */
  readonly isAimed = computed(() => this.aimedTargets().length > 0);

  /** "PLANT-1", or "PLANT-1, DC-1 and 2 more" - the aimed summary's line. */
  readonly aimedLabel = computed(() => {
    const labels = this.aimedTargets().map((target) => target.label);
    if (labels.length <= 3) {
      return labels.join(', ');
    }
    return `${labels.slice(0, 2).join(', ')} and ${labels.length - 2} more`;
  });

  /**
   * String forms for the native `value` bindings.
   *
   * A DOM `value` property is a string, and Angular's `strictTemplates` does not coerce a number
   * into one - the same reason `property-panel.component.html` writes `product.id.toString()`. Kept
   * as computeds rather than done in the template so the conversion is in one place per field.
   */
  readonly targetIdText = computed(() => this.targetId()?.toString() ?? '');
  readonly severityText = computed(() => String(this.severity()));
  readonly probabilityText = computed(() => String(this.probability()));
  readonly severityPercentText = computed(() => String(this.severityPercent()));
  readonly probabilityPercentText = computed(() => String(this.probabilityPercent()));

  // ------------------------------------------------------------------ the target

  readonly needsId = computed(
    () => !this.isAimed() && this.targetType() !== DisruptionTargetType.REGION,
  );
  readonly needsRegion = computed(
    () => !this.isAimed() && this.targetType() === DisruptionTargetType.REGION,
  );

  /** Link options, named by their endpoints - an id is not something to pick from a list. */
  readonly linkOptions = computed(() =>
    this.links().map((link) => ({
      id: link.id,
      label: `${this.nodeName(link.sourceNodeId)} → ${this.nodeName(link.targetNodeId)}`,
    })),
  );

  nodeName(nodeId: Id): string {
    return this.nodes().find((node) => node.id === nodeId)?.name ?? `#${nodeId}`;
  }

  onTargetType(picked: string): void {
    const type = picked as DisruptionTargetType;
    this.targetType.set(type);
    // Each type is addressed by one half of the reference and the server refuses a row carrying
    // both, so switching clears the other (ck_event_target).
    if (type === DisruptionTargetType.REGION) {
      this.targetId.set(null);
      const first = this.targetRegion() ?? this.regions()[0]?.region ?? null;
      this.targetRegion.set(first);
      if (first) {
        this.loadRegionPreview(first);
      }
    } else {
      this.targetRegion.set(null);
      this.setRegionPreview(null);
      // The ids are collected per branch rather than by picking a list first: `nodes() : links()`
      // would be a union of two array types, and calling a method on one of those is a fight with
      // the compiler for no gain.
      const ids: readonly Id[] =
        type === DisruptionTargetType.NODE
          ? this.nodes().map((node) => node.id)
          : this.links().map((link) => link.id);
      if (!ids.some((id) => id === this.targetId())) {
        this.targetId.set(ids[0] ?? null);
      }
    }
  }

  onTargetId(picked: string): void {
    const id = Number(picked);
    this.targetId.set(Number.isFinite(id) ? id : null);
  }

  onRegion(picked: string): void {
    const region = picked.trim();
    this.targetRegion.set(region || null);
    if (region) {
      this.loadRegionPreview(region);
    } else {
      this.setRegionPreview(null);
    }
  }

  /**
   * The one place the preview is written, so the host hears about every change to it.
   *
   * Two consumers with one source: the list of matched names below the field, and - in the network
   * editor - the highlight those same nodes carry on the canvas (FR-16). Setting the signal
   * without telling the host would leave a halo lit around the nodes of a tag the user has since
   * changed, which is worse than no highlight at all.
   */
  private setRegionPreview(preview: RegionNodes | null): void {
    this.regionPreview.set(preview);
    this.regionPreviewed.emit(preview);
  }

  /**
   * Asks the server what the tag resolves to, so "every node in EU-West" is a list of names before
   * the event is saved.
   *
   * Server-side rather than filtered from `nodes()` - which would be a second implementation of the
   * resolution, free to drift from the one a simulation run will use.
   */
  private loadRegionPreview(region: string): void {
    this.regionPreviewError.set(null);
    this.regionNodes.resolve(this.network().id, region).subscribe({
      next: (preview) => this.setRegionPreview(preview),
      error: () => {
        this.setRegionPreview(null);
        this.regionPreviewError.set('Could not resolve that region against this network.');
      },
    });
  }

  // ------------------------------------------------------------------- the timing

  onStartOffsetValue(value: number): void {
    this.startOffset.update((current) => ({ ...current, value }));
  }

  /** A unit pick restates the value; it never reinterprets the number. */
  onStartOffsetUnit(change: UnitChange): void {
    this.startOffset.update((current) =>
      convertDuration({ ...current, value: change.pendingValue ?? current.value }, change.unit),
    );
  }

  onDurationValue(value: number): void {
    this.duration.update((current) => ({ ...current, value }));
  }

  onDurationUnit(change: UnitChange): void {
    this.duration.update((current) =>
      convertDuration({ ...current, value: change.pendingValue ?? current.value }, change.unit),
    );
  }

  /** Where the bar will sit on this network's clock - the same conversion the timeline draws. */
  readonly startPeriod = computed(() => this.toPeriods(this.startOffset()));
  readonly durationPeriods = computed(() => this.toPeriods(this.duration()));
  readonly endPeriod = computed(() => this.startPeriod() + this.durationPeriods());

  readonly horizonPeriods = computed(() => this.network().horizonPeriods);

  /** The restatement under the two duration fields - a courtesy, not the authority. */
  readonly timingHint = computed(
    () =>
      `${formatDuration(this.startOffset())} → ${formatDuration(this.duration())} is periods ` +
      `${this.startPeriod()}–${this.endPeriod()} of ${this.horizonPeriods()}.`,
  );

  /**
   * True when the window would end after the run does - refused by the API.
   *
   * Shown before the save rather than only after it. Same arithmetic as the server's, run early: the
   * server is still the authority, and its message carries the numbers.
   */
  readonly exceedsHorizon = computed(() => this.endPeriod() > this.horizonPeriods());

  /** A window that rounds away entirely - the event would last no time on this clock. */
  readonly windowVanishes = computed(() => this.duration().value > 0 && this.durationPeriods() === 0);

  private toPeriods(duration: Duration): number {
    const network = this.network();
    return durationInPeriods(duration, network.periodLength, network.roundingPolicy);
  }

  // ------------------------------------------------------- severity and probability

  onSeverity(raw: string): void {
    this.severity.set(clamp01(Number(raw)));
  }

  onSeverityPercent(raw: string): void {
    this.severity.set(clamp01(Number(raw) / 100));
  }

  onProbability(raw: string): void {
    this.probability.set(clamp01(Number(raw)));
  }

  onProbabilityPercent(raw: string): void {
    this.probability.set(clamp01(Number(raw) / 100));
  }

  onRecoveryProfile(picked: string): void {
    this.recoveryProfile.set(picked as RecoveryProfileType);
  }

  readonly severityPercent = computed(() => Math.round(this.severity() * 100));
  readonly probabilityPercent = computed(() => Math.round(this.probability() * 100));

  /** What severity means in the model, restated for the panel. */
  readonly severityHint = computed(() => {
    const remaining = Math.round((1 - this.severity()) * 100);
    return this.severity() >= 1
      ? 'Fully offline for the whole window.'
      : `${remaining}% of the target's capacity remains available.`;
  });

  // --------------------------------------------------------------------- submission

  /**
   * What is missing, or null when the draft is sendable.
   *
   * Only what the client can be sure of. Whether a region matches a node and whether the window fits
   * the horizon are the server's calls - the panel warns about both, but does not block on them,
   * because a scenario is project-scoped and the user may be about to switch networks.
   */
  readonly blocker = computed<string | null>(() => {
    if (this.needsId() && this.targetId() === null) {
      return `Pick the ${this.targetType().toLowerCase()} this event strikes.`;
    }
    if (this.needsRegion() && !this.targetRegion()) {
      return 'Name the region this event strikes.';
    }
    if (!(this.duration().value > 0)) {
      return 'An event has to last some time - its duration parameterises the recovery profile.';
    }
    if (this.startOffset().value < 0) {
      return 'A start offset is measured forward from the start of the horizon.';
    }
    return null;
  });

  readonly canSave = computed(() => !this.busy() && this.blocker() === null);

  /** The submit button's wording - plural when one draft is about to become several events. */
  readonly saveLabel = computed(() => {
    if (!this.isNew()) {
      return 'Save event';
    }
    const aimed = this.aimedTargets().length;
    return aimed > 1 ? `Add ${aimed} events` : 'Add event';
  });

  /**
   * Emits the draft - once per target it is aimed at, or once for the picked target.
   *
   * The timing, severity, recovery profile and probability are written **once** and copied across
   * every target. Three nodes disrupted together are three rows that differ only in what they point
   * at, which is precisely what a researcher means by "and these two as well".
   */
  save(): void {
    if (!this.canSave()) {
      return;
    }
    const common = {
      startOffset: this.startOffset(),
      duration: this.duration(),
      severity: this.severity(),
      recoveryProfile: this.recoveryProfile(),
      probability: this.probability(),
    };
    const aimed = this.aimedTargets();
    const targets: readonly AimedTarget[] = aimed.length
      ? aimed
      : [
          {
            targetType: this.targetType(),
            targetId: this.targetId(),
            targetRegion: this.targetRegion(),
            label: '',
          },
        ];

    // Exactly one half of the reference goes on the wire; the other is left off the object entirely
    // rather than sent as null. A row carrying both is refused (`EVENT_TARGET_INVALID`) and
    // `ck_event_target` forbids it in the schema - the two halves are alternatives, not fields.
    this.saved.emit(
      targets.map((target) =>
        target.targetType === DisruptionTargetType.REGION
          ? { ...common, targetType: target.targetType, targetRegion: target.targetRegion }
          : { ...common, targetType: target.targetType, targetId: target.targetId },
      ),
    );
  }

  requestDelete(): void {
    const event = this.event();
    if (event && !this.busy()) {
      this.deleted.emit(event.id);
    }
  }
}

function clamp01(value: number): number {
  if (!Number.isFinite(value)) {
    return 0;
  }
  return Math.min(Math.max(value, 0), 1);
}
