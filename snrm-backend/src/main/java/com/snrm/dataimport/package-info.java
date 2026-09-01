/**
 * CSV/XLSX network import and export: the {@link com.snrm.dataimport.DataSourceAdapter} SPI, its two
 * adapters, and the two-stage validation.
 *
 * <h2>The shape of an import</h2>
 *
 * <pre>
 *   POST /networks/import/preview  →  parse                           →  headers + proposed mapping
 *   POST /networks/import          →  parse → map → stage 1 → stage 2 →  report (+ the network)
 *   GET  /networks/{id}/export     →  the same schema, back out
 * </pre>
 *
 * <p>{@link com.snrm.dataimport.SourceReader} chooses between {@link com.snrm.dataimport.CsvAdapter} and
 * {@link com.snrm.dataimport.XlsxAdapter} and answers with {@link com.snrm.dataimport.SourceTable}s, so
 * nothing after it knows which form the upload took. {@link com.snrm.dataimport.MappedTable} resolves the
 * user's columns onto the canonical ones of {@link com.snrm.dataimport.ImportSchema}.
 * {@link com.snrm.dataimport.RowValidator} is stage 1 and produces a
 * {@link com.snrm.dataimport.StagedNetwork}; {@link com.snrm.dataimport.NetworkChecks} is stage 2 over
 * that graph, alongside {@code TimeValidationService.check} for the resolution rules.
 * {@link com.snrm.dataimport.ImportService} orchestrates, and writes exactly once, at the end, if nothing
 * is an error.
 *
 * <h2>Three invariants worth keeping</h2>
 *
 * <ul>
 *   <li><strong>One schema.</strong> {@link com.snrm.dataimport.ImportSchema} is the only list of column
 *       names in the system, and the export writes from it. A second list is how an export stops
 *       re-importing.</li>
 *   <li><strong>One time-validation implementation.</strong> The checks belong to
 *       {@code com.snrm.network.TimeValidationService}. This package calls it; it does not reimplement
 *       it, or the editor and the wizard would form different opinions about the same lead time.</li>
 *   <li><strong>Nothing is written until everything has been checked.</strong> The import is
 *       transactional, and the way that is achieved here is that persistence is the last step of one
 *       method rather than something undone by a rollback.</li>
 * </ul>
 *
 * <h2>An import can be a fork (FR-28)</h2>
 *
 * <p>{@code POST /networks/import} takes an optional {@code baseNetworkId}, and the network it
 * creates is then also a {@code CONFIGURATION_VARIANT} of that network — written by
 * {@link com.snrm.dataimport.ImportService} in the same transaction, one statement after the
 * network, so there is no observable moment in which an imported variant is a network with no edge
 * and no rollback that can leave an edge with no network. The base must be in the same project
 * ({@link com.snrm.dataimport.BaseNetworkScopeException}), and it is resolved before staging so a
 * dry run refuses a base it could not have linked to.
 *
 * <p>This is the whole backend of FR-28. <strong>A batch is the wizard sending N of these
 * requests</strong>, baseline first, and not a batch endpoint: the per-file transaction and the
 * per-file report the wizard needs are exactly what one call already is. Nothing here diffs two
 * networks, so an edge's note is what the request carried or nothing at all.
 *
 * <h2>Captions travel in all three formats (FR-30)</h2>
 *
 * <p>A node's and a link's {@code caption} and {@code caption_visible} are four entries in
 * {@link com.snrm.dataimport.ImportSchema} and four in {@link com.snrm.dataimport.XmlSchema}, and
 * that is the whole of it — import, export and the project archive move together because
 * they read the same two lists. The first invariant above is what makes that a change of four lines
 * rather than of three writers.
 *
 * <p><strong>Two omission rules, and they are not symmetric.</strong> An omitted {@code caption} is
 * null, the way every optional column is. An omitted {@code caption_visible} — or
 * {@code captionVisible} in the XML — is <em>visible</em>, not false: a file carrying only a caption
 * was written by someone who meant it to be seen, and false would import an annotation nobody can
 * find. A cell that is present but unreadable is an error rather than either
 * default, which is the rule {@link com.snrm.dataimport.UnitTokens} already applies to a unit token.
 */
package com.snrm.dataimport;
