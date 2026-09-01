import { WireRun, normaliseRuns } from '../../core/api-nulls';
import { JobStatus, SimulationRun, SimulationStatus, TimeUnit } from '../../core/models';
import { comparisonCandidates, settlesHistory, toRunTile, toRunTiles } from './run-history';

/**
 * `run-history.ts` - what a run-history tile claims about a run (FR-21).
 *
 * What is worth pinning is not the shape of an object but the four readings a tile has to
 * make, each of which is a nullable DTO field read one particular way:
 *
 *  - *when it ran* is the finished timestamp, falling back to the started one, falling back to
 *    "queued" - three states, not a nullable date;
 *  - *which scenario it applied* is `Baseline - no scenario` exactly when `scenarioId` is null, and
 *    comes from `core/run-discard.ts` so the tile and the deletion dialog name one run alike;
 *  - the status is badged **only** where it is not `DONE`;
 *  - the restored mark is `importedAt`, which is the field the wire omits.
 *
 * Plus the two rules that decide what a tile can *do* - load a report, and be deleted - and the one
 * that decides when the list must be re-read, which is what keeps FR-21 free of a polling loop.
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
  horizonPeriods: 30,
  params: null,
  importedAt: null,
  sourceRunId: null,
  unresolvedEventIds: [],
};

function run(overrides: Partial<SimulationRun> = {}): SimulationRun {
  return { ...RUN, ...overrides };
}

describe('run-history', () => {
  describe('when it ran', () => {
    it('reads the finished timestamp on a run that finished', () => {
      expect(toRunTile(run()).when).toEqual({
        kind: 'finished',
        at: '2026-08-07T09:00:11Z',
      });
    });

    it('falls back to the started timestamp while the run is still executing', () => {
      const tile = toRunTile(run({ status: JobStatus.RUNNING, finishedAt: null }));

      expect(tile.when).toEqual({ kind: 'started', at: '2026-08-07T09:00:00Z' });
    });

    it('says queued when the run has neither timestamp', () => {
      // Not a blank: a blank cell where a date belongs reads as missing data rather than as a run
      // that has not begun (FR-21).
      const tile = toRunTile(run({ status: JobStatus.QUEUED, startedAt: null, finishedAt: null }));

      expect(tile.when).toEqual({ kind: 'queued', at: null });
    });

    it('prefers finished over started when a run carries both', () => {
      // FR-21 asks for "the date and time it finished" - the moment the numbers came into
      // existence - and a completed run carries both timestamps.
      expect(toRunTile(run()).when.at).toBe('2026-08-07T09:00:11Z');
    });
  });

  describe('which scenario it applied', () => {
    it('names a baseline run in full, and by its null scenario id alone', () => {
      expect(toRunTile(run({ scenarioId: null, scenarioName: null })).scenario).toBe(
        'Baseline - no scenario',
      );
    });

    it('names the scenario a disruption run applied', () => {
      const tile = toRunTile(run({ scenarioId: 3, scenarioName: 'Plant outage' }));

      expect(tile.scenario).toBe('Plant outage');
    });

    it('falls back to the scenario id when the name is gone', () => {
      // A scenario deleted since the run: the run row keeps the id, and a nameless tile would be
      // indistinguishable from a baseline one - which is the one thing it must never look like.
      const tile = toRunTile(run({ scenarioId: 7, scenarioName: null }));

      expect(tile.scenario).toBe('Scenario #7');
    });
  });

  describe('the status badge', () => {
    it('is absent on a DONE run', () => {
      // The ordinary case must not shout as loudly as the exceptional one: FR-21 asks for a badge
      // "wherever that is not DONE", which is a statement about the exception.
      expect(toRunTile(run({ status: JobStatus.DONE })).badge).toBeNull();
    });

    it('is the status itself on every other state', () => {
      const others: SimulationStatus[] = [
        JobStatus.QUEUED,
        JobStatus.RUNNING,
        JobStatus.FAILED,
        JobStatus.CANCELLED,
      ];

      expect(others.map((status) => toRunTile(run({ status })).badge)).toEqual(others);
    });
  });

  describe('the restored-archive mark', () => {
    it('is null for a run this installation computed', () => {
      expect(toRunTile(run()).restored).toBeNull();
    });

    it('carries the import instant for a restored run', () => {
      const tile = toRunTile(run({ importedAt: '2026-08-01T12:00:00Z', sourceRunId: 4 }));

      expect(tile.restored).toBe('2026-08-01T12:00:00Z');
    });

    it('is not raised on a run the wire answered without the field', () => {
      // The defect `core/api-nulls.ts` exists for, one field along: `importedAt` is omitted on every
      // locally computed run, and `undefined !== null` passes the test that selects restored ones.
      // A tile marked "restored" would claim numbers computed here came from somewhere else.
      const wire: WireRun = { ...RUN, importedAt: undefined, sourceRunId: undefined };

      expect(toRunTiles(normaliseRuns([wire]))[0].restored).toBeNull();
    });
  });

  describe('what a tile can do', () => {
    it('offers a report to load on a DONE run and on nothing else', () => {
      const all: SimulationStatus[] = [
        JobStatus.QUEUED,
        JobStatus.RUNNING,
        JobStatus.DONE,
        JobStatus.FAILED,
        JobStatus.CANCELLED,
      ];

      // The suite and all three series are written in one transaction with the status,
      // so no other status has anything to load - not even a FAILED run's partial work.
      expect(all.filter((status) => toRunTile(run({ status })).loadable)).toEqual([JobStatus.DONE]);
    });

    it('refuses the delete the server would refuse, and allows the three it would not', () => {
      // `RUN_ACTIVE` (409) while a job still owns the run (FR-20) - the editor does not
      // offer a confirmation it already knows will fail, which is `openDiscardPrompt`'s rule.
      expect(toRunTile(run({ status: JobStatus.QUEUED })).deletable).toBeFalse();
      expect(toRunTile(run({ status: JobStatus.RUNNING })).deletable).toBeFalse();
      expect(toRunTile(run({ status: JobStatus.DONE })).deletable).toBeTrue();
      expect(toRunTile(run({ status: JobStatus.FAILED })).deletable).toBeTrue();
      expect(toRunTile(run({ status: JobStatus.CANCELLED })).deletable).toBeTrue();
    });
  });

  describe('toRunTiles', () => {
    it('keeps the server’s order - newest first, and no client re-sort', () => {
      // `findByNetworkIdOrderByIdDesc` answers newest first, and a client sort on `finishedAt`
      // would have to place a QUEUED run, which has no timestamp at all, somewhere arbitrary.
      const runs = [
        run({ id: 14, startedAt: null, finishedAt: null, status: JobStatus.QUEUED }),
        run({ id: 13 }),
        run({ id: 12 }),
      ];

      expect(toRunTiles(runs).map((tile) => tile.runId)).toEqual([14, 13, 12]);
    });

    it('answers an empty list with no tiles, which is an ordinary answer', () => {
      expect(toRunTiles([])).toEqual([]);
    });
  });

  describe('comparisonCandidates', () => {
    const tiles = toRunTiles([
      run({ id: 14, status: JobStatus.RUNNING, finishedAt: null }),
      run({ id: 13, scenarioId: 3, scenarioName: 'Plant outage' }),
      run({ id: 12 }),
      run({ id: 11, status: JobStatus.FAILED }),
    ]);

    it('offers every other DONE run of the history, so a pair outlives its session', () => {
      // The whole of FR-21's last clause: a baseline run from Monday and a disruption run from
      // Tuesday are comparable, and no editing session ever saw both.
      expect(comparisonCandidates(tiles, 12).map((candidate) => candidate.runId)).toEqual([13]);
    });

    it('never offers a run the ?runIds= comparison would refuse', () => {
      // A non-DONE run is answered `RUN_NOT_DONE` (409) rather than seated as an empty column,
      // so offering the RUNNING or FAILED tile would be offering a link that 409s.
      const offered = comparisonCandidates(tiles, 12).map((candidate) => candidate.runId);

      expect(offered).not.toContain(14);
      expect(offered).not.toContain(11);
    });

    it('never offers the loaded run against itself', () => {
      expect(comparisonCandidates(tiles, 13).map((candidate) => candidate.runId)).toEqual([12]);
    });

    it('puts the candidate first and the loaded run second in the query', () => {
      expect(comparisonCandidates(tiles, 12)[0].runIds).toBe('13,12');
    });

    it('offers nothing when no report is loaded', () => {
      expect(comparisonCandidates(tiles, null)).toEqual([]);
    });

    it('carries the candidate’s own scenario label for the link text', () => {
      expect(comparisonCandidates(tiles, 12)[0].scenario).toBe('Plant outage');
    });
  });

  describe('settlesHistory - when the list on screen has gone stale', () => {
    it('fires on the transition into each of the three terminal states', () => {
      expect(settlesHistory(JobStatus.RUNNING, JobStatus.DONE)).toBeTrue();
      expect(settlesHistory(JobStatus.RUNNING, JobStatus.FAILED)).toBeTrue();
      expect(settlesHistory(JobStatus.RUNNING, JobStatus.CANCELLED)).toBeTrue();
    });

    it('fires when the first status seen is already terminal', () => {
      expect(settlesHistory(null, JobStatus.DONE)).toBeTrue();
    });

    it('does not fire while the job is merely progressing', () => {
      // Otherwise the refresh would be a second polling loop wearing the first one's clothes,
      // which is precisely what must not happen, and FR-21 has no need of it.
      expect(settlesHistory(null, JobStatus.QUEUED)).toBeFalse();
      expect(settlesHistory(JobStatus.QUEUED, JobStatus.RUNNING)).toBeFalse();
      expect(settlesHistory(JobStatus.RUNNING, JobStatus.RUNNING)).toBeFalse();
    });

    it('does not fire twice for one settle', () => {
      // The poll re-emits a terminal status while the results are being fetched; one settle is one
      // request.
      expect(settlesHistory(JobStatus.DONE, JobStatus.DONE)).toBeFalse();
    });

    it('does not fire on no status at all', () => {
      expect(settlesHistory(null, null)).toBeFalse();
    });
  });
});
