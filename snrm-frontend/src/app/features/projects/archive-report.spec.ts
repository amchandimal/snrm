import { ArchiveCounts, ArchiveFinding, ArchiveReport } from '../../core/models';
import {
  ArchiveSeverity,
  archiveSeverity,
  countBySeverity,
  countRows,
  engineWarning,
  hasShortfall,
  provenanceNote,
  restoreHeadline,
  shapeFindings,
} from './archive-report';

const FULL: ArchiveCounts = {
  networks: 2,
  products: 3,
  scenarios: 1,
  events: 4,
  runs: 7,
  metricResults: 84,
  timeseriesRows: 364,
};

function counts(overrides: Partial<ArchiveCounts> = {}): ArchiveCounts {
  return { ...FULL, ...overrides };
}

function finding(code: string, message = 'Something happened.'): ArchiveFinding {
  return { code, subject: 'Plant outage', message };
}

function report(overrides: Partial<ArchiveReport> = {}): ArchiveReport {
  return {
    projectId: 7,
    projectName: 'Resilience study (restored)',
    sourceCounts: counts(),
    restoredCounts: counts(),
    engineVersion: '1.0',
    engineMatches: true,
    findings: [],
    ...overrides,
  };
}

describe('archive-report', () => {
  describe('archiveSeverity', () => {
    it('ranks the engine mismatch above everything else it qualifies', () => {
      expect(archiveSeverity('ENGINE_VERSION_MISMATCH')).toBe(ArchiveSeverity.CRITICAL);
    });

    it('warns about a restored-but-broken reference', () => {
      expect(archiveSeverity('EVENT_TARGET_UNRESOLVED')).toBe(ArchiveSeverity.WARNING);
      expect(archiveSeverity('METRIC_SCOPE_UNRESOLVED')).toBe(ArchiveSeverity.WARNING);
    });

    it('treats an adjusted version and a rename as notices - nothing was lost either time', () => {
      expect(archiveSeverity('NETWORK_VERSION_ADJUSTED')).toBe(ArchiveSeverity.NOTICE);
      expect(archiveSeverity('PROJECT_RENAMED')).toBe(ArchiveSeverity.NOTICE);
    });

    it('treats a subset archive as a notice - a selection is not a fault (FR-24)', () => {
      // Nothing went wrong: somebody ticked three of six networks. The finding explains why the
      // restored project looks the way it does. Where a consequence *is* a warning, it arrives as
      // its own finding - EVENT_TARGET_UNRESOLVED, once per affected event.
      expect(archiveSeverity('ARCHIVE_IS_SUBSET')).toBe(ArchiveSeverity.NOTICE);
    });

    it('defaults an unknown code to a warning rather than to a notice', () => {
      // A sixth code added by a later backend must not be rendered as harmless by a frontend that
      // has never heard of it. This is the whole reason the default is not NOTICE.
      expect(archiveSeverity('SOMETHING_THIS_BUILD_HAS_NEVER_SEEN')).toBe(ArchiveSeverity.WARNING);
    });
  });

  describe('shapeFindings', () => {
    it('groups by severity', () => {
      const shaped = shapeFindings([
        finding('PROJECT_RENAMED'),
        finding('EVENT_TARGET_UNRESOLVED'),
        finding('ENGINE_VERSION_MISMATCH'),
      ]);

      expect(shaped.map((entry) => entry.code)).toEqual([
        'ENGINE_VERSION_MISMATCH',
        'EVENT_TARGET_UNRESOLVED',
        'PROJECT_RENAMED',
      ]);
    });

    it('keeps the server’s order within one severity', () => {
      // The backend ranks the list before sending it; re-ordering inside a group would discard
      // information this client does not have.
      const shaped = shapeFindings([
        finding('METRIC_SCOPE_UNRESOLVED', 'second'),
        finding('EVENT_TARGET_UNRESOLVED', 'first'),
      ]);

      expect(shaped.map((entry) => entry.message)).toEqual(['second', 'first']);
    });

    it('counts by severity for the summary badges', () => {
      const shaped = shapeFindings([
        finding('ENGINE_VERSION_MISMATCH'),
        finding('EVENT_TARGET_UNRESOLVED'),
        finding('METRIC_SCOPE_UNRESOLVED'),
        finding('PROJECT_RENAMED'),
      ]);

      expect(countBySeverity(shaped, ArchiveSeverity.CRITICAL)).toBe(1);
      expect(countBySeverity(shaped, ArchiveSeverity.WARNING)).toBe(2);
      expect(countBySeverity(shaped, ArchiveSeverity.NOTICE)).toBe(1);
    });
  });

  describe('engineWarning', () => {
    it('says nothing when the engine matches', () => {
      expect(engineWarning(report())).toBeNull();
    });

    it('uses the server’s own sentence verbatim', () => {
      const stated = finding(
        'ENGINE_VERSION_MISMATCH',
        'The archived runs were produced by simulation engine 0.9 and this installation runs 1.0.',
      );

      expect(engineWarning(report({ engineMatches: false, findings: [stated] }))).toBe(
        stated.message,
      );
    });

    it('falls back to its own sentence when the finding is missing', () => {
      // `engineMatches: false` with no matching finding is representable in the DTO, and an empty
      // warning banner would be worse than a generic one.
      const message = engineWarning(report({ engineMatches: false, engineVersion: '0.9' }));

      expect(message).toContain('0.9');
      expect(message).toContain('replays exactly only against the engine that wrote it');
    });

    it('names an unstated engine version rather than printing null', () => {
      const message = engineWarning(
        report({ engineMatches: false, engineVersion: null, findings: [] }),
      );

      expect(message).toContain('(unstated)');
    });
  });

  describe('countRows', () => {
    it('compares what arrived against what the manifest claimed', () => {
      const rows = countRows(
        report({ sourceCounts: counts(), restoredCounts: counts({ runs: 5, metricResults: 60 }) }),
      );
      const runs = rows.find((row) => row.label === 'Simulation runs');

      expect(runs).toEqual({ label: 'Simulation runs', source: 7, restored: 5, shortfall: 2 });
      expect(hasShortfall(report({ restoredCounts: counts({ runs: 5 }) }))).toBe(true);
    });

    it('reports no shortfall when the counts agree', () => {
      expect(countRows(report()).every((row) => row.shortfall === 0)).toBe(true);
      expect(hasShortfall(report())).toBe(false);
    });

    it('never reports a negative shortfall when more was written than claimed', () => {
      // Restored metric results include the network-scoped topological rows, so this direction is
      // reachable rather than hypothetical; "−4 missing" would be nonsense on screen.
      const rows = countRows(report({ restoredCounts: counts({ metricResults: 88 }) }));

      expect(rows.find((row) => row.label === 'Metric results')?.shortfall).toBe(0);
    });

    it('compares against nothing when the bundle carried no manifest', () => {
      const rows = countRows(report({ sourceCounts: null }));

      expect(rows.every((row) => row.source === null && row.shortfall === 0)).toBe(true);
      expect(hasShortfall(report({ sourceCounts: null }))).toBe(false);
    });
  });

  describe('restoreHeadline', () => {
    it('names the created project and calls a clean restore exact', () => {
      const headline = restoreHeadline(report());

      expect(headline).toContain('Resilience study (restored)');
      expect(headline).toContain('#7');
      expect(headline).toContain('exactly - no findings');
    });

    it('leads with the engine mismatch, which outranks a shortfall', () => {
      const headline = restoreHeadline(
        report({ engineMatches: false, restoredCounts: counts({ runs: 5 }) }),
      );

      expect(headline).toContain('different simulation engine');
    });

    it('points at the findings when something did not arrive', () => {
      expect(restoreHeadline(report({ restoredCounts: counts({ runs: 5 }) }))).toContain(
        'did not arrive',
      );
    });

    it('mentions notes when everything arrived but a finding was raised', () => {
      expect(restoreHeadline(report({ findings: [finding('PROJECT_RENAMED')] }))).toContain(
        'notes worth reading',
      );
    });
  });

  describe('provenanceNote', () => {
    it('says restored runs are marked, in the plural', () => {
      expect(provenanceNote(report())).toContain('All 7 restored runs are marked');
    });

    it('says it in the singular for one run', () => {
      expect(provenanceNote(report({ restoredCounts: counts({ runs: 1 }) }))).toContain(
        'The restored run is marked',
      );
    });

    it('says nothing about provenance when no run was restored', () => {
      expect(provenanceNote(report({ restoredCounts: counts({ runs: 0 }) }))).toBeNull();
    });
  });
});
