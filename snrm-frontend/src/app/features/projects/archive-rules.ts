/**
 * The two things the archive says before anything is downloaded or restored (FR-24).
 *
 * Pure - no Angular, no HTTP - and here rather than in a template because **three** screens now say
 * them: the restore card on the project list, the whole-project export card on the dashboard, and
 * the subset-export confirmation of FR-24. Three copies of a sentence is three sentences that will
 * eventually differ, and the one that differed would be the one somebody read.
 *
 * Both are stated **before** the action rather than in the report afterwards, for the same reason
 * `network-selection.ts` puts the frozen half of a set delete above the phrase field: a rule the
 * user learns from the outcome is a rule they learn too late.
 *
 * ## Why exactly these two, and no others
 *
 * They are the two claims a reader cannot check for themselves from a button labelled "Export" or
 * "Restore", and both are about *somebody else's data*:
 *
 * - **It copies.** FR-24's own wording. A researcher exporting three of six networks is entitled to
 *   assume the worst of a control that does not say - and the worst here is that the runs they spent
 *   an afternoon computing left with the file.
 * - **A restore creates a new project.** Never a merge, with the reason, because "restore" is the
 *   one word in this feature that could plausibly mean "put it back where it came from".
 *
 * The lead and the body are separate strings only so a template can bold the first without the
 * second becoming markup; nothing may reword either half.
 */

/** One rule, as a bolded lead and the sentence that argues it. */
export interface ArchiveRule {
  readonly lead: string;
  readonly body: string;
}

/**
 * Exporting copies; it never moves (FR-24).
 *
 * The wording is FR-24's: "It **copies and never moves**: the selected networks, their runs and
 * their results stay in the project that computed them."
 */
export const ARCHIVE_IS_A_COPY: ArchiveRule = {
  lead: 'It copies and never moves.',
  body:
    'The selected networks, their runs and their results stay in the project that computed ' +
    'them - nothing here is changed or removed by archiving.',
};

/**
 * A restore always creates a new project.
 *
 * The wording is the restore card's, unchanged: this is the rule the *file* is subject to, so it is
 * said where the file is produced as well as where it is consumed.
 */
export const RESTORE_CREATES_A_NEW_PROJECT: ArchiveRule = {
  lead: 'Restoring it always creates a new project.',
  body:
    'Nothing existing is read or changed - a merge would have to decide what happens when the ' +
    'archive’s “Baseline v1” meets one of yours, and every answer to that silently overwrites or ' +
    'renames somebody’s results.',
};

/**
 * Every restored run carries its provenance.
 *
 * Read by the restore card alone today. It lives beside the other two because it is the third
 * sentence of the same paragraph, and splitting one paragraph across two files is how the first two
 * would come to be edited without it.
 */
export const RESTORED_RUNS_ARE_MARKED: ArchiveRule = {
  lead: 'Every restored run is marked as imported.',
  body:
    'It is a genuine completed run - it freezes its network and appears in the comparison view - ' +
    'so the mark is the only thing distinguishing a number this installation computed from one it ' +
    'was handed.',
};

/** One rule as a single sentence, for a caller with nowhere to put a bolded lead. */
export function ruleSentence(rule: ArchiveRule): string {
  return `${rule.lead} ${rule.body}`;
}
