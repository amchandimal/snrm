import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';

import {
  CanonicalColumn,
  Duration,
  ImportSheetPreview,
  ROUNDING_POLICIES,
  ROUNDING_POLICY_HINT,
  RoundingPolicy,
  TimeUnit,
} from '../../../core/models';
import { convertDuration, formatDuration, formatSpan, horizonSeconds } from '../../../core/time-units';
import { UnitChange, UnitValueComponent } from '../../../shared/unit-value/unit-value.component';
import { DataImportStore } from '../data-import.store';

/**
 * Step 2 of the import wizard: map source columns onto the canonical schema, and confirm
 * what the network will be called and what clock it will run on.
 *
 * ## Why the mapping is per source column, not per canonical column
 *
 * The user is looking at their own file. "What is this column of mine?" is a question they can answer;
 * "which of your columns is `capacity_time_unit`?" makes them search a list of eleven canonical names
 * for each of their own. It is also the only direction in which "ignore this column" is expressible.
 *
 * ## Why the time base is confirmed here
 *
 * When `network_meta` is absent the wizard asks for confirmation of the 1 DAY default. It has
 * to be asked *before* validation, not after, because the period is what every value in a column
 * without a `*_unit` column is interpreted against, and what the resolution checks measure
 * every duration against. Confirming it afterwards would mean validating against a clock the user had
 * not chosen.
 *
 * The span line is the same trap the editor's settings dialog guards: 52 periods of one day
 * is 52 days, and 52 periods of one hour is a little over two days. Changing the period without
 * revisiting the horizon silently redefines how much time the study covers.
 */
@Component({
  selector: 'app-mapping-step',
  standalone: true,
  imports: [UnitValueComponent],
  templateUrl: './mapping-step.component.html',
  styleUrl: './mapping-step.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MappingStepComponent {
  readonly store = inject(DataImportStore);

  readonly policies = ROUNDING_POLICIES;
  readonly policyHints = ROUNDING_POLICY_HINT;

  readonly periodValue = signal(1);
  readonly periodUnit = signal<TimeUnit>('DAY');
  /** Text, so a half-typed horizon survives a re-render. */
  readonly horizonDraft = signal('52');
  readonly policy = signal<RoundingPolicy>('NEAREST');

  /** Which preview the form was seeded from, so re-rendering does not discard what was typed. */
  private readonly seeded = signal(false);

  constructor() {
    effect(
      () => {
        const timeBase = this.store.timeBase();
        if (!timeBase || this.seeded()) {
          return;
        }
        this.seeded.set(true);
        this.periodValue.set(timeBase.periodLength.value);
        this.periodUnit.set(timeBase.periodLength.unit);
        this.horizonDraft.set(String(timeBase.horizonPeriods));
        this.policy.set(timeBase.roundingPolicy);
      },
      { allowSignalWrites: true },
    );

    // The store is the single source of truth for what will be posted, so every edit lands there
    // rather than being collected on submit - which is also what keeps `canValidate` honest.
    effect(
      () => {
        const horizon = this.horizon();
        if (this.periodValid() && horizon !== null) {
          this.store.setTimeBase(this.period(), horizon, this.policy());
        }
      },
      { allowSignalWrites: true },
    );
  }

  readonly sheets = computed(() => this.store.preview()?.sheets ?? []);

  readonly period = computed<Duration>(() => ({
    value: this.periodValue(),
    unit: this.periodUnit(),
  }));

  readonly horizon = computed<number | null>(() => {
    const value = Number(this.horizonDraft().trim());
    return Number.isInteger(value) && value >= 1 ? value : null;
  });

  readonly periodValid = computed(() => this.periodValue() > 0);

  readonly span = computed(() => {
    const horizon = this.horizon();
    return horizon === null || !this.periodValid()
      ? null
      : formatSpan(horizonSeconds(this.period(), horizon));
  });

  /** True when the clock is the default rather than something the file declared. */
  readonly timeBaseAssumed = computed(() => this.store.timeBase()?.declared === false);

  readonly missingRequired = computed(() => this.store.unmappedRequired());

  /** Canonical columns of a sheet - the dropdown options, from the server's own schema. */
  columnsFor(sheet: ImportSheetPreview): readonly CanonicalColumn[] {
    return this.store.preview()?.schema[sheet.sheet] ?? [];
  }

  mapped(sheet: ImportSheetPreview, sourceColumn: string): string {
    return this.store.mappedColumn(sheet.sheet, sourceColumn) ?? '';
  }

  onMap(sheet: ImportSheetPreview, sourceColumn: string, canonical: string): void {
    this.store.mapColumn(sheet.sheet, sourceColumn, canonical === '' ? null : canonical);
  }

  /** True when this canonical column is already fed by a different source column. */
  isTaken(sheet: ImportSheetPreview, canonical: string, bySourceColumn: string): boolean {
    return sheet.columns.some(
      (column) =>
        column.sourceColumn !== bySourceColumn &&
        this.store.mappedColumn(sheet.sheet, column.sourceColumn) === canonical,
    );
  }

  isMissing(sheet: ImportSheetPreview, column: string): boolean {
    return this.store.missingRequiredOf(sheet.sheet).includes(column);
  }

  sampleFor(sheet: ImportSheetPreview, sourceColumn: string): string {
    const index = sheet.headers.indexOf(sourceColumn);
    if (index < 0) {
      return '';
    }
    const values = sheet.sampleRows
      .map((row) => row[index] ?? '')
      .filter((value) => value !== '')
      .slice(0, 3);
    return values.join(', ');
  }

  onPeriodValue(value: number): void {
    this.periodValue.set(value);
  }

  onPeriodUnit(change: UnitChange): void {
    // A period is a duration like any other: a coarser unit restates it, never reinterprets it
    // - 24 hours becomes 1 day, not 24 days.
    const base: Duration =
      change.pendingValue === null
        ? this.period()
        : { value: change.pendingValue, unit: this.periodUnit() };
    const restated = convertDuration(base, change.unit);
    this.periodValue.set(restated.value);
    this.periodUnit.set(restated.unit);
  }

  onHorizon(text: string): void {
    this.horizonDraft.set(text);
  }

  onPolicy(value: string): void {
    const policy = value as RoundingPolicy;
    if (ROUNDING_POLICIES.includes(policy)) {
      this.policy.set(policy);
    }
  }

  periodLabel(): string {
    return formatDuration(this.period());
  }
}
