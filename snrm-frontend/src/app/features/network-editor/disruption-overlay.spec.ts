import { DisruptionEvent, DisruptionTargetType, Id, Network } from '../../core/models';
import { OverlayResolution, buildOverlay, eventLine } from './disruption-overlay';
import { placeBar } from '../scenario-builder/timeline';

/** A 52-period network stepping in one day - the clock every expectation below is stated against. */
function daily(horizonPeriods = 52): Network {
  return {
    id: 7,
    projectId: 1,
    name: 'Baseline',
    version: 1,
    baseline: true,
    editable: true,
    periodLength: { value: 1, unit: 'DAY' },
    horizonPeriods,
    roundingPolicy: 'NEAREST',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  };
}

function event(
  id: Id,
  target: Partial<Pick<DisruptionEvent, 'targetType' | 'targetId' | 'targetRegion'>>,
  overrides: Partial<DisruptionEvent> = {},
): DisruptionEvent {
  return {
    id,
    scenarioId: 1,
    targetType: DisruptionTargetType.NODE,
    targetId: null,
    targetRegion: null,
    startOffset: { value: 5, unit: 'DAY' },
    duration: { value: 10, unit: 'DAY' },
    severity: 1,
    recoveryProfile: 'STEP',
    probability: 1,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...target,
    ...overrides,
  };
}

function resolution(overrides: Partial<OverlayResolution> = {}): OverlayResolution {
  return {
    nodeIds: new Set<Id>([10, 11, 12]),
    linkIds: new Set<Id>([20, 21]),
    regionNodes: new Map<string, readonly Id[]>(),
    ...overrides,
  };
}

describe('disruption-overlay', () => {
  describe('buildOverlay', () => {
    it('marks the node an event names', () => {
      const overlay = buildOverlay(
        [event(1, { targetType: 'NODE', targetId: 10 })],
        daily(),
        resolution(),
      );

      expect([...overlay.nodes.keys()]).toEqual([10]);
      expect(overlay.links.size).toBe(0);
      expect(overlay.unresolved).toEqual([]);
      expect(overlay.nodes.get(10)?.events[0].viaRegion).toBeNull();
    });

    it('marks the link an event names, and never the nodes at its ends', () => {
      const overlay = buildOverlay(
        [event(1, { targetType: 'LINK', targetId: 20 })],
        daily(),
        resolution(),
      );

      expect([...overlay.links.keys()]).toEqual([20]);
      expect(overlay.nodes.size).toBe(0);
    });

    it('places the badge on the same clock the timeline places the bar on', () => {
      const one = event(1, { targetType: 'NODE', targetId: 10 });
      const network = daily();

      const overlay = buildOverlay([one], network, resolution());

      // Not a re-derivation: the overlay is expected to hold the timeline's own placement, which is
      // what keeps "periods 5–15" from reading differently on the canvas and on the Gantt chart.
      expect(overlay.nodes.get(10)?.events[0].bar).toEqual(placeBar(one, network));
    });

    it('spreads a REGION event across every node the server resolved it to', () => {
      const overlay = buildOverlay(
        [event(1, { targetType: 'REGION', targetRegion: 'EU-West' })],
        daily(),
        resolution({ regionNodes: new Map([['EU-West', [10, 12]]]) }),
      );

      expect([...overlay.nodes.keys()].sort()).toEqual([10, 12]);
      expect(overlay.nodes.get(10)?.regionOnly).toBe(true);
      expect(overlay.nodes.get(10)?.events[0].viaRegion).toBe('EU-West');
      expect(overlay.unresolved).toEqual([]);
    });

    it('leaves a region tag the server has not answered unresolved rather than filtering nodes itself', () => {
      // The resolution map is empty: nothing has come back for `EU-West` yet. A client-side filter
      // over `node.region` would answer it here - and would be a second implementation of the
      // resolution a run will use.
      const one = event(1, { targetType: 'REGION', targetRegion: 'EU-West' });

      const overlay = buildOverlay([one], daily(), resolution());

      expect(overlay.nodes.size).toBe(0);
      expect(overlay.unresolved).toEqual([one]);
    });

    it('reports a region the server resolved to nothing as unresolved', () => {
      const one = event(1, { targetType: 'REGION', targetRegion: 'APAC' });

      const overlay = buildOverlay(
        [one],
        daily(),
        resolution({ regionNodes: new Map([['APAC', []]]) }),
      );

      expect(overlay.nodes.size).toBe(0);
      expect(overlay.unresolved).toEqual([one]);
    });

    it('reports an id this network does not hold as unresolved - a scenario outlives a network', () => {
      const stray = event(1, { targetType: 'NODE', targetId: 99 });
      const strayLink = event(2, { targetType: 'LINK', targetId: 98 });

      const overlay = buildOverlay([stray, strayLink], daily(), resolution());

      expect(overlay.nodes.size).toBe(0);
      expect(overlay.links.size).toBe(0);
      expect(overlay.unresolved).toEqual([stray, strayLink]);
    });

    it('drops a resolved region node the canvas no longer holds', () => {
      const one = event(1, { targetType: 'REGION', targetRegion: 'EU-West' });

      const overlay = buildOverlay(
        [one],
        daily(),
        // The server answered with a node the store has since removed.
        resolution({ regionNodes: new Map([['EU-West', [10, 44]]]) }),
      );

      expect([...overlay.nodes.keys()]).toEqual([10]);
      expect(overlay.unresolved).toEqual([]);
    });

    it('collects several events on one element, earliest window first', () => {
      const late = event(1, { targetType: 'NODE', targetId: 10 }, {
        startOffset: { value: 20, unit: 'DAY' },
      });
      const early = event(2, { targetType: 'NODE', targetId: 10 }, {
        startOffset: { value: 2, unit: 'DAY' },
      });

      const overlay = buildOverlay([late, early], daily(), resolution());

      expect(overlay.nodes.get(10)?.events.map((entry) => entry.event.id)).toEqual([2, 1]);
    });

    it('takes the hardest hit as the mark severity and notes a window past the horizon', () => {
      const mild = event(1, { targetType: 'NODE', targetId: 10 }, { severity: 0.3 });
      const hard = event(2, { targetType: 'NODE', targetId: 10 }, {
        severity: 0.9,
        duration: { value: 40, unit: 'DAY' },
      });

      const overlay = buildOverlay([mild, hard], daily(20), resolution());
      const mark = overlay.nodes.get(10);

      expect(mark?.maxSeverity).toBe(0.9);
      expect(mark?.exceedsHorizon).toBe(true);
    });

    it('is not regionOnly once one event names the node directly', () => {
      const overlay = buildOverlay(
        [
          event(1, { targetType: 'REGION', targetRegion: 'EU-West' }),
          event(2, { targetType: 'NODE', targetId: 10 }),
        ],
        daily(),
        resolution({ regionNodes: new Map([['EU-West', [10]]]) }),
      );

      expect(overlay.nodes.get(10)?.regionOnly).toBe(false);
      expect(overlay.nodes.get(10)?.events.length).toBe(2);
    });
  });

  describe('eventLine', () => {
    const network = daily();

    it('states severity, the declared window and what it becomes on this clock', () => {
      const one = event(1, { targetType: 'NODE', targetId: 10 }, { severity: 0.8 });

      expect(eventLine(one, placeBar(one, network), null)).toBe(
        '80% · 5 d → 10 d · periods 5–15 of 52',
      );
    });

    it('omits a probability of 1 and states anything else', () => {
      const certain = event(1, { targetType: 'NODE', targetId: 10 });
      const chancy = event(2, { targetType: 'NODE', targetId: 10 }, { probability: 0.6 });

      expect(eventLine(certain, placeBar(certain, network), null)).not.toContain('p ');
      expect(eventLine(chancy, placeBar(chancy, network), null)).toContain('p 60%');
    });

    it('names the region an event arrived through', () => {
      const one = event(1, { targetType: 'REGION', targetRegion: 'EU-West' });

      expect(eventLine(one, placeBar(one, network), 'EU-West')).toContain('via EU-West');
    });
  });
});
