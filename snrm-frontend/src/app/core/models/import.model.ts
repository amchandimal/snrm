import { Id } from './common.model';
import { Network } from './network.model';
import { Duration, RoundingPolicy, TimeValidationReport } from './time.model';

/**
 * Data-import and export DTOs (FR-02).
 *
 * Three endpoints:
 *
 * - `POST /networks/import/preview` (multipart) → {@link ImportPreview}, which fills wizard step 2.
 * - `POST /networks/import` (multipart) → {@link ImportReport}; `validateOnly` makes it a dry run,
 *   which is what step 3 shows before the user confirms.
 * - `GET /networks/{id}/export?format=xlsx|csv` → the same schema back out, unit-preserving.
 *
 * Mirrors `ImportPreview`, `ImportReport`, `ImportDiagnostic` and `ImportTimeBase` on the backend.
 *
 * Note on the path: the obvious spelling is `POST /networks/{id}/import`, but the import
 * *creates* the network, so there is no id yet. The backend implements `POST /networks/import` with
 * the project in the body; the backend documents the same deviation.
 */

/** The canonical sheets a network import is composed of, in schema order. */
export const ImportSheet = {
  /** Optional. Absent means 1 DAY / 52 / NEAREST, and the wizard must confirm it. */
  NETWORK_META: 'network_meta',
  NODES: 'nodes',
  LINKS: 'links',
  /** Optional; a default product is created if absent. */
  PRODUCTS: 'products',
  NODE_PRODUCTS: 'node_products',
} as const;

export type ImportSheet = (typeof ImportSheet)[keyof typeof ImportSheet];

/** Every sheet, in the order the report and the mapping step list them. */
export const IMPORT_SHEETS: readonly ImportSheet[] = [
  ImportSheet.NETWORK_META,
  ImportSheet.NODES,
  ImportSheet.LINKS,
  ImportSheet.PRODUCTS,
  ImportSheet.NODE_PRODUCTS,
];

/**
 * One canonical column, as the server describes it.
 *
 * The schema arrives from the server with every preview rather than being duplicated here: a second
 * copy of the column list in the client is how an export stops re-importing.
 */
export interface CanonicalColumn {
  readonly name: string;
  /** The `*` of the table: the sheet is invalid without it. */
  readonly required: boolean;
  /**
   * For a `*_unit` column, the value column it qualifies. Omitting a unit column means the value is
   * read in the network's period unit, so a plain numeric file imports unchanged.
   */
  readonly unitOwner?: string;
  readonly note: string;
}

/** One source column and the canonical one it would feed; null means it is ignored. */
export interface ColumnSuggestion {
  readonly sourceColumn: string;
  readonly canonicalColumn: string | null;
}

/** One sheet of the upload, as parsed. */
export interface ImportSheetPreview {
  readonly sheet: ImportSheet;
  /** The file or worksheet it came from. */
  readonly sourceName: string;
  /** How it was read, including the detected CSV delimiter - `CSV (delimiter ';')`, `XLSX`. */
  readonly origin: string;
  readonly headers: readonly string[];
  readonly rowCount: number;
  readonly columns: readonly ColumnSuggestion[];
  /** Required columns nothing feeds yet. An error unless the mapping step fixes it. */
  readonly missingRequired: readonly string[];
  readonly sampleRows: readonly (readonly string[])[];
}

/**
 * The clock an import will give the network.
 *
 * `declared` false means these are the defaults because no `network_meta` sheet was supplied. The
 * wizard must ask for confirmation in that case - every value in a column without a
 * `*_unit` column is read in this period's unit, so it is not a detail to skip past.
 */
export interface ImportTimeBase {
  readonly periodLength: Duration;
  readonly horizonPeriods: number;
  readonly roundingPolicy: RoundingPolicy;
  readonly declared: boolean;
}

/** `POST /networks/import/preview`: what the upload contains and the mapping to propose. */
export interface ImportPreview {
  readonly sheets: readonly ImportSheetPreview[];
  /**
   * The network name the file declares, if any - an XML export always carries one, and
   * `network_meta` has an optional `name` column. The wizard offers it as the default, so a network
   * exported and re-imported needs nothing typed.
   */
  readonly declaredName?: string;
  readonly timeBase: ImportTimeBase;
  /** Canonical schema keyed by sheet name - the options the mapping dropdowns offer. */
  readonly schema: Readonly<Record<string, readonly CanonicalColumn[]>>;
  readonly rowsRead: Readonly<Record<string, number>>;
  readonly diagnostics: readonly ImportDiagnostic[];
}

/**
 * The user's mapping from step 2: sheet → source header → canonical column.
 *
 * Keyed by source header because that is the direction the user works in, and the only one that can
 * express "ignore this column" (null) as distinct from "this canonical column has no source". Sent as
 * a JSON string in the `mapping` form field. Only overrides need to be sent - a header that is already
 * canonical is matched by the server.
 */
export type ImportMapping = Partial<Record<ImportSheet, Record<string, string | null>>>;

/** Severity of an import diagnostic. One error anywhere means no network is created. */
export const ImportSeverity = {
  ERROR: 'ERROR',
  WARNING: 'WARNING',
} as const;

export type ImportSeverity = (typeof ImportSeverity)[keyof typeof ImportSeverity];

/**
 * One diagnostic, addressed precisely enough to fix.
 *
 * Row-level checks carry `line` and usually `column`. Network-level checks (stage 2)
 * leave both out where they are statements about the whole graph - "customer CUST-2 has no inbound
 * path" - and name the element instead. Absent members are omitted from the JSON, not null.
 */
export interface ImportDiagnostic {
  readonly sheet?: ImportSheet;
  /** 1-based line as the user's editor numbers it, header included. */
  readonly line?: number;
  readonly column?: string;
  readonly severity: ImportSeverity;
  /**
   * Stable machine code. Values are `ImportCheck` names for the two stages and `TimeCheck`
   * names for the resolution findings folded in beside them - one namespace.
   */
  readonly code: string;
  readonly message: string;
  /** The offending cell as written. */
  readonly value?: string;
  /** The network element a stage-2 finding is about - a node name, or `A → B` for a link. */
  readonly element?: string;
}

/**
 * `POST /networks/import`: the two-stage validation report, and the network if one was created.
 *
 * `valid` and `committed` are different questions. A dry run that finds nothing wrong is valid and not
 * committed - that is exactly step 3 of the wizard, which shows the report before the user confirms.
 * Imports are transactional, so a network exists only when `committed` is true.
 */
export interface ImportReport {
  readonly valid: boolean;
  readonly committed: boolean;
  readonly networkId?: Id;
  /** The created network, so the wizard can open the editor without a second request. */
  readonly network?: Network;
  readonly timeBase: ImportTimeBase;
  /**
   * The resolution findings under that clock, run in IMPORT context - where a duration
   * that converts to zero periods is an error rather than a warning. Also carries `suggestedPeriod`,
   * which is the remedy to offer.
   */
  readonly timeValidation?: TimeValidationReport;
  readonly rowsRead: Readonly<Record<string, number>>;
  readonly errorCount: number;
  readonly warningCount: number;
  /** False when row errors dropped rows before the network-level checks ran. */
  readonly graphComplete: boolean;
  readonly diagnostics: readonly ImportDiagnostic[];
}

/**
 * Format of `GET /networks/{id}/export`.
 *
 * `xlsx` to edit a network by hand and re-import it, `csv` to diff two variants as text, `xml` to
 * archive or send one - the interchange document is a single self-describing file that
 * needs no column mapping on the way back in. All three carry the units and the canvas layout.
 */
export const ExportFormat = {
  XLSX: 'xlsx',
  CSV: 'csv',
  XML: 'xml',
} as const;

export type ExportFormat = (typeof ExportFormat)[keyof typeof ExportFormat];

/**
 * The two formats a *table* can be exported in - run results and the comparison matrix.
 *
 * One fewer than {@link ExportFormat}, and the missing one is the point. XML is the interchange
 * document: a network archived whole, and re-importable. A metric table has no
 * interchange format and no way back in, so offering `xml` there would produce a document nothing
 * can read.
 */
export type TabularExportFormat = 'xlsx' | 'csv';
