import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import {
  IMPORT_SHEETS,
  ImportDiagnostic,
  ImportReport,
  ImportSeverity,
  TimeFinding,
} from '../../../core/models';
import { formatDuration } from '../../../core/time-units';

/** One sheet's worth of diagnostics, for the grouped tables. */
interface DiagnosticGroup {
  readonly sheet: string;
  readonly rows: readonly ImportDiagnostic[];
}

/**
 * One two-stage validation report, drawn.
 *
 * This is the report step's own rendering, lifted out of it unchanged when FR-28 made the step
 * capable of showing **several** - one per file of a batch. It is a component rather than a second
 * copy for the same reason `network-inspector` is one: a second implementation is a second
 * place for the two to drift, and here they would drift in the direction that matters most, since
 * the batch's per-file report is the only account a refused file gets.
 *
 * ## What is shown, and why in this shape
 *
 * Errors and warnings are separated, not just colour-coded, because they answer different questions:
 * every error has to be fixed before anything can be imported, and every warning is a decision the
 * researcher is being invited to make. Within each, findings are grouped by sheet and ordered by line -
 * the order a spreadsheet is edited in - because the point of a per-row report with line numbers
 * is that a user can work down it once with the file open beside them.
 *
 * The resolution findings appear twice on purpose. They are in the row table, addressed to
 * the line that caused them, so a lead time that rounds badly reads like any other cell problem; and
 * they are in their own section with the declared value, the converted periods and the **suggested
 * period**, which is the remedy and belongs next to the arithmetic rather than in a table of rows.
 *
 * ## The verdict is the caller's, and it is projected
 *
 * The sentence at the top of a report - *nothing blocks this import*, *N errors block it*, *created as
 * `Baseline v2`* - is the one thing that genuinely differs between the two callers, because one is
 * about a decision the user is about to take and the other is about something that has already
 * happened to one file of eight. So it arrives through `<ng-content />`, the shape
 * `shared/confirm-dialog` already takes for the same reason. A caller that projects the markup it
 * always projected renders byte-identically to what this component was extracted from.
 */
@Component({
  selector: 'app-import-report-view',
  standalone: true,
  templateUrl: './import-report-view.component.html',
  styleUrl: './import-report-view.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ImportReportViewComponent {
  readonly report = input.required<ImportReport>();

  /** Heading above the summary. Null draws none - a batch's file card carries its own. */
  readonly title = input<string | null>('3. Validation report');

  readonly errors = computed(() => this.group(ImportSeverity.ERROR));
  readonly warnings = computed(() => this.group(ImportSeverity.WARNING));

  /** Rows read per sheet, in schema order rather than in whatever order the map arrived. */
  readonly rowsRead = computed(() => {
    const counts = this.report().rowsRead ?? {};
    return IMPORT_SHEETS.filter((sheet) => sheet in counts).map((sheet) => ({
      sheet,
      rows: counts[sheet],
    }));
  });

  /** The 5.5.4 findings, with their own section. Empty is the normal case. */
  readonly timeFindings = computed<readonly TimeFinding[]>(
    () => this.report().timeValidation?.findings ?? [],
  );

  readonly suggestedPeriod = computed(() => {
    const suggested = this.report().timeValidation?.suggestedPeriod;
    return suggested ? formatDuration(suggested) : null;
  });

  readonly periodLabel = computed(() => {
    const timeBase = this.report().timeBase;
    return timeBase ? formatDuration(timeBase.periodLength) : '';
  });

  duration(finding: TimeFinding): string {
    return finding.declaredValue ? formatDuration(finding.declaredValue) : '-';
  }

  /**
   * The signed rounding error as a percentage - `+33%`, `−100%`.
   *
   * Formatted here rather than with the decimal pipe in the template because the value is legitimately
   * absent for the two checks that are not about one duration's arithmetic (`PERIOD_TOO_FINE`,
   * `EVENT_EXCEEDS_HORIZON`), and a pipe would have to be guarded for that anyway. The sign is always
   * shown: "+140%" and "−100%" are different failures, and a bare number hides which one happened.
   */
  errorPercent(finding: TimeFinding): string {
    const percent = finding.errorPercent;
    if (percent === undefined || percent === null) {
      return '-';
    }
    const rounded = Math.round(percent);
    return `${rounded > 0 ? '+' : ''}${rounded}%`;
  }

  private group(severity: ImportSeverity): readonly DiagnosticGroup[] {
    const diagnostics = this.report().diagnostics ?? [];
    const groups: DiagnosticGroup[] = [];
    // Schema order, then "no sheet" last: a finding about the upload as a whole is context for the rest,
    // and the sheets are listed in the order the file is read and fixed.
    for (const sheet of [...IMPORT_SHEETS, null]) {
      const rows = diagnostics.filter(
        (entry) => entry.severity === severity && (entry.sheet ?? null) === sheet,
      );
      if (rows.length) {
        groups.push({ sheet: sheet ?? 'Upload', rows });
      }
    }
    return groups;
  }
}
