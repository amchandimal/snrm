import { JobStatus, SimulationRun, SimulationStatus, TimeUnit } from './models';
import {
  activeRuns,
  activeRunsBlocker,
  describeRun,
  discardNetworkRunsConfirm,
  discardRunConfirm,
  lockingRuns,
  restoredRuns,
} from './run-discard';

/**
 * `run-discard.ts` - the wording and the gate of the FR-20 confirmation.
 *
 * What is worth pinning here is not string equality but the four claims the dialog makes, each of
 * which is a promise about what the server will do:
 *
 *  - it does not offer a discard the server would refuse (`RUN_ACTIVE`);
 *  - it counts what is going, and says so;
 *  - it raises a *separate* warning for a restored archive result;
 *  - it promises an unlock only when a run in the set actually holds the freeze.
 */

const RUN: SimulationRun = {
  id: 12,
  networkId: 1,
  networkName: 'Baseline',
  scenarioId: null,
  scenarioName: null,
  status: JobStatus.DONE,
  startedAt: '2026-08-07T09:00:00Z',
  finishedAt: '2026-08-07T09:00:11Z',
  periodLength: { value: 1, unit: TimeUnit.DAY },
  horizonPeriods: 10,
  params: {
    replications: 1,
    seed: 20260803,
    horizonPeriods: 10,
    demandNoiseCv: 0,
    timingJitterPeriods: 0,
    includeRandomFailures: true,
    baselineSuppressesFailures: false,
    safetyStockPriority: 0.1,
    unmetDemandPenalty: null,
    quantum: 1000,
    engineVersion: 'test',
  },
  importedAt: null,
  sourceRunId: null,
  unresolvedEventIds: [],
};

function run(overrides: Partial<SimulationRun> = {}): SimulationRun {
  return { ...RUN, ...overrides };
}

const NETWORK = { name: 'Baseline', version: 2 };

/** Every line of the confirmation as one searchable string. */
function text(confirm: { message: string; details: readonly string[] }): string {
  return [confirm.message, ...confirm.details].join('\n');
}

describe('run-discard', () => {
  describe('activeRuns / activeRunsBlocker', () => {
    it('treats exactly the non-terminal statuses as active, like the backend does', () => {
      const all: SimulationStatus[] = [
        JobStatus.QUEUED,
        JobStatus.RUNNING,
        JobStatus.DONE,
        JobStatus.FAILED,
        JobStatus.CANCELLED,
      ];
      const runs = all.map((status, index) => run({ id: index, status }));

      expect(activeRuns(runs).map((r) => r.status)).toEqual([
        JobStatus.QUEUED,
        JobStatus.RUNNING,
      ]);
    });

    it('is null when nothing is executing, so the confirm may open', () => {
      expect(activeRunsBlocker([run(), run({ id: 13, status: JobStatus.CANCELLED })])).toBeNull();
    });

    it('names the run in the way and says nothing will be deleted', () => {
      // Coalesced so the assertions below are string matchers rather than nullable ones; the
      // null case is the test above.
      const blocker = activeRunsBlocker([run(), run({ id: 13, status: JobStatus.RUNNING })]) ?? '';

      expect(blocker).toContain('run #13');
      expect(blocker).toContain('RUNNING');
      // The whole reason the server refuses whole rather than skipping: a half-discard would take
      // the finished results and leave the freeze in place.
      expect(blocker).toContain('refused whole');
      expect(blocker).toContain('Cancel it');
    });
  });

  describe('lockingRuns', () => {
    it('counts QUEUED, RUNNING and DONE - and not FAILED or CANCELLED', () => {
      expect(
        lockingRuns([
          run({ id: 1, status: JobStatus.QUEUED }),
          run({ id: 2, status: JobStatus.RUNNING }),
          run({ id: 3, status: JobStatus.DONE }),
          run({ id: 4, status: JobStatus.FAILED }),
          run({ id: 5, status: JobStatus.CANCELLED }),
        ]).map((r) => r.id),
      ).toEqual([1, 2, 3]);
    });
  });

  describe('describeRun', () => {
    it('labels a run with no scenario as the baseline run of FR-17', () => {
      expect(describeRun(run())).toBe('Baseline (run #12, DONE)');
    });

    it('uses the scenario name when there is one', () => {
      expect(describeRun(run({ scenarioId: 3, scenarioName: 'Plant outage' }))).toBe(
        'Plant outage (run #12, DONE)',
      );
    });

    it('marks a restored run, because that is what the extra warning is keyed on', () => {
      expect(describeRun(run({ importedAt: '2026-08-04T11:02:41Z' }))).toBe(
        'Baseline (run #12, DONE, restored)',
      );
    });
  });

  describe('discardNetworkRunsConfirm', () => {
    it('asks for the network name AND version - the FR-15 objection answered, not inherited', () => {
      const confirm = discardNetworkRunsConfirm(NETWORK, [run()]);

      // FR-15 types the *project* name because a network shares its name with every variant of it.
      // Name plus version is unique within the project, so it identifies the configuration whose
      // results are going - and it is what the toolbar and the fork prompt already show.
      expect(confirm.requiredPhrase).toBe('Baseline v2');
      expect(confirm.phraseLabel).toBe('network name and version');
    });

    it('counts the runs and lists them', () => {
      const confirm = discardNetworkRunsConfirm(NETWORK, [
        run({ id: 12 }),
        run({ id: 13, scenarioId: 3, scenarioName: 'Plant outage' }),
      ]);

      expect(confirm.message).toContain('all 2 simulation runs');
      expect(text(confirm)).toContain('Baseline (run #12, DONE)');
      expect(text(confirm)).toContain('Plant outage (run #13, DONE)');
    });

    it('summarises the tail rather than listing forty runs', () => {
      const many = Array.from({ length: 9 }, (_, index) => run({ id: index + 1 }));
      const listed = text(discardNetworkRunsConfirm(NETWORK, many));

      expect(listed).toContain('run #6');
      expect(listed).toContain('and 3 more');
      expect(listed).not.toContain('run #7');
    });

    it('promises the unlock when a locking run is going', () => {
      const confirm = discardNetworkRunsConfirm(NETWORK, [run()]);

      expect(text(confirm)).toContain('becomes editable again');
      expect(text(confirm)).toContain('the freeze is derived from');
    });

    it('promises no unlock when nothing in the set was freezing anything', () => {
      // A network whose only runs failed was never frozen. Saying "becomes editable again" here
      // would claim an effect the deletion does not have.
      const confirm = discardNetworkRunsConfirm(NETWORK, [
        run({ status: JobStatus.FAILED }),
        run({ id: 13, status: JobStatus.CANCELLED }),
      ]);

      expect(text(confirm)).not.toContain('becomes editable again');
      expect(text(confirm)).toContain('holds nothing');
    });

    it('warns separately about a restored archive result', () => {
      const confirm = discardNetworkRunsConfirm(NETWORK, [
        run({ id: 12 }),
        run({ id: 13, importedAt: '2026-08-04T11:02:41Z', sourceRunId: 42 }),
      ]);
      const warning = confirm.details.find((line) => line.startsWith('⚠')) ?? '';

      // A line of its own, not a clause buried in the count: destroying it destroys the only copy
      // this installation holds.
      expect(warning).not.toBe('');
      expect(warning).toContain('One of these is a restored archive result');
      expect(warning).toContain('2026-08-04');
      expect(warning).toContain('only copy');
      // And the honest limit of the claim: the archive file is untouched.
      expect(warning).toContain('archive file itself is unaffected');
    });

    it('raises no restored warning when every run was computed here', () => {
      const confirm = discardNetworkRunsConfirm(NETWORK, [run(), run({ id: 13 })]);

      expect(confirm.details.some((line) => line.startsWith('⚠'))).toBe(false);
    });

    it('says so plainly when there is nothing to discard', () => {
      const confirm = discardNetworkRunsConfirm(NETWORK, []);

      expect(confirm.message).toContain('no simulation runs');
      expect(confirm.details).toEqual([]);
    });

    it('always states that the structural metrics survive', () => {
      // They carry run_id = NULL and belong to the network, so a discard that appeared to take them
      // would be describing something the server does not do.
      expect(text(discardNetworkRunsConfirm(NETWORK, [run()]))).toContain('structural metrics');
    });
  });

  describe('discardRunConfirm', () => {
    it('asks for the run id, which is unique outright and on screen', () => {
      const confirm = discardRunConfirm(run());

      expect(confirm.requiredPhrase).toBe('run 12');
      expect(confirm.phraseLabel).toBe('run id');
    });

    it('does not promise an unlock it cannot know about', () => {
      // Whether other runs still hold the network is the server's answer, not this screen's.
      expect(text(discardRunConfirm(run()))).toContain('If other runs remain, it stays frozen');
    });

    it('carries the restored warning in the single-run form too', () => {
      const confirm = discardRunConfirm(run({ importedAt: '2026-08-04T11:02:41Z' }));

      expect(confirm.details.some((line) => line.startsWith('⚠'))).toBe(true);
    });

    it('names the three time series that go with the metric suite', () => {
      expect(text(discardRunConfirm(run()))).toContain('all three of its time series');
    });
  });
});
