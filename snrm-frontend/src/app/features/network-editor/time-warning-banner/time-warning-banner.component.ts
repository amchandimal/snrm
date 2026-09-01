import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';

import { TimeElementType, TimeFinding } from '../../../core/models';
import { formatDuration, formatNumber } from '../../../core/time-units';
import { NetworkEditorStore } from '../network-editor.store';

/** One banner row: the finding, plus the two numbers it is really about. */
interface WarningRow {
  readonly finding: TimeFinding;
  /** What the user declared - "6 h" - or null where the check is not about one duration. */
  readonly declared: string | null;
  /** What the engine will use - "0 periods" - or null, likewise. */
  readonly converted: string | null;
  /** Signed conversion error, already rounded for display. */
  readonly error: string | null;
  /** False for a `DISRUPTION_EVENT`, which has nothing on this canvas to select. */
  readonly selectable: boolean;
}

/**
 * The resolution-warning banner.
 *
 * > "a dismissible banner lists elements whose durations round badly under the current period;
 * > clicking an entry selects that element on the canvas."
 *
 * Every row states **both** numbers - what was declared and what the engine will use - because the
 * gap between them is the whole finding. "Lead time 6 h → 0 periods" is a sentence a researcher can
 * act on; "rounding warning on link 12" is not.
 *
 * The findings come from `GET /networks/{id}/time-validation` rather than being computed here. The
 * checks run where the data is, over every duration in the network rather than the ones on screen,
 * and the same service answers the import wizard - so the editor and the importer cannot drift into
 * disagreeing about what a bad conversion is.
 *
 * Nothing here refuses anything. The editor is a workspace, where a half-entered lead time is a
 * normal intermediate state; the severity that would refuse it belongs to import.
 */
@Component({
  selector: 'app-time-warning-banner',
  standalone: true,
  templateUrl: './time-warning-banner.component.html',
  styleUrl: './time-warning-banner.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TimeWarningBannerComponent {
  readonly store = inject(NetworkEditorStore);

  readonly visible = this.store.timeWarningsVisible;

  readonly period = computed(() => {
    const report = this.store.timeValidation();
    return report ? formatDuration(report.periodLength) : null;
  });

  readonly policy = computed(() => this.store.timeValidation()?.roundingPolicy ?? null);

  readonly rows = computed<readonly WarningRow[]>(() =>
    this.store.timeFindings().map((finding) => ({
      finding,
      declared: finding.declaredValue ? formatDuration(finding.declaredValue) : null,
      converted:
        finding.convertedPeriods === undefined
          ? null
          : `${formatNumber(finding.convertedPeriods)} period${
              finding.convertedPeriods === 1 ? '' : 's'
            }`,
      error:
        finding.errorPercent === undefined
          ? null
          : `${finding.errorPercent > 0 ? '+' : ''}${formatNumber(Math.round(finding.errorPercent))}%`,
      selectable:
        finding.elementType === TimeElementType.NODE ||
        finding.elementType === TimeElementType.LINK,
    })),
  );

  readonly summary = computed(() => {
    const report = this.store.timeValidation();
    if (!report) {
      return '';
    }
    const parts: string[] = [];
    if (report.errorCount) {
      parts.push(`${report.errorCount} error${report.errorCount === 1 ? '' : 's'}`);
    }
    if (report.warningCount) {
      parts.push(`${report.warningCount} warning${report.warningCount === 1 ? '' : 's'}`);
    }
    return parts.join(', ');
  });

  select(row: WarningRow): void {
    if (row.selectable) {
      this.store.selectFinding(row.finding.elementType, row.finding.elementId);
    }
  }

  dismiss(): void {
    this.store.dismissTimeWarnings();
  }
}
