import { TestBed } from '@angular/core/testing';
import { WritableSignal, computed, signal } from '@angular/core';
import { Observable, of, throwError } from 'rxjs';

import { ApiService } from '../../core/api.service';
import {
  Duration,
  ElementTimeseries,
  JobStatus,
  LinkTimeseries,
  NodeTimeseries,
  SimulationResults,
  SimulationRun,
  TimeUnit,
} from '../../core/models';
import { EditorRunStore } from './editor-run.store';
import { PlaybackPreferencesService } from './playback-preferences.service';
import { PlaybackStore } from './playback.store';

const ONE_DAY: Duration = { value: 1, unit: TimeUnit.DAY };
const NETWORK_ID = 42;

/** One node of the 4-echelon sample, as much of it as the channels read. */
function nodeSeries(nodeId: number, name: string, onHand: readonly number[]): NodeTimeseries {
  const zeroes = onHand.map(() => 0);
  return {
    nodeId,
    name,
    onHand,
    inTransit: zeroes,
    arrivals: zeroes,
    served: zeroes,
    unserved: zeroes,
    throughput: zeroes,
    availability: onHand.map(() => 1),
    inboundLead: onHand.map(() => null),
    baselineOnHand: onHand,
    baselineServed: zeroes,
  };
}

function linkSeries(linkId: number, flow: readonly number[]): LinkTimeseries {
  return {
    linkId,
    sourceName: 'SUP-1',
    targetName: 'PLANT-1',
    flow,
    utilisation: flow.map(() => null),
    availability: flow.map(() => 1),
    baselineFlow: flow,
  };
}

const ELEMENT_SERIES: ElementTimeseries = {
  available: true,
  nodes: [nodeSeries(1, 'SUP-1', [10, 10, 20, 10])],
  links: [linkSeries(10, [10, 10, 0, 10])],
};

/** Lets every pending microtask - the element read's promise chain - settle. */
function settle(): Promise<void> {
  return new Promise<void>((resolve) => setTimeout(resolve, 0));
}

interface ResultsOptions {
  /** How many timeseries rows the run actually delivered. Defaults to the declared horizon. */
  readonly rows?: number;
  readonly networkId?: number;
  readonly periodLength?: Duration;
}

/** A completed run's results - only the parts playback reads carry meaning. */
function results(horizonPeriods: number, options: ResultsOptions = {}): SimulationResults {
  const run: SimulationRun = {
    id: 1,
    networkId: options.networkId ?? NETWORK_ID,
    networkName: 'Editor Test',
    scenarioId: null,
    scenarioName: null,
    status: JobStatus.DONE,
    startedAt: null,
    finishedAt: null,
    periodLength: options.periodLength ?? ONE_DAY,
    horizonPeriods,
    params: {
      replications: 1,
      seed: 1,
      horizonPeriods,
      demandNoiseCv: 0,
      timingJitterPeriods: 0,
      includeRandomFailures: false,
      baselineSuppressesFailures: false,
      safetyStockPriority: 0,
      unmetDemandPenalty: null,
      quantum: 1,
      engineVersion: 'test',
    },
    // Locally computed, like every run this suite cares about. The archive provenance is
    // required on the DTO because the FR-20 discard dialog branches on it; playback reads neither.
    importedAt: null,
    sourceRunId: null,
    unresolvedEventIds: [],
  };
  return {
    run,
    metrics: [],
    timeseries: Array.from({ length: options.rows ?? horizonPeriods }, (_, period) => ({
      period,
      totalDemand: 0,
      servedDemand: 0,
      cost: 0,
      baselineServedDemand: 0,
      baselineCost: 0,
      endingInventory: 0,
      inPipeline: 0,
    })),
  };
}

describe('PlaybackStore', () => {
  let store: PlaybackStore;
  let prefs: PlaybackPreferencesService;
  let report: WritableSignal<SimulationResults | null>;
  let autoPlay: WritableSignal<number>;
  /** What `GET /simulations/{id}/timeseries/elements` answers next, and every path asked for. */
  let elementAnswer: () => Observable<ElementTimeseries>;
  let requested: string[];
  /** Frame callbacks the store has asked for and that have neither fired nor been cancelled. */
  let scheduled: Map<number, FrameRequestCallback>;
  let cancelled: number[];

  /** Delivers the frame the store is waiting for, at `timestamp` milliseconds since the origin. */
  function frame(timestamp: number): void {
    const next = scheduled.entries().next();
    if (next.done) {
      fail(`expected a requested animation frame before ${timestamp} ms`);
      return;
    }
    const [handle, callback] = next.value;
    scheduled.delete(handle);
    callback(timestamp);
  }

  beforeEach(() => {
    localStorage.removeItem(PlaybackPreferencesService.STORAGE_KEY);
    scheduled = new Map<number, FrameRequestCallback>();
    cancelled = [];
    let nextHandle = 1;

    spyOn(window, 'requestAnimationFrame').and.callFake((callback: FrameRequestCallback) => {
      const handle = nextHandle++;
      scheduled.set(handle, callback);
      return handle;
    });
    spyOn(window, 'cancelAnimationFrame').and.callFake((handle: number) => {
      cancelled.push(handle);
      scheduled.delete(handle);
    });

    report = signal<SimulationResults | null>(null);
    autoPlay = signal(0);
    const runs = {
      report: report.asReadonly(),
      reportRun: computed(() => report()?.run ?? null),
      autoPlayToken: autoPlay.asReadonly(),
    } as unknown as EditorRunStore;

    requested = [];
    elementAnswer = () => of(ELEMENT_SERIES);
    const api = {
      get: (path: string) => {
        requested.push(path);
        return elementAnswer();
      },
    } as unknown as ApiService;

    TestBed.configureTestingModule({
      providers: [
        PlaybackStore,
        { provide: EditorRunStore, useValue: runs },
        { provide: ApiService, useValue: api },
      ],
    });
    store = TestBed.inject(PlaybackStore);
    prefs = TestBed.inject(PlaybackPreferencesService);
    // The store's reset effect has to have run before anything is asserted about the transport.
    TestBed.flushEffects();
  });

  afterEach(() => {
    store.ngOnDestroy();
    localStorage.removeItem(PlaybackPreferencesService.STORAGE_KEY);
  });

  /** Loads a report and lets the store's reactive reset settle. */
  function load(loaded: SimulationResults | null): void {
    report.set(loaded);
    TestBed.flushEffects();
  }

  // ------------------------------------------------------------------ what there is to play

  describe('enabled', () => {
    it('is off with no report, and the transport does nothing', () => {
      expect(store.enabled()).toBeFalse();
      expect(store.horizonPeriods()).toBe(0);
      expect(store.periodLength()).toBeNull();
      expect(store.pace()).toBeNull();

      store.play();

      expect(store.playing()).toBeFalse();
      expect(scheduled.size).toBe(0);
    });

    it('comes on when a completed run is loaded', () => {
      load(results(30));

      expect(store.enabled()).toBeTrue();
      expect(store.horizonPeriods()).toBe(30);
      expect(store.lastPeriod()).toBe(29);
      expect(store.periodLength()).toEqual(ONE_DAY);
    });

    it('takes the shorter of the declared horizon and the delivered series', () => {
      // A QUEUED or RUNNING run answers with its record and empty lists, and a scrub bar
      // over periods that do not exist yet would be a lie.
      load(results(52, { rows: 0 }));
      expect(store.horizonPeriods()).toBe(0);
      expect(store.enabled()).toBeFalse();

      load(results(52, { rows: 20 }));
      expect(store.horizonPeriods()).toBe(20);
      expect(store.lastPeriod()).toBe(19);
      expect(store.enabled()).toBeTrue();
    });

    it('reads the run clock, never the live network clock', () => {
      // The point of SimulationRun.periodLength: a later time-base change on a forked
      // variant must not relabel an old run's playback.
      load(results(30, { periodLength: { value: 2, unit: TimeUnit.HOUR } }));

      expect(store.periodLength()).toEqual({ value: 2, unit: TimeUnit.HOUR });
      expect(store.pace()).toBe(
        'One period (2 hours) plays in 1 s; a full run (30 periods) plays in ~30 s.',
      );
    });
  });

  // -------------------------------------------------------------------------------- the loop

  describe('the frame loop', () => {
    // 10 periods per second, so one period is 100 ms of frame time. Every delta below is well
    // under MAX_FRAME_DT (250 ms) on purpose: the clamp is real, it applies to ordinary frames as
    // much as to a backgrounded tab, and a spec written with 500 ms frames would be measuring the
    // clamp rather than the pace. Its own test drives it deliberately.
    beforeEach(() => {
      load(results(30));
      prefs.setSpeed(NETWORK_ID, 10);
      expect(store.speed()).toBe(10);
    });

    it('advances by timestamp delta, not by frame count', () => {
      store.play();
      expect(store.playing()).toBeTrue();

      frame(0); // seeds the delta and advances nothing
      expect(store.currentPeriod()).toBe(0);

      frame(100); // a tenth of a second at 10 periods/s is one period
      expect(store.currentPeriod()).toBe(1);

      frame(200);
      expect(store.currentPeriod()).toBe(2);

      // Thirty short frames covering 120 ms reach the same place one 120 ms frame would: the pace
      // is a function of elapsed time, not of how many times the browser called back.
      for (let i = 1; i <= 30; i++) {
        frame(200 + (i * 120) / 30);
      }
      expect(store.currentPeriod()).toBe(3); // 2 + 0.12 s x 10 = 3.2 periods
    });

    it('holds the period signal steady within a period', () => {
      // The reason simTime is a private field: four frames inside one period must present the same
      // period, so nothing downstream re-renders per frame.
      store.play();
      frame(0);
      const withinPeriod = [20, 40, 60, 80].map((timestamp) => {
        frame(timestamp);
        return store.currentPeriod();
      });

      expect(withinPeriod).toEqual([0, 0, 0, 0]);

      frame(120); // 1.2 periods in - the first frame of period 1
      expect(store.currentPeriod()).toBe(1);
    });

    it('clamps a background-tab delta so a refocus resumes instead of leaping', () => {
      store.play();
      frame(0);
      frame(100);
      expect(store.currentPeriod()).toBe(1);

      // Ten seconds in another tab. Unclamped that is 100 periods on, well past the end of this
      // 30-period run; clamped it is a quarter-second of movement.
      frame(10_100);

      expect(store.currentPeriod()).toBe(3); // 1 + 0.25 s x 10 = 3.5 periods
      expect(store.playing()).toBeTrue();
    });

    it('pauses where it is and re-seeds the delta on resume', () => {
      store.play();
      frame(0);
      frame(100);
      expect(store.currentPeriod()).toBe(1);

      store.pause();

      expect(store.playing()).toBeFalse();
      expect(cancelled.length).toBe(1);
      expect(scheduled.size).toBe(0);

      // A minute later. The first frame after a resume establishes the baseline and moves nothing;
      // without nulling the timestamp the resume would advance by a clamped 0.25 s it never spent.
      store.play();
      frame(60_000);
      expect(store.currentPeriod()).toBe(1);

      frame(60_100);
      expect(store.currentPeriod()).toBe(2);
    });

    it('takes a mid-play speed change on the next frame, without moving the clock', () => {
      store.play();
      frame(0);
      frame(100);
      expect(store.currentPeriod()).toBe(1);

      store.setSpeed(20);

      expect(store.speed()).toBe(20);
      expect(store.currentPeriod()).toBe(1); // no jump: the clock is where the last frame left it
      expect(store.playing()).toBeTrue();

      // The same 50 ms that was half a period a moment ago is now a whole one.
      frame(150);
      expect(store.currentPeriod()).toBe(2);
    });

    it('rests on the last period when the run ends, and restarts on the next play', () => {
      store.play();
      frame(0);
      // Driving it home through the frame cap: 0.25 s at 10 periods/s is 2.5 periods a frame, so
      // the 30-period run ends on the twelfth.
      for (let i = 1; i <= 100 && store.playing(); i++) {
        frame(i * 250);
      }

      expect(store.playing()).toBeFalse();
      expect(store.currentPeriod()).toBe(29); // the last index, not the horizon of 30
      expect(scheduled.size).toBe(0);

      store.play();

      expect(store.playing()).toBeTrue();
      expect(store.currentPeriod()).toBe(0);
    });

    it('toggles', () => {
      store.toggle();
      expect(store.playing()).toBeTrue();

      store.toggle();
      expect(store.playing()).toBeFalse();
    });
  });

  // --------------------------------------------------------------------------- seek and step

  describe('seek and step', () => {
    beforeEach(() => load(results(30)));

    it('clamps into the run', () => {
      store.seek(12);
      expect(store.currentPeriod()).toBe(12);

      store.seek(999);
      expect(store.currentPeriod()).toBe(29);

      store.seek(-4);
      expect(store.currentPeriod()).toBe(0);
    });

    it('steps a period at a time, pausing first', () => {
      store.play();
      expect(store.playing()).toBeTrue();

      store.stepForward();

      expect(store.playing()).toBeFalse();
      expect(store.currentPeriod()).toBe(1);

      store.stepForward();
      expect(store.currentPeriod()).toBe(2);

      store.stepBack();
      expect(store.currentPeriod()).toBe(1);
    });

    it('does not step off either end', () => {
      store.stepBack();
      expect(store.currentPeriod()).toBe(0);

      store.seek(29);
      store.stepForward();
      expect(store.currentPeriod()).toBe(29);
    });

    it('keeps playing through a scrub', () => {
      prefs.setSpeed(NETWORK_ID, 10); // one period per 100 ms of frame time
      store.play();
      frame(0);

      store.seek(20);

      expect(store.playing()).toBeTrue();
      expect(store.currentPeriod()).toBe(20);

      frame(100);
      expect(store.currentPeriod()).toBe(21);
    });

    it('restarts from the beginning and plays', () => {
      store.seek(20);

      store.restart();

      expect(store.currentPeriod()).toBe(0);
      expect(store.playing()).toBeTrue();
    });
  });

  // ------------------------------------------------------------------------------- the speed

  describe('speed', () => {
    it('opens on the horizon-derived default', () => {
      load(results(30));
      expect(store.speed()).toBe(1); // 30 / 30

      load(results(52, { networkId: 7 }));
      expect(store.speed()).toBe(2); // 52 / 30 lands on 2
    });

    it('is remembered per network, so two runs of two networks differ', () => {
      load(results(30));
      store.setSpeed(5);
      expect(store.speed()).toBe(5);

      load(results(30, { networkId: 7 }));
      expect(store.speed()).toBe(1); // network 7 has no pick of its own

      load(results(30));
      expect(store.speed()).toBe(5); // network 42 still does
    });

    it('goes through the preference service - one write path', () => {
      load(results(30));

      store.setSpeed(20);

      expect(prefs.speedFor(NETWORK_ID, 30)).toBe(20);
    });

    it('ignores a speed off the ladder', () => {
      load(results(30));

      store.setSpeed(3);

      expect(store.speed()).toBe(1);
      expect(prefs.hasPick(NETWORK_ID)).toBeFalse();
    });

    it('is inert with no run loaded', () => {
      expect(() => store.setSpeed(5)).not.toThrow();
      expect(prefs.hasPick(NETWORK_ID)).toBeFalse();
    });
  });

  // ------------------------------------------------------------------------ reactive resets

  describe('reactive resets', () => {
    it('rewinds and pauses when a different report arrives', () => {
      load(results(30));
      store.seek(20);
      store.play();
      expect(store.playing()).toBeTrue();

      load(results(52, { networkId: 7 }));

      expect(store.playing()).toBeFalse();
      expect(store.currentPeriod()).toBe(0);
      expect(cancelled.length).toBe(1);
    });

    it('clears when the report goes away', () => {
      load(results(30));
      store.seek(9);
      store.play();

      load(null); // what a fresh submission does to the run store

      expect(store.enabled()).toBeFalse();
      expect(store.playing()).toBeFalse();
      expect(store.currentPeriod()).toBe(0);
    });

    it('leaves the position alone when the same report is re-emitted', () => {
      const loaded = results(30);
      load(loaded);
      store.seek(11);

      load(loaded);

      expect(store.currentPeriod()).toBe(11);
    });
  });

  // ----------------------------------------------------------------------- the element series

  describe('the element series (FR-18)', () => {
    it('reads it once per report, from the run the report names', async () => {
      load(results(30));
      await settle();

      expect(requested).toEqual(['/simulations/1/timeseries/elements']);
      expect(store.seriesAvailable()).toBeTrue();
      expect(store.elementsLoading()).toBeFalse();
      expect(store.elements()?.nodes.get(1)?.maxOnHand).toBe(20);
      expect(store.elements()?.links.get(10)?.maxFlow).toBe(10);
      expect(store.elementNote()).toBeNull();

      // Re-emitting the same report is not a new run, and must not be a second request.
      load(report());
      await settle();
      expect(requested.length).toBe(1);
    });

    it('keeps playback on when the run recorded no elements, and says so', async () => {
      elementAnswer = () => of({ available: false, nodes: [], links: [] });

      load(results(30));
      await settle();

      // The clock, the transport and the curve all read RUN_TIMESERIES, which this run has.
      expect(store.enabled()).toBeTrue();
      expect(store.seriesAvailable()).toBeFalse();
      expect(store.elements()).toBeNull();
      expect(store.elementNote()).toBe('element detail unavailable for this run (recorded before V9)');
    });

    it('degrades to the same silence when the read fails', async () => {
      elementAnswer = () => throwError(() => new Error('offline'));

      load(results(30));
      await settle();

      expect(store.enabled()).toBeTrue();
      expect(store.elements()).toBeNull();
      expect(store.elementNote()).toBe('element detail could not be read for this run');
    });

    it('does not ask for a run whose series are still empty', async () => {
      // A QUEUED or RUNNING run answers `available: false` anyway - no request to spend.
      load(results(52, { rows: 0 }));
      await settle();

      expect(requested).toEqual([]);
      expect(store.elementNote()).toBeNull(); // playback is off; there is nothing to explain
    });

    it('clears the series when the report goes away', async () => {
      load(results(30));
      await settle();
      expect(store.elements()).not.toBeNull();

      load(null);

      expect(store.elements()).toBeNull();
      expect(store.seriesAvailable()).toBeFalse();
    });

    it('drops an answer for a report the viewer has already left', async () => {
      // The 30-period run's read resolves after the 52-period one has been loaded. Landing it would
      // animate one run's elements against another run's clock.
      let resolveFirst: (series: ElementTimeseries) => void = () => undefined;
      elementAnswer = () =>
        new Observable<ElementTimeseries>((subscriber) => {
          resolveFirst = (series) => {
            subscriber.next(series);
            subscriber.complete();
          };
        });
      load(results(30));

      elementAnswer = () => of({ available: false, nodes: [], links: [] });
      load(results(52, { networkId: 7 }));
      await settle();

      resolveFirst(ELEMENT_SERIES);
      await settle();

      expect(store.seriesAvailable()).toBeFalse();
      expect(store.elements()).toBeNull();
    });
  });

  // ---------------------------------------------------------------------------- auto-play

  describe('auto-play (FR-18)', () => {
    /** What `EditorRunStore.onSettled` does: the report lands, then the token is bumped. */
    function runCompletes(loaded: SimulationResults): void {
      report.set(loaded);
      autoPlay.update((token) => token + 1);
      TestBed.flushEffects();
    }

    it('rewinds and plays when a run this session launched completes', () => {
      runCompletes(results(30));

      expect(store.currentPeriod()).toBe(0);
      expect(store.playing()).toBeTrue();
      expect(scheduled.size).toBe(1);
    });

    it('starts once, so a re-emitted report does not restart the animation', () => {
      const loaded = results(30);
      runCompletes(loaded);
      store.pause();
      store.seek(14);

      load(loaded);

      expect(store.playing()).toBeFalse();
      expect(store.currentPeriod()).toBe(14);
    });

    it('stays put when the completed run has nothing to play', () => {
      runCompletes(results(52, { rows: 0 }));

      expect(store.playing()).toBeFalse();
    });

    it('does not fire for a report loaded without a run completing', () => {
      // Opening the editor on an existing report is not "I just pressed Run".
      load(results(30));

      expect(store.playing()).toBeFalse();
    });
  });

  describe('ngOnDestroy', () => {
    it('cancels the pending frame', () => {
      load(results(30));
      store.play();
      expect(scheduled.size).toBe(1);

      store.ngOnDestroy();

      expect(cancelled.length).toBe(1);
      expect(scheduled.size).toBe(0);
    });
  });
});
