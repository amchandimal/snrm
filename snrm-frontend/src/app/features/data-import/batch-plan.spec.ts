import {
  BatchRole,
  BatchStatus,
  DEFAULT_ROLES,
  NOT_ATTEMPTED_REASON,
  ProjectBaseline,
  RoleAssignment,
  attemptable,
  baselineFlagNote,
  batchRows,
  batchSummary,
  importPlan,
  resolveBaselineIndex,
} from './batch-plan';

/**
 * The roles a batch assigns and the order it imports in - several workbooks in one pass (FR-28).
 *
 * Four things are pinned here because each of them is a way FR-28 could be subtly wrong while every
 * screen still rendered: **the default is variant**, **the baseline goes first**, **the project's
 * baseline flag and the batch's edge base are two different things**, and **a partial batch reads as
 * a partial batch**. The last two are the subtle ones, and both are checked here as behaviour
 * rather than as wording.
 */
describe('batch-plan', () => {
  describe('batchRows', () => {
    it('names every file after itself and defaults every non-baseline file to variant', () => {
      // The default is variant, because a folder of workbooks from one study is usually one
      // configuration and its alternatives, and the reader who disagrees is one click from saying so.
      const rows = batchRows(files('Baseline.xlsx', 'Dual source.xlsx', 'Extra DC.xlsx'), DEFAULT_ROLES);

      expect(rows.map((row) => row.name)).toEqual(['Baseline', 'Dual source', 'Extra DC']);
      expect(rows.map((row) => row.role)).toEqual([
        BatchRole.BASELINE,
        BatchRole.VARIANT,
        BatchRole.VARIANT,
      ]);
    });

    it('moves the baseline to whichever file was chosen, leaving exactly one', () => {
      const rows = batchRows(files('a.xlsx', 'b.xlsx', 'c.xlsx'), roles(2));

      expect(rows.filter((row) => row.role === BatchRole.BASELINE).length).toBe(1);
      expect(rows[2].role).toBe(BatchRole.BASELINE);
      expect(rows[0].role).toBe(BatchRole.VARIANT);
    });

    it('marks the files the user ticked as independent and no others', () => {
      const rows = batchRows(files('a.xlsx', 'b.xlsx', 'c.xlsx'), roles(0, [2]));

      expect(rows.map((row) => row.role)).toEqual([
        BatchRole.BASELINE,
        BatchRole.VARIANT,
        BatchRole.INDEPENDENT,
      ]);
    });

    it('carries a null name for a file that strips to nothing, and never makes it the baseline', () => {
      const rows = batchRows(files('.xlsx', 'Baseline.xlsx'), DEFAULT_ROLES);

      expect(rows[0].name).toBeNull();
      expect(rows[0].role).not.toBe(BatchRole.BASELINE);
      expect(rows[1].role).toBe(BatchRole.BASELINE);
    });
  });

  describe('resolveBaselineIndex', () => {
    it('honours a choice that can be named', () => {
      expect(resolveBaselineIndex(['a', 'b', 'c'], 1)).toBe(1);
    });

    it('falls back to the first nameable file when the choice cannot be named', () => {
      expect(resolveBaselineIndex([null, 'b', 'c'], 0)).toBe(1);
    });

    it('falls back when the choice is off the end of a list that has since changed', () => {
      expect(resolveBaselineIndex(['a', 'b'], 7)).toBe(0);
      expect(resolveBaselineIndex(['a', 'b'], -1)).toBe(0);
      expect(resolveBaselineIndex(['a', 'b'], Number.NaN)).toBe(0);
    });

    it('answers −1 when nothing in the batch can be named at all', () => {
      expect(resolveBaselineIndex([null, null], 0)).toBe(-1);
    });
  });

  describe('importPlan', () => {
    it('puts the baseline first and keeps the rest in the order the files were chosen', () => {
      // The baseline goes first, because a variant edge cannot name a base that does not
      // yet exist.
      const plan = importPlan(
        batchRows(files('a.xlsx', 'b.xlsx', 'c.xlsx'), roles(2)),
        ProjectBaseline.NONE,
      );

      expect(plan.steps.map((step) => step.name)).toEqual(['c', 'a', 'b']);
      expect(plan.steps[0].role).toBe(BatchRole.BASELINE);
    });

    it('asks for a base network id on the variants and on nothing else', () => {
      const plan = importPlan(
        batchRows(files('a.xlsx', 'b.xlsx', 'c.xlsx'), roles(0, [2])),
        ProjectBaseline.NONE,
      );

      expect(plan.steps.map((step) => step.needsBase)).toEqual([false, true, false]);
      expect(plan.variants).toBe(1);
      expect(plan.independents).toBe(1);
    });

    it('sets the project’s baseline flag on the baseline file when the project has none', () => {
      const plan = importPlan(batchRows(files('a.xlsx', 'b.xlsx'), DEFAULT_ROLES), ProjectBaseline.NONE);

      expect(plan.setsBaselineFlag).toBeTrue();
      expect(plan.steps.map((step) => step.setsBaselineFlag)).toEqual([true, false]);
    });

    it('leaves the flag alone when the project already has a baseline, and still records the edges', () => {
      // The distinction that matters, as arithmetic: the *flag* does not move, and every
      // variant still points at the chosen file. Conflating the two is what makes a batch fail at
      // its first request with BASELINE_ALREADY_SET and take every variant behind it down.
      const plan = importPlan(batchRows(files('a.xlsx', 'b.xlsx'), DEFAULT_ROLES), ProjectBaseline.PRESENT);

      expect(plan.setsBaselineFlag).toBeFalse();
      expect(plan.steps.every((step) => !step.setsBaselineFlag)).toBeTrue();
      expect(plan.steps[1].needsBase).toBeTrue();
      expect(plan.baselineName).toBe('a');
    });

    it('treats an unreadable project like one that already has a baseline', () => {
      // Not knowing is not a reason to guess: an unset flag costs a checkbox later, a wrongly set one
      // costs the whole batch.
      const plan = importPlan(batchRows(files('a.xlsx', 'b.xlsx'), DEFAULT_ROLES), ProjectBaseline.UNKNOWN);

      expect(plan.setsBaselineFlag).toBeFalse();
      expect(plan.steps[1].needsBase).toBeTrue();
    });

    it('holds back a file that cannot be named without holding back the rest', () => {
      const plan = importPlan(
        batchRows(files('Baseline.xlsx', '.xlsx', 'Variant.xlsx'), DEFAULT_ROLES),
        ProjectBaseline.NONE,
      );

      expect(plan.steps.map((step) => step.name)).toEqual(['Baseline', 'Variant']);
      expect(plan.unnamed.map((row) => row.fileName)).toEqual(['.xlsx']);
    });

    it('plans a batch in which nothing can be named as no requests at all', () => {
      const plan = importPlan(batchRows(files('.xlsx', '.xlsm'), DEFAULT_ROLES), ProjectBaseline.NONE);

      expect(plan.steps).toEqual([]);
      expect(plan.unnamed.length).toBe(2);
      expect(plan.baselineName).toBeNull();
      expect(plan.setsBaselineFlag).toBeFalse();
    });
  });

  describe('attemptable', () => {
    it('sends the baseline and the independents whatever else happened', () => {
      const plan = importPlan(
        batchRows(files('a.xlsx', 'b.xlsx'), roles(0, [1])),
        ProjectBaseline.NONE,
      );

      expect(attemptable(plan.steps[0], null)).toBeTrue();
      expect(attemptable(plan.steps[1], null)).toBeTrue();
    });

    it('does not send a variant whose baseline was never created', () => {
      // The one way a file's failure reaches another file, and the reason "not attempted" is a
      // status of its own rather than a refusal.
      const plan = importPlan(batchRows(files('a.xlsx', 'b.xlsx'), DEFAULT_ROLES), ProjectBaseline.NONE);

      expect(attemptable(plan.steps[1], null)).toBeFalse();
      expect(attemptable(plan.steps[1], 12)).toBeTrue();
    });

    it('says why, rather than importing the variant as an independent network', () => {
      expect(NOT_ATTEMPTED_REASON).toContain('needs its base network’s id');
      expect(NOT_ATTEMPTED_REASON).toContain('independent network');
    });
  });

  describe('baselineFlagNote', () => {
    it('promises the flag only when the project has none', () => {
      expect(baselineFlagNote(ProjectBaseline.NONE, null).message).toContain('no baseline yet');
      expect(baselineFlagNote(ProjectBaseline.NONE, null).tone).toBe('info');
    });

    it('names the incumbent and says the flag does not move', () => {
      const note = baselineFlagNote(ProjectBaseline.PRESENT, 'Baseline v1');
      expect(note.message).toContain('Baseline v1');
      expect(note.message).toContain('does not move');
      expect(note.message).toContain('variant edges');
    });

    it('degrades to a warning that leaves the flag alone and says the edges are unaffected', () => {
      const note = baselineFlagNote(ProjectBaseline.UNKNOWN, null);
      expect(note.tone).toBe('warning');
      expect(note.message).toContain('unaffected');
    });
  });

  describe('batchSummary', () => {
    it('reads as a success only when every file created a network', () => {
      const summary = batchSummary([BatchStatus.CREATED, BatchStatus.CREATED]);
      expect(summary.verdict).toBe('CREATED_ALL');
      expect(summary.tone).toBe('success');
      expect(summary.created).toBe(2);
    });

    it('reads as a failure only when nothing was created', () => {
      const summary = batchSummary([BatchStatus.REFUSED, BatchStatus.NOT_ATTEMPTED]);
      expect(summary.verdict).toBe('CREATED_NONE');
      expect(summary.tone).toBe('danger');
      expect(summary.message).toContain('Nothing in the project changed');
    });

    it('reads a partial batch as a partial batch - neither of the other two', () => {
      // A batch that created some networks and refused others says so at the top rather
      // than reading as either a success or a failure. Green would hide the file to fix; red would
      // suggest re-importing the seven that worked.
      const summary = batchSummary([
        BatchStatus.CREATED,
        BatchStatus.CREATED,
        BatchStatus.REFUSED,
        BatchStatus.NOT_ATTEMPTED,
      ]);

      expect(summary.verdict).toBe('PARTIAL');
      expect(summary.tone).toBe('warning');
      expect(summary.title).toContain('2 of 4');
      expect(summary.message).toContain('neither a success nor a failure');
    });

    it('names both kinds of shortfall separately, because they have different remedies', () => {
      const summary = batchSummary([
        BatchStatus.CREATED,
        BatchStatus.REFUSED,
        BatchStatus.NOT_ATTEMPTED,
      ]);
      expect(summary.message).toContain('1 file was refused');
      expect(summary.message).toContain('1 was not attempted');
    });

    it('reports the eight-workbook case as seven imported and one named', () => {
      const statuses = Array.from({ length: 8 }, (_unused, index) =>
        index === 5 ? BatchStatus.REFUSED : BatchStatus.CREATED,
      );
      const summary = batchSummary(statuses);

      expect(summary.verdict).toBe('PARTIAL');
      expect(summary.created).toBe(7);
      expect(summary.refused).toBe(1);
    });

    it('says it is still running while anything is pending, whatever has settled so far', () => {
      const summary = batchSummary([BatchStatus.CREATED, BatchStatus.PENDING, BatchStatus.PENDING]);
      expect(summary.verdict).toBe('RUNNING');
      expect(summary.title).toContain('1 of 3');
    });

    it('answers an empty batch without claiming it succeeded', () => {
      expect(batchSummary([]).verdict).toBe('CREATED_NONE');
    });
  });
});

/** `File` objects with the given names - the batch only ever reads the name. */
function files(...names: string[]): File[] {
  return names.map((name) => new File([''], name));
}

/** A role assignment: which index is the baseline, and which are independent. */
function roles(baselineIndex: number, independent: readonly number[] = []): RoleAssignment {
  return { baselineIndex, independent: new Set(independent) };
}
