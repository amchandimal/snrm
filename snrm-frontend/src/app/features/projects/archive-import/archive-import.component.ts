import { ChangeDetectionStrategy, Component, inject, output } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { Id } from '../../../core/models';
import { FileDropComponent } from '../../../shared/file-drop/file-drop.component';
import { ArchiveSeverity } from '../archive-report';
import { ArchiveRestoreStore } from '../archive-restore.store';
import { RESTORED_RUNS_ARE_MARKED, RESTORE_CREATES_A_NEW_PROJECT } from '../archive-rules';

/**
 * Restore a project archive - the import half, on the project **list**.
 *
 * > "the matching import lives on the project list, not on any project, because a restore creates a
 * > new project rather than adding to one"
 *
 * That placement is the whole reason this is a component of its own and not a control on the
 * dashboard: there is no project to put it on until it has run. It is hosted by
 * `project-list.component`, which reloads its list when {@link restored} fires.
 *
 * ## What it says before anything happens
 *
 * A restore is consequential without being destructive - it writes a project, several networks and
 * every run they carry - so the panel states the two rules that surprise people *before* the file is
 * chosen rather than in the report afterwards: it is **never a merge**, and every restored run is
 * **marked as imported**. Both are irreversible in the ordinary sense (undoing a restore means
 * deleting the project), and neither is guessable from a button labelled "Restore".
 *
 * The sentences come from `archive-rules.ts` rather than from this template, because as of FR-24 the
 * "never a merge" rule is also stated on the *export* side - the subset-export confirmation on the
 * project dashboard makes the same claim about the file it is about to write. Two copies of one rule
 * is two rules that will eventually differ, and the one that differed would be the one somebody
 * read.
 *
 * ## What it says afterwards
 *
 * The report is content, not a toast: the findings with their severities, the counts the
 * manifest claimed beside the counts that arrived, and - when `engineMatches` is false - the
 * engine-version warning in the backend's own words, which is the one thing on this screen that can
 * invalidate a comparison. Only then does it offer the restored project, because opening it first
 * would be an invitation to skip the sentence that qualifies everything in it.
 */
@Component({
  selector: 'app-archive-import',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink, FileDropComponent],
  templateUrl: './archive-import.component.html',
  styleUrl: './archive-import.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ArchiveImportComponent {
  readonly store = inject(ArchiveRestoreStore);

  /** Fires with the new project's id, so the list can reload and highlight nothing else. */
  readonly restored = output<Id>();

  /** Optional. Blank means "take the archived name", which the backend suffixes if it is taken. */
  readonly nameControl = new FormControl('', {
    nonNullable: true,
    // 160 mirrors `project.name VARCHAR(160)`, as the create form does.
    validators: [Validators.maxLength(160)],
  });

  /**
   * The two rules, from the one definition (`archive-rules.ts`).
   *
   * The subset-export confirmation of FR-24 reads {@link RESTORE_CREATES_A_NEW_PROJECT} too, which
   * is why it is not spelled out in this template any more.
   */
  readonly rules = [RESTORE_CREATES_A_NEW_PROJECT, RESTORED_RUNS_ARE_MARKED];

  readonly dropHint =
    'or click to choose. One .zip written by “Export project archive” on a project dashboard - ' +
    'bundle.json plus one XML document per network.';

  addFiles(files: readonly File[]): void {
    this.store.addFiles(files);
  }

  async restore(): Promise<void> {
    if (!this.store.canRestore() || this.nameControl.invalid) {
      this.nameControl.markAsTouched();
      return;
    }
    this.store.setName(this.nameControl.value);
    const report = await this.store.restore();
    if (report) {
      this.restored.emit(report.projectId);
    }
  }

  /** Clears the panel back to an empty drop target for a second archive. */
  restoreAnother(): void {
    this.store.reset();
    this.nameControl.reset('');
  }

  /** The route to the project the restore created. */
  projectLink(projectId: Id): (string | number)[] {
    return ['/projects', projectId];
  }

  /**
   * The badge's whole class list for a finding's severity - see `archive-report.ts` on the ranking.
   *
   * Returns `badge` with it rather than leaving that as a static class beside a `[class]` binding:
   * the two do merge, but a reader should not have to know the merge rule to see what is applied.
   */
  severityClass(severity: ArchiveSeverity): string {
    switch (severity) {
      case ArchiveSeverity.CRITICAL:
        return 'badge text-bg-danger';
      case ArchiveSeverity.WARNING:
        return 'badge text-bg-warning';
      default:
        return 'badge text-bg-light border';
    }
  }

  /** The word beside the code, so a badge is readable without knowing the ranking. */
  severityLabel(severity: ArchiveSeverity): string {
    switch (severity) {
      case ArchiveSeverity.CRITICAL:
        return 'Read this first';
      case ArchiveSeverity.WARNING:
        return 'Warning';
      default:
        return 'Note';
    }
  }
}
