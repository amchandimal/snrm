import {
  WireLink,
  WireMetric,
  WireNode,
  WireResults,
  WireRun,
  normaliseLink,
  normaliseMetric,
  normaliseNodes,
  normaliseResults,
  normaliseRun,
  normaliseRuns,
} from './api-nulls';
import {
  Duration,
  JobStatus,
  MetricCode,
  MetricScope,
  SimulationParams,
  TimeUnit,
} from './models';
import { restoredRuns } from './run-discard';

/**
 * What the wire omits, and what every reader downstream is entitled to assume.
 *
 * The fixtures here are **written the way the API writes them** - nullable fields left out, never
 * spelled `null`. That is the whole point of the file: `spring.jackson.default-property-inclusion=non_null`
 * means a null field is absent, `undefined` fails every `=== null` test and passes every `!== null`
 * one, and the app is full of both. A spec that spelled the nulls out would agree with the models
 * and disagree with the browser, which is the state this module was written to end.
 */

const ONE_DAY: Duration = { value: 1, unit: TimeUnit.DAY };

const PARAMS: SimulationParams = {
  replications: 100,
  seed: 42,
  horizonPeriods: 30,
  demandNoiseCv: 0,
  timingJitterPeriods: 0,
  includeRandomFailures: true,
  baselineSuppressesFailures: false,
  safetyStockPriority: 0.1,
  unmetDemandPenalty: null,
  quantum: 1000,
  engineVersion: '1.0.0',
};

/** Run 57 of the report that found this - a `DONE` baseline run, exactly as it arrived. */
const BASELINE_RUN: WireRun = {
  id: 57,
  networkId: 18,
  networkName: 'Playback 4-chain',
  status: JobStatus.DONE,
  startedAt: '2026-08-06T23:47:36.470710Z',
  finishedAt: '2026-08-06T23:47:36.490228Z',
  periodLength: ONE_DAY,
  horizonPeriods: 30,
  unresolvedEventIds: [],
};

describe('api-nulls - normaliseRun', () => {
  it('gives a baseline run the null scenario FR-17 recognises it by', () => {
    const run = normaliseRun(BASELINE_RUN);

    expect(run.scenarioId).toBeNull();
    expect(run.scenarioName).toBeNull();
    // The test every reader makes, and the one that was silently false.
    expect(run.scenarioId === null).toBeTrue();
  });

  it('does not claim a locally computed run was restored from an archive', () => {
    const run = normaliseRun(BASELINE_RUN);

    expect(run.importedAt).toBeNull();
    expect(run.sourceRunId).toBeNull();
    // `run-discard.ts` selects restored results with `!== null`, and this is the assertion that
    // matters: a false positive here puts an untrue warning on an irreversible action (FR-20).
    expect(restoredRuns([run]).length).toBe(0);
  });

  it('still recognises a run that really was restored', () => {
    const restored = normaliseRun({
      ...BASELINE_RUN,
      importedAt: '2026-08-01T10:00:00Z',
      sourceRunId: 4,
    });

    expect(restoredRuns([restored]).length).toBe(1);
    expect(restored.sourceRunId).toBe(4);
  });

  it('keeps a null parameter set null rather than undefined', () => {
    // Absent because `SimulationService.fromJson` could not read `params_json` back. The view has
    // to be able to branch on it; `undefined` renders as a crash, not as a branch.
    expect(normaliseRun(BASELINE_RUN).params).toBeNull();
    expect(normaliseRun({ ...BASELINE_RUN, params: PARAMS }).params).toBe(PARAMS);
  });

  it('answers an absent unresolvedEventIds with an empty list, not a null', () => {
    const { unresolvedEventIds, ...withoutList } = BASELINE_RUN;
    expect(unresolvedEventIds).toEqual([]);
    // Its own contract says an accepted run carries an empty list, and no reader of it
    // has a null branch - so this is the one field where absence is not null.
    expect(normaliseRun(withoutList).unresolvedEventIds).toEqual([]);
  });

  it('leaves everything the wire did send exactly as it was', () => {
    const run = normaliseRun(BASELINE_RUN);

    expect(run.id).toBe(57);
    expect(run.networkId).toBe(18);
    expect(run.networkName).toBe('Playback 4-chain');
    expect(run.status).toBe(JobStatus.DONE);
    expect(run.horizonPeriods).toBe(30);
    expect(run.periodLength).toEqual(ONE_DAY);
    expect(run.startedAt).toBe('2026-08-06T23:47:36.470710Z');
  });

  it('normalises a list, which is what the FR-20 discard counts', () => {
    const runs = normaliseRuns([BASELINE_RUN, { ...BASELINE_RUN, id: 58 }]);
    expect(runs.length).toBe(2);
    expect(runs.every((run) => run.importedAt === null)).toBeTrue();
  });
});

describe('api-nulls - normaliseMetric', () => {
  /** `CVAR_COST` is a functional of the replication set, not a mean, so it has no interval. */
  const CVAR: WireMetric = {
    networkId: 18,
    runId: 57,
    metricCode: MetricCode.CVAR_COST,
    scope: MetricScope.NETWORK,
    value: 12094,
  };

  it('gives a metric with no interval two null bounds', () => {
    const metric = normaliseMetric(CVAR);

    expect(metric.ciLow).toBeNull();
    expect(metric.ciHigh).toBeNull();
    // `ci-value` draws a whisker when both are non-null; undefined would have it drawing one
    // between two undefineds instead of printing the plain number.
    expect(metric.ciLow !== null && metric.ciHigh !== null).toBeFalse();
  });

  it('gives a NETWORK-scoped row a null scope id rather than an absent one', () => {
    const metric = normaliseMetric(CVAR);

    expect(metric.scopeId).toBeNull();
    expect(metric.scopeName).toBeNull();
    expect(metric.displayUnit).toBeNull();
  });

  it('keeps the bounds and the scope a row does carry', () => {
    const metric = normaliseMetric({
      networkId: 18,
      metricCode: MetricCode.NODE_CRITICALITY,
      scope: MetricScope.NODE,
      scopeId: 139,
      scopeName: 'PLANT-1',
      value: 1,
    });

    expect(metric.scopeId).toBe(139);
    expect(metric.scopeName).toBe('PLANT-1');
    // A topological row belongs to the network, not to a run - absent, and it must read
    // as null for `runId === null` to mean what the model says it means.
    expect(metric.runId).toBeNull();
  });
});

describe('api-nulls - normaliseNode and normaliseLink', () => {
  /** `CUST-1` of the four-echelon sample: uncapped, unplaced, untagged - three omissions. */
  const CUSTOMER: WireNode = {
    id: 4,
    networkId: 18,
    name: 'CUST-1',
    type: 'CUSTOMER',
    // `<capacity timeUnit="DAY"/>` - a unit with no value, which is how an uncapped node is stored.
    // Jackson drops the number entirely.
    capacity: { timeUnit: TimeUnit.DAY },
    processingTime: { value: 0, unit: TimeUnit.DAY },
    fixedCost: 0,
    varCost: 0,
    failureProb: 0,
    // `NOT NULL DEFAULT TRUE` in V10, so the flag is always on the wire - it is the *caption* that
    // is omitted when there is none (FR-30).
    captionVisible: true,
    createdAt: '2026-08-06T23:47:00Z',
    updatedAt: '2026-08-06T23:47:00Z',
  };

  it('gives an unplaced node the two nulls the miniature chooses its layout by', () => {
    // `undefined !== null`, so without this a network imported without coordinates would report
    // itself as fully placed and draw every node on the origin (FR-22, `mini-map-layout.ts`).
    const node = normaliseNodes([CUSTOMER])[0];

    expect(node.posX).toBeNull();
    expect(node.posY).toBeNull();
    expect(node.posX !== null && node.posY !== null).toBeFalse();
    expect(node.region).toBeNull();
    expect(node.lat).toBeNull();
  });

  it('gives an uncapped element a null capacity value, on both ends of an arc', () => {
    // Two readings turn on this one field: *uncapped* - no ceiling to be a fraction of - and *no
    // capacity available*, an outage that took the ceiling to zero. They are different claims about
    // an arc, and absence made every uncapped one read as the second.
    const node = normaliseNodes([CUSTOMER])[0];
    const link: WireLink = {
      id: 30,
      networkId: 18,
      sourceNodeId: 3,
      targetNodeId: 4,
      leadTime: { value: 1, unit: TimeUnit.DAY },
      capacity: { timeUnit: TimeUnit.DAY },
      unitCost: 0,
      failureProb: 0,
      captionVisible: true,
      createdAt: '2026-08-06T23:47:00Z',
      updatedAt: '2026-08-06T23:47:00Z',
    };

    expect(node.capacity.value).toBeNull();
    expect(normaliseLink(link).capacity.value).toBeNull();
    // And the unit survives, so giving it a number later needs no unit invented for it.
    expect(normaliseLink(link).capacity.timeUnit).toBe(TimeUnit.DAY);
  });

  it('leaves a node that was placed and capped exactly as it arrived', () => {
    const placed = normaliseNodes([
      { ...CUSTOMER, capacity: { value: 100, timeUnit: TimeUnit.DAY }, posX: 640, posY: 480 },
    ])[0];

    expect(placed.posX).toBe(640);
    expect(placed.posY).toBe(480);
    expect(placed.capacity.value).toBe(100);
  });

  it('gives an uncaptioned element the null the editor undoes a caption edit through', () => {
    // The consequence is not cosmetic: `previousAttributeValue` reads a **null** caption as "there
    // was none, so undoing means the empty string that clears it", and an undefined one as "leave
    // it alone" - so without this, undoing "typed a caption on a node that had none" would report
    // success and leave the caption on the canvas (FR-30, `element-captions.ts`).
    const node = normaliseNodes([CUSTOMER])[0];
    const link = normaliseLink({
      id: 30,
      networkId: 18,
      sourceNodeId: 3,
      targetNodeId: 4,
      leadTime: { value: 1, unit: TimeUnit.DAY },
      capacity: { timeUnit: TimeUnit.DAY },
      unitCost: 0,
      failureProb: 0,
      captionVisible: true,
      createdAt: '2026-08-06T23:47:00Z',
      updatedAt: '2026-08-06T23:47:00Z',
    });

    expect(node.caption).toBeNull();
    expect(link.caption).toBeNull();
  });

  it('keeps a caption that is on the wire, on a node and on an arc', () => {
    const node = normaliseNodes([{ ...CUSTOMER, caption: 'Nordic hub - 3PL operated' }])[0];

    expect(node.caption).toBe('Nordic hub - 3PL operated');
    // The flag travels on its own terms: it is never omitted, so a hidden caption stays hidden.
    expect(normaliseNodes([{ ...CUSTOMER, caption: 'Kept, not drawn', captionVisible: false }])[0])
      .toEqual(jasmine.objectContaining({ caption: 'Kept, not drawn', captionVisible: false }));
  });
});

describe('api-nulls - normaliseResults', () => {
  const WIRE: WireResults = {
    run: BASELINE_RUN,
    metrics: [
      {
        networkId: 18,
        runId: 57,
        metricCode: MetricCode.FILL_RATE,
        scope: MetricScope.NETWORK,
        value: 1,
        ciLow: 1,
        ciHigh: 1,
      },
    ],
    timeseries: [
      {
        period: 0,
        totalDemand: 10,
        servedDemand: 10,
        cost: 404,
        baselineServedDemand: 10,
        baselineCost: 404,
        endingInventory: 40,
        inPipeline: 20,
      },
    ],
  };

  it('normalises the run and every metric row in one pass', () => {
    const results = normaliseResults(WIRE);

    expect(results.run.scenarioId).toBeNull();
    expect(results.metrics[0].scopeId).toBeNull();
    expect(results.metrics[0].ciLow).toBe(1);
  });

  it('leaves the curves alone, because RUN_TIMESERIES has no nullable column', () => {
    expect(normaliseResults(WIRE).timeseries).toBe(WIRE.timeseries);
  });

  it('survives a response with no metrics at all - a QUEUED or RUNNING run', () => {
    const results = normaliseResults({ ...WIRE, metrics: undefined });
    expect(results.metrics).toEqual([]);
  });
});
