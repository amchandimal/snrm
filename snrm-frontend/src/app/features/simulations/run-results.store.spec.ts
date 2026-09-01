import { HttpErrorResponse } from '@angular/common/http';
import { effect } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { NEVER, Observable, Subject, of, throwError } from 'rxjs';

import {
  WireLink,
  WireMetric,
  WireNode,
  WireResults,
  WireRun,
  WireTopologicalResponse,
} from '../../core/api-nulls';
import { ApiService } from '../../core/api.service';
import {
  ELEMENT_SERIES_UNAVAILABLE,
  ELEMENT_SERIES_UNREADABLE,
} from '../../core/element-series';
import { JobPollingService } from '../../core/job-polling.service';
import {
  Duration,
  ElementTimeseries,
  Job,
  JobStatus,
  MetricCode,
  MetricScope,
  SimulationParams,
  TimeUnit,
} from '../../core/models';
import { RunResultsStore } from './run-results.store';

/**
 * What the results dashboard's **route entry** must do (FR-08).
 *
 * Two defects on one click path - the run panel's "Full dashboard ↗" (FR-17) - are pinned
 * here, because both of them present as the same thing on screen: the breadcrumb, and nothing under
 * it. Everything the dashboard draws sits inside one `@if (run(); as record)`, so a store that never
 * loaded and a store holding a stale nothing are indistinguishable to a reader.
 *
 * **1. `open` must work when it is called from a reactive context.** Route entry is observed in an
 * `effect`, and an effect refuses signal writes unless its creator passed `allowSignalWrites`
 * (Angular's `NG0600`). `load` sets `runId` and `state` on its first line, so a caller that forgot
 * the flag got the refusal raised inside an `async` body - an unhandled rejection, not a failing
 * effect. The fetch never started, no request appeared on the network tab, and nothing on screen or
 * in an error banner said why. `open` therefore takes that permission itself (`untracked`), so the
 * store's usability stops depending on an option in a component three files away. The first spec
 * below creates a **deliberately flagless** effect for that reason: it is the caller this store has
 * to survive, not the caller it would prefer.
 *
 * **2. Entering the page must re-read the run.** The old test - `runId !== store.runId()` - looked
 * like a cache check over a store that is `providedIn: 'root'`, so it answered "is this store still
 * pointing at that run", never "is what it holds worth showing". A run that was `RUNNING` when the
 * page was first opened stayed unfinished forever; a first load that failed could not be retried by
 * navigating back to it. A run is a durable row that moves underneath its reader.
 */

const ONE_DAY: Duration = { value: 1, unit: TimeUnit.DAY };
const RUN_ID = 12;
const OTHER_RUN_ID = 13;
const NETWORK_ID = 7;

const PARAMS: SimulationParams = {
  replications: 100,
  seed: 42,
  horizonPeriods: 4,
  demandNoiseCv: 0,
  timingJitterPeriods: 0,
  includeRandomFailures: true,
  baselineSuppressesFailures: false,
  safetyStockPriority: 0.1,
  unmetDemandPenalty: null,
  quantum: 1000,
  engineVersion: 'test',
};

/**
 * A baseline run **as the API actually sends it**.
 *
 * Every null field is *omitted*, not written - `spring.jackson.default-property-inclusion=non_null`
 * (see `core/api-nulls.ts`). Writing `scenarioId: null` here instead would make this spec pass
 * against a shape the browser never receives, which is precisely how the defect survived
 * `run-discard.spec.ts`.
 */
function wireRun(runId: number, status: JobStatus = JobStatus.DONE): WireRun {
  return {
    id: runId,
    networkId: NETWORK_ID,
    networkName: 'Baseline',
    status,
    periodLength: ONE_DAY,
    horizonPeriods: 4,
    params: PARAMS,
    unresolvedEventIds: [],
  };
}

/**
 * The simulated suite as the API sends it.
 *
 * `CVAR_COST` is a functional of the replication set rather than a mean, so it carries no interval
 * - and the wire says that by leaving `ciLow`/`ciHigh` out. A NETWORK-scoped row leaves
 * `scopeId`/`scopeName` out for the same reason.
 */
const WIRE_METRICS: readonly WireMetric[] = [
  {
    networkId: NETWORK_ID,
    runId: RUN_ID,
    metricCode: MetricCode.FILL_RATE,
    scope: MetricScope.NETWORK,
    value: 1,
    ciLow: 1,
    ciHigh: 1,
  },
  {
    networkId: NETWORK_ID,
    runId: RUN_ID,
    metricCode: MetricCode.CVAR_COST,
    scope: MetricScope.NETWORK,
    value: 12094,
  },
];

function results(runId: number, status: JobStatus = JobStatus.DONE): WireResults {
  return { run: wireRun(runId, status), metrics: WIRE_METRICS, timeseries: [] };
}

/**
 * The same run **with its curve written** - a run the period cursor can move through (FR-22).
 *
 * `results()` above answers with an empty `timeseries`, which is what a `QUEUED` or `RUNNING` run
 * actually sends and therefore the right default for most of this spec. The cursor needs the
 * other case: four periods, matching the `horizonPeriods: 4` the record declares, so that "the
 * horizon comes from the run's own record" is asserted against a record that agrees with its series.
 */
function resultsWithCurve(runId: number): WireResults {
  return {
    run: wireRun(runId),
    metrics: WIRE_METRICS,
    timeseries: [0, 1, 2, 3].map((period) => ({
      period,
      totalDemand: 10,
      // Period 2 is the stockout, so the figures at the cursor differ from period to period rather
      // than being the same number wherever it is put.
      servedDemand: period === 2 ? 0 : 10,
      cost: 400 + period,
      baselineServedDemand: 10,
      baselineCost: 400,
      endingInventory: 20,
      inPipeline: 30,
    })),
  };
}

/**
 * `GET /networks/{id}/metrics/topological` with one criticality row.
 *
 * A row rather than an empty suite so that "the previous run's numbers are gone" is an assertion
 * about something that was there: the criticality table is drawn from the *network*, so it is the
 * panel most likely to survive a run switch unnoticed.
 */
const TOPOLOGICAL: WireTopologicalResponse = {
  networkId: NETWORK_ID,
  // As the API sends it: a structural metric is exact, so `ciLow`/`ciHigh` are absent rather than
  // null, and `runId` is absent because a topological metric belongs to the network.
  metrics: [
    {
      networkId: NETWORK_ID,
      metricCode: MetricCode.NODE_CRITICALITY,
      scope: MetricScope.NODE,
      scopeId: 3,
      scopeName: 'PLANT-1',
      value: 0.4,
    },
  ],
  computedInMs: 1,
};

/**
 * The run's network, as the wire sends it (FR-22).
 *
 * `SUP-1` is placed and capped; `CUST-1` is neither, and **says so by omission** - no `posX`, no
 * `posY`, no `value` inside its capacity. That is what `GET /networks/{id}/nodes` actually returns
 * for the four-echelon sample's customer (`<capacity timeUnit="DAY"/>`), and it is why the
 * store reads both lists through `core/api-nulls.ts`.
 */
const WIRE_NODES: readonly WireNode[] = [
  {
    id: 1,
    networkId: NETWORK_ID,
    name: 'SUP-1',
    type: 'SUPPLIER',
    capacity: { value: 100, timeUnit: TimeUnit.DAY },
    processingTime: { value: 0, unit: TimeUnit.DAY },
    fixedCost: 0,
    varCost: 0,
    failureProb: 0,
    posX: 0,
    posY: 0,
    // `caption_visible` is NOT NULL, so the wire always carries it; the caption itself is the
    // omitted half. Neither is drawn here - FR-30 is the editor's canvas, not this page.
    captionVisible: true,
    createdAt: '2026-08-07T00:00:00Z',
    updatedAt: '2026-08-07T00:00:00Z',
  },
  {
    id: 3,
    networkId: NETWORK_ID,
    name: 'PLANT-1',
    type: 'PLANT',
    capacity: { timeUnit: TimeUnit.DAY },
    processingTime: { value: 0, unit: TimeUnit.DAY },
    fixedCost: 0,
    varCost: 0,
    failureProb: 0,
    captionVisible: true,
    createdAt: '2026-08-07T00:00:00Z',
    updatedAt: '2026-08-07T00:00:00Z',
  },
];

const WIRE_LINKS: readonly WireLink[] = [
  {
    id: 30,
    networkId: NETWORK_ID,
    sourceNodeId: 1,
    targetNodeId: 3,
    leadTime: { value: 1, unit: TimeUnit.DAY },
    capacity: { timeUnit: TimeUnit.DAY },
    unitCost: 0,
    failureProb: 0,
    captionVisible: true,
    createdAt: '2026-08-07T00:00:00Z',
    updatedAt: '2026-08-07T00:00:00Z',
  },
];

/** One node and one link of per-element series - enough to prove the read happened. */
const ELEMENTS: ElementTimeseries = {
  available: true,
  nodes: [
    {
      nodeId: 1,
      name: 'SUP-1',
      onHand: [10, 10, 20, 10],
      inTransit: [0, 0, 0, 0],
      arrivals: [0, 0, 0, 0],
      served: [0, 0, 0, 0],
      unserved: [0, 0, 0, 0],
      throughput: [10, 10, 10, 0],
      availability: [1, 1, 1, 1],
      inboundLead: [null, null, null, null],
      baselineOnHand: [10, 10, 20, 10],
      baselineServed: [0, 0, 0, 0],
    },
  ],
  links: [
    {
      linkId: 30,
      sourceName: 'SUP-1',
      targetName: 'PLANT-1',
      flow: [10, 10, 0, 10],
      utilisation: [0.1, 0.1, 0, 0.1],
      availability: [1, 1, 1, 1],
      baselineFlow: [10, 10, 0, 10],
    },
  ],
};

/** The ordinary answers: the run, the structural suite, the network, and the element series. */
function ordinary(path: string): Observable<unknown> {
  if (path.endsWith('/metrics/topological')) {
    return of(TOPOLOGICAL);
  }
  if (path.endsWith('/nodes')) {
    return of(WIRE_NODES);
  }
  if (path.endsWith('/links')) {
    return of(WIRE_LINKS);
  }
  if (path.endsWith('/timeseries/elements')) {
    return of(ELEMENTS);
  }
  return of(results(RUN_ID));
}

/** Lets `load`'s promise chain - and the topological read it starts - settle. */
function settle(): Promise<void> {
  return new Promise<void>((resolve) => setTimeout(resolve, 0));
}

function job(status: JobStatus): Job {
  return { jobId: 'job-1', type: 'SIMULATION', status, progress: 1 };
}

describe('RunResultsStore - route entry', () => {
  let store: RunResultsStore;
  let gets: string[];
  let respond: (path: string) => Observable<unknown>;
  let polled: string[];
  let jobFeed: Subject<Job>;

  /** Every `GET /simulations/{runId}` this spec has seen - the whole point of most assertions. */
  function runReads(): string[] {
    return gets.filter((path) => /^\/simulations\/\d+$/.test(path));
  }

  beforeEach(() => {
    gets = [];
    polled = [];
    jobFeed = new Subject<Job>();
    respond = ordinary;

    const api = {
      get: (path: string) => {
        gets.push(path);
        return respond(path);
      },
      // Recorded under a verb of its own: `DELETE /simulations/12` shares a path with the read, and
      // a spec counting reads must not be able to count a deletion as one.
      delete: (path: string) => {
        gets.push(`DELETE ${path}`);
        return of(undefined);
      },
    };

    const jobs = {
      poll: (jobId: string) => {
        polled.push(jobId);
        return jobFeed.asObservable();
      },
      cancel: () => of(undefined),
    };

    TestBed.configureTestingModule({
      providers: [
        { provide: ApiService, useValue: api },
        { provide: JobPollingService, useValue: jobs },
      ],
    });
    store = TestBed.inject(RunResultsStore);
  });

  afterEach(() => store.ngOnDestroy());

  // ---------------------------------------------------------------- the write-permission defect

  it('loads when opened from an effect that did not ask for signal writes', async () => {
    // Deliberately flagless: this is the caller the store has to survive. Before `open` took the
    // permission itself, `load`'s first line (`_runId.set`) was refused with NG0600, the refusal
    // was swallowed as a rejected promise, and the dashboard rendered an empty page having issued
    // no request at all.
    TestBed.runInInjectionContext(() => {
      effect(() => store.open(RUN_ID, null));
    });
    TestBed.flushEffects();

    expect(runReads()).toEqual([`/simulations/${RUN_ID}`]);

    await settle();
    expect(store.run()?.id).toBe(RUN_ID);
    expect(store.state()).toBe('ready');
  });

  it('follows the job when the caller was told one, and polls nothing when it was not', async () => {
    TestBed.runInInjectionContext(() => {
      effect(() => store.open(RUN_ID, 'job-1'));
    });
    TestBed.flushEffects();
    await settle();

    expect(polled).toEqual(['job-1']);

    // A terminal status re-reads the run: the metric rows and the curves are written before the job
    // reports DONE, so this is the read that turns progress into results.
    jobFeed.next(job(JobStatus.DONE));
    await settle();
    expect(runReads().length).toBe(2);

    store.open(OTHER_RUN_ID, null);
    await settle();
    expect(polled).toEqual(['job-1']);
  });

  // ------------------------------------------------------- the wire's omissions (api-nulls.ts)

  it('turns the API’s omitted fields into the nulls the models promise', async () => {
    store.open(RUN_ID, null);
    await settle();

    const record = store.run();
    // `scenarioId === null` is how FR-17 recognises a baseline run. Absent, it read as a disruption
    // run: the wrong prose, a triangle claimed where the curves coincide, four metrics reported
    // missing rather than inapplicable.
    expect(record?.scenarioId).toBeNull();
    expect(record?.scenarioName).toBeNull();
    // `importedAt !== null` is how `run-discard.ts` selects restored archive results. Absent, every
    // locally computed run was warned about as one - on an irreversible action (FR-20).
    expect(record?.importedAt).toBeNull();
    expect(record?.sourceRunId).toBeNull();
    expect(record?.startedAt).toBeNull();

    // `ciLow !== null && ciHigh !== null` is how `ci-value` decides it has an interval to draw, so
    // an omitted bound would have it drawing a whisker between two undefineds. CVAR_COST is the row
    // that has no interval by definition.
    const cvar = store.metricCards().find((metric) => metric.metricCode === MetricCode.CVAR_COST);
    expect(cvar?.ciLow).toBeNull();
    expect(cvar?.ciHigh).toBeNull();
    expect(store.criticalityRows().length).toBe(1);
  });

  it('renders a run whose parameter set the server could not read', async () => {
    // `SimulationService.fromJson` logs and returns null when `params_json` will not deserialise,
    // and `non_null` then drops the field entirely. The run itself, its metrics and its curves are
    // all intact - only the reproducibility record is gone - so the page must show them.
    const withoutParams: WireRun = { ...wireRun(RUN_ID), params: undefined };
    respond = (path) =>
      path.endsWith('/metrics/topological')
        ? of(TOPOLOGICAL)
        : of({ run: withoutParams, metrics: WIRE_METRICS, timeseries: [] });

    store.open(RUN_ID, null);
    await settle();

    expect(store.state()).toBe('ready');
    expect(store.run()?.id).toBe(RUN_ID);
    // Null, not undefined: the template branches on it to say "parameters unavailable" rather than
    // dereferencing `params.seed` and taking the whole view update down with it.
    expect(store.run()?.params).toBeNull();
    // And the rest of the run is untouched - the numbers were computed from those parameters
    // whatever the row now fails to deserialise into.
    expect(store.metricCards().length).toBe(2);
  });

  // ------------------------------------------------------------------------ re-entry refreshes

  it('re-reads the run on every entry, including one it is already showing', async () => {
    store.open(RUN_ID, null);
    await settle();
    expect(runReads().length).toBe(1);

    // Back to the editor and in again. The run is durable but its status, metrics and curves are
    // not fixed - a `RUNNING` run read once would otherwise never be seen to finish.
    store.open(RUN_ID, null);
    await settle();

    expect(runReads()).toEqual([`/simulations/${RUN_ID}`, `/simulations/${RUN_ID}`]);
  });

  it('does not blank the page when re-entering the run it is already showing', async () => {
    store.open(RUN_ID, null);
    await settle();

    respond = () => NEVER;
    store.open(RUN_ID, null);

    // Still on screen while the re-read is in flight: blanking a page to redraw the same figures is
    // a flicker, not a refresh.
    expect(store.run()?.id).toBe(RUN_ID);
  });

  it('drops the previous run before a different one lands', async () => {
    store.open(RUN_ID, null);
    await settle();
    expect(store.run()?.id).toBe(RUN_ID);
    expect(store.criticalityRows().length).toBe(1);

    respond = () => NEVER;
    store.open(OTHER_RUN_ID, null);

    // One run's curve and cards under another run's id, for as long as the fetch takes, is a page
    // that lies about which run it is showing.
    expect(store.run()).toBeNull();
    expect(store.criticalityRows().length).toBe(0);
  });

  it('can be re-opened after a load failed, with or without the banner dismissed', async () => {
    respond = () =>
      throwError(
        () =>
          new HttpErrorResponse({
            status: 500,
            error: { code: 'INTERNAL', detail: 'Boom.' },
          }),
      );
    store.open(RUN_ID, null);
    await settle();
    expect(store.state()).toBe('error');

    // Dismissing leaves a page with no error, no spinner and no run - indistinguishable from a page
    // that never loaded. Coming back has to be a retry, or there is nothing left that is.
    store.dismissError();
    respond = ordinary;
    store.open(RUN_ID, null);
    await settle();

    expect(runReads().length).toBe(2);
    expect(store.run()?.id).toBe(RUN_ID);
    expect(store.state()).toBe('ready');
  });

  // -------------------------------------------------------- the scope and its two reads (FR-22)

  /** Every `GET …/timeseries/elements` this spec has seen. */
  function elementReads(): string[] {
    return gets.filter((path) => path.endsWith('/timeseries/elements'));
  }

  function structureReads(): string[] {
    return gets.filter((path) => path.endsWith('/nodes') || path.endsWith('/links'));
  }

  it('opens on the network scope and draws the run’s network straight away', async () => {
    // The inspector is on the page from the moment it renders, top-left, so the
    // structure is not lazy: a miniature with nothing in it is a hole, not a degradation.
    store.open(RUN_ID, null);
    await settle();

    expect(store.scope()).toEqual({ kind: 'network' });
    expect(store.isNetworkScope()).toBeTrue();
    expect(store.nodes().length).toBe(2);
    expect(store.links().length).toBe(1);
    // Read through `api-nulls`, so the omissions are the nulls the miniature branches on.
    expect(store.nodes()[1].posX).toBeNull();
    expect(store.links()[0].capacity.value).toBeNull();
  });

  it('reads the network once, however often the run itself is re-read', async () => {
    // A completed run freezes its network, so neither list can change under this page.
    store.open(RUN_ID, null);
    await settle();
    expect(structureReads().length).toBe(2);

    await store.refresh();
    await settle();
    jobFeed.next(job(JobStatus.DONE));
    await settle();

    expect(runReads().length).toBeGreaterThan(1);
    expect(structureReads().length).toBe(2);
  });

  it('spends nothing on the element series for a reader who never selects an element', async () => {
    // `horizon × (nodes + links)` numbers, which is why they are kept out of the run response
    // at all. The network scope - curve, cards, criticality - needs none of it.
    store.open(RUN_ID, null);
    await settle();

    expect(elementReads()).toEqual([]);
  });

  it('reads the element series once, at the first element scope, and never again', async () => {
    store.open(RUN_ID, null);
    await settle();

    store.selectNode(1);
    await settle();
    expect(elementReads()).toEqual([`/simulations/${RUN_ID}/timeseries/elements`]);
    expect(store.elementsAvailable()).toBeTrue();
    expect(store.scopedNode()?.name).toBe('SUP-1');
    expect(store.scopedNodeSeries()?.onHand).toEqual([10, 10, 20, 10]);

    // A second element, and a return trip through the network scope, cost nothing: the whole horizon
    // is in memory for as long as the run is.
    store.selectLink(30);
    await settle();
    store.selectNetwork();
    store.selectNode(1);
    await settle();

    expect(elementReads().length).toBe(1);
    expect(store.scopedLink()).toBeNull();
  });

  it('states the run with no element detail, and leaves the network scope working', async () => {
    // `available: false` is not an empty series and not an error: a run recorded before
    // `V9__element_timeseries.sql` answered a different question. The sentence is the editor's own,
    // verbatim, because two surfaces phrasing one situation differently read as two problems.
    respond = (path) =>
      path.endsWith('/timeseries/elements')
        ? of({ available: false, nodes: [], links: [] })
        : ordinary(path);

    store.open(RUN_ID, null);
    await settle();
    store.selectLink(30);
    await settle();

    expect(store.elementsAvailable()).toBeFalse();
    expect(store.elementNote()).toBe(ELEMENT_SERIES_UNAVAILABLE);
    expect(store.scopedLinkSeries()).toBeNull();
    // Never an error banner, and never a page of zeros.
    expect(store.error()).toBeNull();
    expect(store.metricCards().length).toBe(2);
    expect(store.criticalityRows().length).toBe(1);

    // And it is a durable fact about the run, so a second element click does not ask again.
    store.selectNode(1);
    await settle();
    expect(elementReads().length).toBe(1);
  });

  it('retries a failed element read on the next element click', async () => {
    // The other half of that rule: a read that failed may succeed, and clicking an element is the
    // gesture that says the reader still wants it.
    respond = (path) =>
      path.endsWith('/timeseries/elements')
        ? throwError(() => new HttpErrorResponse({ status: 503 }))
        : ordinary(path);

    store.open(RUN_ID, null);
    await settle();
    store.selectNode(1);
    await settle();

    expect(store.elementNote()).toBe(ELEMENT_SERIES_UNREADABLE);
    expect(store.error()).toBeNull();

    respond = ordinary;
    store.selectLink(30);
    await settle();

    expect(elementReads().length).toBe(2);
    expect(store.elementsAvailable()).toBeTrue();
    expect(store.elementNote()).toBeNull();
  });

  it('drops the scope, the network and the series when a different run is opened', async () => {
    store.open(RUN_ID, null);
    await settle();
    store.selectNode(1);
    await settle();

    respond = () => NEVER;
    store.open(OTHER_RUN_ID, null);

    // Node 1 of the run being left is not node 1 of the run being opened, and a page drawing one
    // run's series under another run's id is the defect this store already guards against.
    expect(store.scope()).toEqual({ kind: 'network' });
    expect(store.nodes()).toEqual([]);
    expect(store.scopedNodeSeries()).toBeNull();
    expect(store.elementsAvailable()).toBeFalse();
  });

  // ------------------------------------------------------------ the period cursor (FR-22)

  /** The ordinary answers, with a run that has a curve - see {@link resultsWithCurve}. */
  function withCurve(path: string): Observable<unknown> {
    return /^\/simulations\/\d+$/.test(path)
      ? of(resultsWithCurve(Number(path.split('/')[2])))
      : ordinary(path);
  }

  it('opens the cursor on the run’s last period, and spends no request on it', async () => {
    respond = withCurve;
    store.open(RUN_ID, null);
    await settle();

    // The end state, which is what a reader means by "the result" until they scrub - and the period
    // every metric card on the page is a horizon summary over.
    expect(store.cursorHorizon()).toBe(4);
    expect(store.lastCursorPeriod()).toBe(3);
    expect(store.cursorPeriod()).toBe(3);
    expect(store.hasCursor()).toBeTrue();
    // The default position is not a gesture, so it asks for nothing: a reader who opens a run and
    // reads its curve still pays for no `horizon × (nodes + links)` response.
    expect(elementReads()).toEqual([]);
  });

  it('restates the cursor through the run’s own clock, never as a bare index', async () => {
    respond = withCurve;
    store.open(RUN_ID, null);
    await settle();

    expect(store.cursorLabel()).toBe('Period 3 of 4 - 3 days');
    store.setCursor(1);
    expect(store.cursorLabel()).toBe('Period 1 of 4 - 1 day');
  });

  it('offers no cursor on a run that has not written its series', async () => {
    // A `QUEUED` run declares a horizon and has no periods to show. A scrub bar over them would be
    // a control that answers nothing, so the page renders none (`hasCursor`).
    store.open(RUN_ID, null);
    await settle();

    expect(store.cursorHorizon()).toBe(4);
    expect(store.hasCursor()).toBeFalse();
  });

  it('clamps the cursor into the run at both ends', async () => {
    respond = withCurve;
    store.open(RUN_ID, null);
    await settle();

    store.setCursor(99);
    expect(store.cursorPeriod()).toBe(3);
    store.setCursor(-4);
    expect(store.cursorPeriod()).toBe(0);
    store.stepCursor(-1);
    expect(store.cursorPeriod()).toBe(0);
    store.stepCursor(1);
    expect(store.cursorPeriod()).toBe(1);
  });

  it('reads the element series at the first move of the cursor, once', async () => {
    // The tints are the one thing on the page that needs it, and moving the cursor is the gesture
    // that asks for them. Dragging a slider across the horizon must not issue a request per period.
    respond = withCurve;
    store.open(RUN_ID, null);
    await settle();
    expect(elementReads()).toEqual([]);

    store.setCursor(2);
    await settle();
    store.setCursor(1);
    store.stepCursor(-1);
    store.selectNode(1);
    await settle();

    expect(elementReads().length).toBe(1);
    expect(store.elements()).not.toBeNull();
  });

  it('keeps the cursor where it is when the scope changes', async () => {
    // Changing what the page is about is not changing which period it is showing - and comparing an
    // element against the whole network *at one period* is what the scope is for (FR-22 item 6).
    respond = withCurve;
    store.open(RUN_ID, null);
    await settle();
    store.setCursor(1);
    await settle();

    store.selectNode(1);
    expect(store.cursorPeriod()).toBe(1);
    store.selectLink(30);
    expect(store.cursorPeriod()).toBe(1);
    store.selectNetwork();
    expect(store.cursorPeriod()).toBe(1);
  });

  it('does not rewind the cursor when the run is re-read', async () => {
    // Refresh, and the poll settling, both call `load` on the run already open - several times while
    // a run finishes. A reader parked on period 1 of a disruption window must not be thrown back to
    // the end of the horizon by a request they did not make.
    respond = withCurve;
    store.open(RUN_ID, null);
    await settle();
    store.setCursor(1);

    await store.refresh();
    await settle();
    jobFeed.next(job(JobStatus.DONE));
    await settle();

    expect(runReads().length).toBeGreaterThan(1);
    expect(store.cursorPeriod()).toBe(1);
  });

  it('re-defaults the cursor when a different run is opened', async () => {
    // Period 1 of the run being left is not period 1 of the run being opened, and a shorter horizon
    // may not have the period at all - the same rule the scope and the structure follow.
    respond = withCurve;
    store.open(RUN_ID, null);
    await settle();
    store.setCursor(0);
    expect(store.cursorPeriod()).toBe(0);

    store.open(OTHER_RUN_ID, null);
    await settle();

    expect(store.run()?.id).toBe(OTHER_RUN_ID);
    expect(store.cursorPeriod()).toBe(3);
  });

  it('re-reads a run it had already deleted from another surface', async () => {
    store.open(RUN_ID, null);
    await settle();
    expect(await store.deleteRun()).toBeTrue();
    expect(store.run()).toBeNull();

    // `deleteRun` empties the store *and* nulls its run id, so the old `runId() !== runId` test
    // happened to work here. It is asserted anyway: the page that survives a delete elsewhere in
    // the session must still be able to show a run with the same id it had been holding.
    store.open(RUN_ID, null);
    await settle();
    expect(store.run()?.id).toBe(RUN_ID);
  });
});
