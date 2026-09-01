import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  input,
  output,
  signal,
} from '@angular/core';

import {
  TIME_UNITS,
  TIME_UNIT_ABBREVIATION,
  TIME_UNIT_LABEL,
  TimeUnit,
} from '../../core/models';

/** What the unit dropdown reports: the unit picked, and any number typed but not yet committed. */
export interface UnitChange {
  readonly unit: TimeUnit;
  /**
   * The number in the box when the dropdown was used, if it parses and differs from what was
   * committed; null when the box was untouched.
   *
   * Carried so the caller can fold "36, and actually make that hours" into **one** edit. Typing a
   * value and then re-uniting it must not cost two undo steps, and must not commit the typed number
   * against the old unit for a moment in between.
   */
  readonly pendingValue: number | null;
}

/**
 * A number paired with the unit it is counted in - the field every duration and rate uses.
 *
 * > Every duration and rate field is a value box paired with a unit dropdown […] Changing a unit
 * > re-displays the value in that unit rather than reinterpreting the number, and the change is a
 * > single undoable command.
 *
 * ## What this component does and does not decide
 *
 * It owns the two controls, the draft text while the user types, and parse-level validation. It does
 * **not** convert: `unitPicked` reports the unit the user chose and the caller restates the value,
 * because only the caller knows what "the value" is across a multi-selection whose elements each
 * hold their own number and their own unit. Conversion itself lives in `core/time-units.ts`.
 *
 * ## Why the commit is on blur, one field at a time
 *
 * Same reason as the rest of the property panel: a bulk PATCH leaves omitted fields alone, so the
 * panel sends only the field the user just left. That also gives undo a natural granularity - "Set
 * lead time on 12 links" is one step back, not twelve.
 *
 * Durations and rates differ only in wording (`hours` versus `per hour`) and in what an empty box
 * means, so one component covers both rather than two that would drift apart.
 */
@Component({
  selector: 'app-unit-value',
  standalone: true,
  templateUrl: './unit-value.component.html',
  styleUrl: './unit-value.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class UnitValueComponent {
  readonly label = input.required<string>();
  /** DOM id for the number box, so the label is really a label. */
  readonly fieldId = input.required<string>();
  /** `duration` reads "12 hours"; `rate` reads "500 per hour". */
  readonly kind = input.required<'duration' | 'rate'>();

  /** The committed number, or null for an empty field - an unconstrained capacity. */
  readonly value = input<number | null>(null);
  readonly unit = input.required<TimeUnit>();

  /** True when the selected elements disagree; the box shows a `-` placeholder rather than one value. */
  readonly mixed = input(false);
  readonly disabled = input(false);
  /** Shown when the box is empty and not mixed, e.g. `unconstrained`. */
  readonly placeholder = input('');

  /**
   * The restatement line under the field: what the engine will make of this value.
   *
   * A courtesy, not a warning - the authoritative account of a conversion that loses something is
   * the resolution banner, which asks the server.
   */
  readonly hint = input<string | null>(null);
  /** A message the caller wants shown, e.g. a rejected server write. Parse errors are ours. */
  readonly error = input<string | null>(null);

  /** Shows the × that clears the field back to unset. Only nullable fields have one. */
  readonly clearable = input(false);
  /** Clearing is a PUT and single-element only, so the caller decides when it is available. */
  readonly clearDisabled = input(false);
  readonly clearTitle = input('Clear this field');

  /** A parsed, non-negative number the user committed with blur or Enter. */
  readonly valueCommitted = output<number>();
  /** A unit the user picked. See {@link UnitChange} for why the pending draft travels with it. */
  readonly unitPicked = output<UnitChange>();
  readonly cleared = output<void>();

  readonly units = TIME_UNITS;
  readonly abbreviations = TIME_UNIT_ABBREVIATION;

  /** Half-typed text, so change detection cannot swallow a keystroke. Dropped once committed. */
  private readonly draft = signal<string | null>(null);
  private readonly parseError = signal<string | null>(null);

  constructor() {
    // A draft belongs to the value it was typed against. When the committed pair changes underneath
    // - the selection moved, or a save came back - the box must show what is now true rather than a
    // number aimed at something else. Typing does not trip this: none of the three inputs move while
    // the user is in the box.
    effect(
      () => {
        this.value();
        this.unit();
        this.mixed();
        this.draft.set(null);
        this.parseError.set(null);
      },
      { allowSignalWrites: true },
    );
  }

  readonly displayValue = computed(() => {
    const draft = this.draft();
    if (draft !== null) {
      return draft;
    }
    if (this.mixed()) {
      return '';
    }
    const value = this.value();
    return value === null ? '' : String(value);
  });

  readonly message = computed(() => this.parseError() ?? this.error());

  readonly boxPlaceholder = computed(() => (this.mixed() ? '-' : this.placeholder()));

  /** `per day` for a rate, `days` for a duration. */
  unitOption(unit: TimeUnit): string {
    const label = TIME_UNIT_LABEL[unit];
    return this.kind() === 'rate' ? `per ${singular(label)}` : label;
  }

  /** MONTH and YEAR are fixed lengths, and the UI has to say so. */
  readonly unitTitle =
    'The unit this value is counted in. A month is always 30 days and a year 365 - fixed lengths, ' +
    'not calendar arithmetic.';

  onInput(text: string): void {
    this.draft.set(text);
  }

  /** Blur or Enter: parse, range-check, and emit only if the number is one the API would accept. */
  commit(): void {
    const draft = this.draft();
    if (draft === null) {
      return;
    }
    const trimmed = draft.trim();
    if (!trimmed) {
      // An empty box is not zero. Where the field has an unset state, the × is the way to reach it;
      // where it has none, zero has to be typed - either way, guessing here would write a number the
      // user did not choose.
      this.parseError.set(
        this.clearable() ? 'Use the × to clear this field.' : 'Enter a number - 0 for none.',
      );
      return;
    }
    const value = Number(trimmed);
    if (!Number.isFinite(value)) {
      this.parseError.set('Enter a number.');
      return;
    }
    if (value < 0) {
      this.parseError.set('Must not be negative.');
      return;
    }
    this.parseError.set(null);
    this.draft.set(null);
    this.valueCommitted.emit(value);
  }

  onUnitChange(picked: string): void {
    const unit = picked as TimeUnit;
    if (!TIME_UNITS.includes(unit) || unit === this.unit()) {
      return;
    }
    const pending = this.pendingValue();
    this.parseError.set(null);
    this.draft.set(null);
    this.unitPicked.emit({ unit, pendingValue: pending });
  }

  clear(): void {
    this.parseError.set(null);
    this.draft.set(null);
    this.cleared.emit();
  }

  /**
   * The typed-but-uncommitted number, if it is usable and actually differs from the committed one.
   *
   * Silently dropped when it does not parse: the unit change is still a legitimate action, and
   * refusing it because of half-typed text in the box would be the more surprising behaviour.
   */
  private pendingValue(): number | null {
    const draft = this.draft();
    if (draft === null) {
      return null;
    }
    const trimmed = draft.trim();
    if (!trimmed) {
      return null;
    }
    const value = Number(trimmed);
    if (!Number.isFinite(value) || value < 0 || value === this.value()) {
      return null;
    }
    return value;
  }
}

/** `days` → `day`, so a rate reads "per day" rather than "per days". */
function singular(label: string): string {
  return label.replace(/^(\w+?)s\b/, '$1');
}
