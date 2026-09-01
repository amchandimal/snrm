/**
 * Project export and import — the archivable experiment.
 *
 * <blockquote>Flyway-versioned schema, seeded RNG, immutable completed runs, and a project
 * export/import (JSON bundle) so an entire experiment is archivable alongside the thesis.
 * </blockquote>
 *
 * <p>This is the mitigation against <em>models validated on synthetic or
 * single-case data</em>: the XML interchange document archives a network's
 * <em>inputs</em> — structure, units, layout — and nothing else. It carries no scenario, no run, no
 * parameter set, no seed, no metric result and no time series, so a reader handed the repository can
 * rebuild what was modelled but not what was found. A bundle written here carries both halves.
 *
 * <h2>What is in the archive</h2>
 *
 * <p>The project, its product catalogue, every network, every configuration variant's lineage, every
 * disruption scenario with its events, and every <strong>completed</strong> run with its
 * {@code params_json}, its metric results and its full time series. Runs that failed, were cancelled,
 * or are still queued are deliberately left out: a run's results are interpretable only
 * beside the structure that produced them, and a run with no results has nothing to interpret.
 *
 * <p>The archive can also be <strong>narrowed to some of the project's networks</strong>
 * (FR-24) — see decision 6. It is the same archive, restored by the same endpoint into the same kind
 * of new project; what changes is which networks travel, and three rules about what has to travel
 * with them regardless.
 *
 * <h2>Six decisions</h2>
 *
 * <p><strong>1. A network is archived as its own XML document, not as a second format.</strong>
 * {@link com.snrm.dataimport.NetworkExportService} already writes the full-fidelity interchange
 * document and {@link com.snrm.dataimport.ImportService} already reads it, through
 * validation that a bundle has no business skipping. Inventing a JSON spelling of a network would be
 * a second structural format to keep in step with {@link com.snrm.dataimport.ImportSchema} — the
 * exact failure {@code XmlSchema} exists to prevent — and it would let a bundle carry a network the
 * importer would have refused. So the archive is a zip: {@code bundle.json} for everything the XML
 * cannot express, and one {@code networks/*.xml} entry per network, byte-identical to what
 * {@code GET /networks/{id}/export?format=xml} returns.
 *
 * <p>This is that JSON bundle, wrapped in a zip. The manifest and every experiment-level
 * record <em>is</em> JSON; the networks sit beside it as the XML documents already defined rather
 * than as escaped strings inside it, which would put a thousand-node network on one unreadable line
 * and make the archive undiffable — defeating the point of a format meant to sit beside a thesis.
 * {@code NetworkExportService} takes the same shape for the same reason, a zip of CSV files.
 *
 * <p><strong>2. An imported run is re-created, never identity-preserving.</strong> A bundle restores
 * into a <em>new</em> project, and {@code simulation_run.id} is an {@code AUTO_INCREMENT} identity in
 * the receiving database. Preserving ids would mean forcing them past the generator, colliding with
 * whatever that instance already computed, and making the obvious second use of an archive —
 * importing it twice, to compare the archived result against a re-run — impossible. Every restored
 * row is a new row. What is preserved is the <em>reference</em>: {@code source_run_id} holds the id
 * the run had where it was computed, so a thesis citing run 42 stays navigable.
 *
 * <p><strong>3. A restored run says so.</strong> {@code simulation_run.imported_at} is non-null on
 * exactly the runs this instance did not compute ({@code V7__run_provenance.sql}, which argues the
 * column against the alternatives). It has to exist: a restored run is a genuine
 * {@link com.snrm.simulation.SimulationStatus#DONE} run — it freezes its network and
 * appears in the comparison view, both correct — so without the mark, a number computed on
 * another machine under another engine is indistinguishable from one this installation produced.
 * {@code SimulationRunDto} surfaces it on every read of a run.
 *
 * <p><strong>4. The bundle records the engine version, and a mismatch warns rather than
 * refuses.</strong> {@link com.snrm.simulation.SimulationParams#ENGINE_VERSION} exists because a
 * stored parameter set replays exactly only against the engine that wrote it. The manifest records
 * the exporting instance's version once, and each run's {@code params_json} — copied across, not
 * regenerated — keeps its own. On import the two are compared and any difference is reported
 * prominently. It is not a refusal, because an archive's whole purpose is to be legible later, and
 * "later" is precisely when the engine has moved on; refusing would break the mitigation exactly when
 * it is needed. What the mismatch forbids is comparison, not reading, and that is a judgement for the
 * researcher holding the thesis.
 *
 * <p>The <em>format</em> version is the opposite case and is enforced: an unknown
 * {@link com.snrm.archive.ArchiveSchema#FORMAT_VERSION} is refused outright, the same way
 * {@code XmlSchema.SCHEMA_VERSION} is, because a reader that cannot parse a document cannot warn
 * about it either.
 *
 * <p><strong>5. Every cross-row reference travels by name, never by id.</strong> A disruption event
 * targets {@code node.id} or {@code link.id}; a {@code NODE_CRITICALITY} result carries a
 * {@code scope_id}. None of those numbers survive the move to a new project. They are written as
 * {@link com.snrm.archive.ProjectBundle.ElementRef} — a network key plus a node name, or a link's two
 * endpoint names — and resolved back on the way in, which is the same identity the XML document uses
 * for its own links and the same one {@code uq_node (network_id, name)} guarantees. An event whose
 * target no longer resolves is reported rather than dropped, on both sides: dropping it would show a
 * restored network absorbing a disruption it never received, which is the false negative
 * {@code DisruptionScenarioService} refuses for the same reason.
 *
 * <p><strong>6. A subset of the networks is a copy of part of a project, and three rules decide what
 * has to travel with them.</strong> {@code GET /projects/{id}/archive?networkIds=…} (FR-24)
 * narrows the archive to a selection made on the project dashboard. Two properties frame it.
 * <em>Omitting the parameter changes nothing</em>: the whole-project archive is the reproducibility
 * artefact and it does not get to change shape because a second caller appeared, so the
 * selection travels as {@link com.snrm.archive.ProjectBundle.Selection}, which is null on that path
 * and therefore not written at all. And <em>it copies, never moves</em>: the selected networks,
 * their runs and their results stay in the project that computed them.
 *
 * <p>Each of the three rules is the safe answer rather than the tidy one, and the asymmetry is the
 * point — two of them carry <em>more</em> than the selection strictly needs, and the third carries
 * less but says so.
 *
 * <ul>
 *   <li><strong>The whole product catalogue travels.</strong> A product is project-scoped
 *       and a {@code node_product} row names one by name, which is decision 5 applied to the
 *       catalogue: computing the used subset means computing it correctly every time or writing a
 *       reference nothing resolves. The two failures are not comparable — an unused catalogue entry
 *       in the restored project is inert, a dangling one breaks an import.</li>
 *   <li><strong>Every scenario travels</strong>, because a scenario is project-scoped and replayable
 *       across variants, so which networks were selected says nothing about which scenarios are
 *       worth keeping. An event whose target sits in an excluded network is archived unresolved and
 *       restored through the negative-{@code target_id} convention decision 5 already argues for an
 *       <em>already</em>-dangling target — so the scenario stays visible, stays structurally intact,
 *       and refuses a submission with {@code EVENT_TARGET_UNRESOLVED}. A scenario that visibly
 *       refuses to run beats one that runs with a disruption silently missing, which is the same
 *       false negative {@code DisruptionScenarioService} refuses a region for.</li>
 *   <li><strong>A variant edge whose base network is not selected is dropped</strong> — its fork
 *       note has no parent to hang from — and the manifest <strong>counts</strong> it. The restore
 *       already skips an unresolvable lineage key silently and is right to; the count is what stops
 *       the silence being the whole story, and it becomes the {@code ARCHIVE_IS_SUBSET} finding.
 *       This one is a genuine loss rather than a deferral: what is missing is provenance, not a
 *       network, and the alternative — dragging the base network in unasked — would make the
 *       selection something other than what the user ticked.</li>
 * </ul>
 *
 * <p>A named network that is not in the project is <strong>refused, never skipped</strong>. A
 * silently smaller archive restores into a project quietly missing a configuration, which is
 * discovered — if at all — as an absent column in a comparison view.
 *
 * <h2>Boundaries</h2>
 *
 * <p>This package sits outside the module list — a deliberate deviation — because an
 * experiment is the one concept that spans every module: project,
 * network, scenario, simulation and metrics. Folding it into {@code dataimport} would give that
 * module, which owns network-level tabular import, a dependency on the simulation
 * engine and the metric store. Nothing here computes anything: it reads the persistence edge of each
 * module and writes it back, and every network it restores goes through the ordinary importer with
 * the ordinary validation.
 */
package com.snrm.archive;
