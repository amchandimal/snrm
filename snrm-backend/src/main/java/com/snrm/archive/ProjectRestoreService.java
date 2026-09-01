package com.snrm.archive;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.snrm.archive.ProjectBundle.Counts;
import com.snrm.archive.ProjectBundle.ElementRef;
import com.snrm.archive.ProjectBundle.EventRecord;
import com.snrm.archive.ProjectBundle.MetricRecord;
import com.snrm.archive.ProjectBundle.NetworkRecord;
import com.snrm.archive.ProjectBundle.ProductRecord;
import com.snrm.archive.ProjectBundle.RunRecord;
import com.snrm.archive.ProjectBundle.ScenarioRecord;
import com.snrm.archive.ProjectBundle.TimeseriesRecord;
import com.snrm.archive.ProjectBundle.VariantRecord;
import com.snrm.archive.ArchiveReport.Finding;
import com.snrm.common.DurationAmount;
import com.snrm.dataimport.ImportDiagnostic;
import com.snrm.dataimport.ImportReport;
import com.snrm.dataimport.ImportRequest;
import com.snrm.dataimport.ImportService;
import com.snrm.dataimport.ImportSeverity;
import com.snrm.dataimport.UploadedFile;
import com.snrm.metrics.MetricResult;
import com.snrm.metrics.MetricResultRepository;
import com.snrm.metrics.MetricScope;
import com.snrm.network.ConfigurationVariant;
import com.snrm.network.ConfigurationVariantRepository;
import com.snrm.network.Link;
import com.snrm.network.LinkRepository;
import com.snrm.network.Network;
import com.snrm.network.NetworkRepository;
import com.snrm.network.Node;
import com.snrm.network.NodeRepository;
import com.snrm.network.Product;
import com.snrm.network.ProductRepository;
import com.snrm.project.Project;
import com.snrm.project.ProjectRepository;
import com.snrm.scenario.DisruptionEvent;
import com.snrm.scenario.DisruptionScenario;
import com.snrm.scenario.DisruptionScenarioRepository;
import com.snrm.scenario.DisruptionTargetType;
import com.snrm.simulation.RunTimeseries;
import com.snrm.simulation.RunTimeseriesRepository;
import com.snrm.simulation.SimulationParams;
import com.snrm.simulation.SimulationRun;
import com.snrm.simulation.SimulationRunRepository;
import com.snrm.simulation.SimulationStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Restores an archived experiment into a new project.
 *
 * <p>The whole restore is one transaction, for the reason {@code ImportService} gives for the same
 * choice: a half-restored experiment — networks without their runs, runs without their curves — is
 * worse than none, and it would be indistinguishable from a real experiment that had been abandoned
 * halfway. Nothing is written unless everything is.
 *
 * <p><strong>Always into a new project, never merged into an existing one.</strong> A merge would
 * have to answer what happens when the archive's "Baseline v1" meets a different network of the same
 * name — and every answer to that is either a silent overwrite or a silent rename of somebody's
 * results. A new project makes the archived experiment reproducible next to whatever is already
 * there, and the researcher can compare the two through the ordinary comparison view.
 *
 * <p><strong>Networks go through the ordinary importer.</strong> Each archived XML document is fed
 * to {@link ImportService}, so a restored network is parsed, mapped and validated exactly as a
 * hand-uploaded one — an archive cannot introduce a network the importer would have refused, and
 * there is no second write path for a network in this codebase.
 *
 * <p><strong>Order matters and is fixed here.</strong> Networks first (they create the products they
 * reference), then the rest of the catalogue, then variant lineage, then scenarios and their events,
 * then runs and their results. Every step after the first resolves names against what the previous
 * ones wrote, and a run is written last because it freezes its network — writing one
 * earlier would make the network immutable while the restore still had work to do on it.
 */
@Service
public class ProjectRestoreService {

    /** Appended when the owner already has a project of the archived name ({@code uq_project}). */
    private static final String RENAME_SUFFIX = " (restored)";

    /** Bound on entries read out of an uploaded zip, so a crafted archive cannot exhaust the heap. */
    private static final int MAX_ENTRIES = 10_000;

    private final ProjectRepository projects;
    private final NetworkRepository networks;
    private final ProductRepository products;
    private final ConfigurationVariantRepository variants;
    private final DisruptionScenarioRepository scenarios;
    private final SimulationRunRepository runs;
    private final MetricResultRepository metrics;
    private final RunTimeseriesRepository timeseries;
    private final NodeRepository nodes;
    private final LinkRepository links;
    private final ImportService imports;
    private final ObjectMapper json;

    ProjectRestoreService(ProjectRepository projects, NetworkRepository networks,
            ProductRepository products, ConfigurationVariantRepository variants,
            DisruptionScenarioRepository scenarios, SimulationRunRepository runs,
            MetricResultRepository metrics, RunTimeseriesRepository timeseries,
            NodeRepository nodes, LinkRepository links, ImportService imports, ObjectMapper json) {
        this.projects = projects;
        this.networks = networks;
        this.products = products;
        this.variants = variants;
        this.scenarios = scenarios;
        this.runs = runs;
        this.metrics = metrics;
        this.timeseries = timeseries;
        this.nodes = nodes;
        this.links = links;
        this.imports = imports;
        this.json = json;
    }

    /**
     * Restores an archive.
     *
     * @param content the uploaded zip
     * @param name    a name for the new project, or null to take the archived one
     * @param ownerId the calling researcher
     * @throws ArchiveFormatException if the upload is not a readable archive of a known format
     */
    @Transactional
    public ArchiveReport restore(byte[] content, String name, long ownerId) {
        Map<String, byte[]> entries = unzip(content);
        ProjectBundle bundle = parse(entries);
        List<Finding> findings = new ArrayList<>();

        Instant importedAt = Instant.now();
        Project project = createProject(bundle, name, ownerId, findings);

        // 1. Networks, oldest version first — a variant's version number is only meaningful in
        //    sequence, and the importer assigns the next free one as it goes.
        List<NetworkRecord> ordered = new ArrayList<>(nullToEmpty(bundle.networks()));
        ordered.sort(Comparator.comparing(NetworkRecord::name)
                .thenComparingInt(NetworkRecord::version));
        Map<String, Network> byKey = new LinkedHashMap<>();
        for (NetworkRecord record : ordered) {
            byKey.put(record.key(), restoreNetwork(record, entries, project, ownerId, findings));
        }

        // 2. Anything left in the catalogue. A network's XML carries only the products it references
        //    (NetworkExportService says so), so a product no network uses would otherwise be lost —
        //    and the catalogue is project-scoped and shared by every variant.
        restoreProducts(bundle.products(), project);

        // 3. Element lookups, built once over what the importer just wrote.
        Map<NodeKey, Node> nodesByRef = new LinkedHashMap<>();
        Map<LinkKey, Link> linksByRef = new LinkedHashMap<>();
        for (Map.Entry<String, Network> entry : byKey.entrySet()) {
            String key = entry.getKey();
            for (Node node : nodes.findByNetworkId(entry.getValue().getId())) {
                nodesByRef.put(NodeKey.of(key, node.getName()), node);
            }
            for (Link link : links.findByNetworkId(entry.getValue().getId())) {
                linksByRef.put(LinkKey.of(key, link.getSourceNode().getName(),
                        link.getTargetNode().getName()), link);
            }
        }

        restoreVariants(bundle.variants(), project, byKey);
        Map<String, DisruptionScenario> scenariosByName =
                restoreScenarios(bundle.scenarios(), project, nodesByRef, linksByRef, findings);
        RestoredRuns restored = restoreRuns(bundle.runs(), byKey, scenariosByName, nodesByRef,
                linksByRef, importedAt, findings);

        // 4. Topological results, which hang off a network rather than a run.
        int networkMetrics = restoreNetworkMetrics(bundle.networkMetrics(), byKey, nodesByRef,
                linksByRef, findings);

        // Said before the engine finding is inserted, so the engine one still ends up first: it
        // qualifies every number, where this qualifies what is present at all (FR-24).
        ProjectBundle.Selection selection =
                bundle.manifest() == null ? null : bundle.manifest().selection();
        if (selection != null) {
            findings.add(0, subsetFinding(selection));
        }

        String engineVersion = bundle.manifest() == null ? null : bundle.manifest().engineVersion();
        boolean engineMatches = SimulationParams.ENGINE_VERSION.equals(engineVersion);
        if (!engineMatches) {
            // First in the list, because it qualifies every number the restore just wrote.
            findings.add(0, new Finding(ArchiveReport.ENGINE_VERSION_MISMATCH, engineVersion,
                    ("The archived runs were produced by simulation engine %s and this installation "
                            + "runs %s. The results are restored and readable, and each run's "
                            + "params.engineVersion records which engine produced it — but a stored "
                            + "parameter set replays exactly only against the engine that wrote it. "
                            + "Re-run before comparing these results with anything "
                            + "computed here; a comparison across engine versions measures the "
                            + "engine as much as the network.")
                            .formatted(engineVersion == null ? "(unstated)" : engineVersion,
                                    SimulationParams.ENGINE_VERSION)));
        }

        int eventCount = 0;
        for (ScenarioRecord scenario : nullToEmpty(bundle.scenarios())) {
            eventCount += nullToEmpty(scenario.events()).size();
        }
        Counts restoredCounts = new Counts(byKey.size(),
                products.findByProjectIdOrderByNameAsc(project.getId()).size(),
                scenariosByName.size(), eventCount, restored.runs(),
                restored.metricResults() + networkMetrics, restored.timeseriesRows());

        return new ArchiveReport(project.getId(), project.getName(),
                bundle.manifest() == null ? null : bundle.manifest().counts(), restoredCounts,
                engineVersion, engineMatches, List.copyOf(findings));
    }

    // ---------------------------------------------------------------------- reading

    /**
     * The zip's entries, in memory.
     *
     * <p>Entry names are matched against what {@code bundle.json} points at and are never used as
     * filesystem paths, so the zip-slip class of bug has nowhere to land; {@link #MAX_ENTRIES} bounds
     * a crafted archive.
     */
    private static Map<String, byte[]> unzip(byte[] content) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                if (entries.size() >= MAX_ENTRIES) {
                    throw new ArchiveFormatException("This archive holds more than " + MAX_ENTRIES
                            + " entries, which no project export produces. Refused unread.");
                }
                entries.put(entry.getName(), zip.readAllBytes());
            }
        } catch (IOException malformed) {
            throw new ArchiveFormatException("The upload is not a readable zip archive. A project "
                    + "archive is the file GET /projects/{id}/archive returns.", malformed);
        }
        return entries;
    }

    /**
     * {@code bundle.json}, with the format version checked before anything else is bound.
     *
     * <p>Read as a tree first for two reasons. An unknown format version has to be refused with the
     * message {@link ArchiveFormatException#unknownVersion} gives rather than as whatever binding
     * error the unknown shape produces; and
     * {@code spring.jackson.deserialization.fail-on-unknown-properties=true} makes any field this
     * build does not know fatal, so the version check must not depend on binding succeeding.
     */
    private ProjectBundle parse(Map<String, byte[]> entries) {
        byte[] document = entries.get(ArchiveSchema.BUNDLE_ENTRY);
        if (document == null) {
            throw new ArchiveFormatException(("This archive has no '%s'. It is either not a project "
                    + "archive or it was repacked without its manifest; a network on its own is "
                    + "imported through POST /networks/import instead.")
                    .formatted(ArchiveSchema.BUNDLE_ENTRY));
        }
        JsonNode tree;
        try {
            tree = json.readTree(document);
        } catch (IOException malformed) {
            throw new ArchiveFormatException(
                    "'%s' is not valid JSON.".formatted(ArchiveSchema.BUNDLE_ENTRY), malformed);
        }
        JsonNode version = tree.get("formatVersion");
        if (version == null || !version.isInt()) {
            throw new ArchiveFormatException(("'%s' declares no formatVersion, so there is no way to "
                    + "know how to read it.").formatted(ArchiveSchema.BUNDLE_ENTRY));
        }
        if (version.intValue() != ArchiveSchema.FORMAT_VERSION) {
            throw ArchiveFormatException.unknownVersion(version.intValue());
        }
        try {
            return json.treeToValue(tree, ProjectBundle.class);
        } catch (JsonProcessingException malformed) {
            throw new ArchiveFormatException(("'%s' declares format version %d but does not have "
                    + "that shape: %s").formatted(ArchiveSchema.BUNDLE_ENTRY,
                    ArchiveSchema.FORMAT_VERSION, malformed.getOriginalMessage()), malformed);
        }
    }

    // -------------------------------------------------------------------- restoring

    /** The new project, renamed if this owner already has one by the archived name. */
    private Project createProject(ProjectBundle bundle, String requested, long ownerId,
            List<Finding> findings) {

        String archived = bundle.project() == null || bundle.project().name() == null
                ? "Restored project"
                : bundle.project().name();
        String wanted = requested != null && !requested.isBlank() ? requested.trim() : archived;

        String name = wanted;
        if (projects.existsByOwnerIdAndName(ownerId, name)) {
            name = wanted + RENAME_SUFFIX;
            for (int n = 2; projects.existsByOwnerIdAndName(ownerId, name); n++) {
                name = wanted + RENAME_SUFFIX + " " + n;
            }
            findings.add(new Finding(ArchiveReport.PROJECT_RENAMED, name,
                    ("A project named '%s' already belongs to this user, so the restore was named "
                            + "'%s'. Nothing in the existing project was read or changed — an "
                            + "archive is always restored alongside, never merged.")
                            .formatted(wanted, name)));
        }
        return projects.save(new Project(name, ownerId));
    }

    /**
     * One network, through the ordinary importer, keeping its archived version number.
     *
     * <p>The version is reasserted afterwards rather than requested up front because
     * {@code ImportService} owns version assignment — it takes the next free number under the name,
     * which is what keeps {@code uq_network} satisfied for a hand-uploaded file. In a project this
     * restore just created, the numbers it assigns agree with the archived ones unless the source
     * had a gap (a variant deleted after its successors were forked). Where they disagree the
     * archived number wins, because {@link VariantRecord} lineage and any citation of "Baseline v3"
     * are stated in those numbers — and the disagreement is reported.
     */
    private Network restoreNetwork(NetworkRecord record, Map<String, byte[]> entries,
            Project project, long ownerId, List<Finding> findings) {

        byte[] document = entries.get(record.xmlEntry());
        if (document == null) {
            throw new ArchiveFormatException(("The archive's bundle.json names network '%s' at entry "
                    + "'%s', which is not in the zip. The archive is incomplete.")
                    .formatted(record.key(), record.xmlEntry()));
        }

        UploadedFile file = new UploadedFile(record.name() + ".xml", "application/xml", document);
        // Deliberately the form that names no base network (FR-28). A restore re-creates variant
        // lineage in its own pass, in restoreVariants below, because an edge's two ends can arrive
        // in either order and its origin may be SEARCH rather than the MANUAL an import records.
        ImportRequest request = new ImportRequest(project.getId(), record.name(), record.baseline(),
                false, null, null, null, null);
        ImportReport report = imports.importNetworkFiles(List.of(file), request, ownerId);

        if (!report.committed()) {
            // An archive this tool wrote holds networks this tool validated, so this is a corrupt or
            // hand-edited bundle rather than a user's modelling mistake. The errors are quoted so the
            // failure is diagnosable without a second upload.
            throw new ArchiveFormatException(("Network '%s' in this archive did not pass import "
                    + "validation, so nothing was restored. %s")
                    .formatted(record.key(), errorsOf(report)));
        }

        Network network = networks.findById(report.networkId()).orElseThrow();
        if (network.getVersion() != record.version()) {
            findings.add(new Finding(ArchiveReport.NETWORK_VERSION_ADJUSTED, record.key(),
                    ("Network '%s' was archived as version %d and the import assigned version %d; "
                            + "the archived number has been restored. This happens when the source "
                            + "project had a gap in a name's version sequence, usually because a "
                            + "variant was deleted after later ones were forked from it.")
                            .formatted(record.name(), record.version(), network.getVersion())));
            network.setVersion(record.version());
        }
        return network;
    }

    /** Catalogue entries no network's XML brought in with it. */
    private void restoreProducts(List<ProductRecord> records, Project project) {
        if (records == null || records.isEmpty()) {
            return;
        }
        Map<String, Product> existing = new LinkedHashMap<>();
        for (Product product : products.findByProjectIdOrderByNameAsc(project.getId())) {
            existing.put(ArchiveSchema.key(product.getName()), product);
        }
        List<Product> created = new ArrayList<>();
        for (ProductRecord record : records) {
            if (!existing.containsKey(ArchiveSchema.key(record.name()))) {
                created.add(new Product(project, record.name(), record.unitValue()));
            }
        }
        if (!created.isEmpty()) {
            products.saveAll(created);
        }
    }

    /** Variant lineage ({@code CONFIGURATION_VARIANT}), which the XML cannot express. */
    private void restoreVariants(List<VariantRecord> records, Project project,
            Map<String, Network> byKey) {

        if (records == null) {
            return;
        }
        List<ConfigurationVariant> created = new ArrayList<>();
        for (VariantRecord record : records) {
            Network network = byKey.get(record.networkKey());
            Network base = byKey.get(record.baseNetworkKey());
            if (network == null || base == null) {
                continue; // both ends were archived together; a bundle naming a missing one is
                          // structurally impossible short of hand-editing, and lineage is provenance
            }
            created.add(new ConfigurationVariant(project, base, network, record.generatedBy(),
                    text(record.leverChanges())));
        }
        if (!created.isEmpty()) {
            variants.saveAll(created);
        }
    }

    /** Scenarios and their events, with every target resolved back from a name. */
    private Map<String, DisruptionScenario> restoreScenarios(List<ScenarioRecord> records,
            Project project, Map<NodeKey, Node> nodesByRef, Map<LinkKey, Link> linksByRef,
            List<Finding> findings) {

        Map<String, DisruptionScenario> byName = new LinkedHashMap<>();
        if (records == null) {
            return byName;
        }
        for (ScenarioRecord record : records) {
            DisruptionScenario scenario = new DisruptionScenario(project, record.name());
            scenario.setNumReplications(record.numReplications());
            scenario.setSeed(record.seed());

            for (EventRecord event : nullToEmpty(record.events())) {
                scenario.addEvent(restoreEvent(event, scenario, record.name(), nodesByRef,
                        linksByRef, findings));
            }
            byName.put(ArchiveSchema.key(record.name()), scenarios.save(scenario));
        }
        return byName;
    }

    /**
     * One event, with its target resolved from a name back to an id.
     *
     * <p>A target that does not resolve is <strong>kept</strong>, carrying the id it had in the
     * source instance, and reported. Dropping it would leave a scenario that runs cleanly and shows
     * a network absorbing a disruption it never received — the false negative
     * {@code DisruptionScenarioService} refuses a region for, and the one direction a resilience
     * study cannot afford to be wrong in. The kept id resolves to nothing here either, so a run that
     * uses this scenario is refused by {@code EVENT_TARGET_UNRESOLVED} at submission rather than
     * quietly producing a number.
     */
    private DisruptionEvent restoreEvent(EventRecord record, DisruptionScenario scenario,
            String scenarioName, Map<NodeKey, Node> nodesByRef, Map<LinkKey, Link> linksByRef,
            List<Finding> findings) {

        ElementRef target = record.target();
        Long targetId = null;
        String targetRegion = null;

        if (record.targetType() == DisruptionTargetType.REGION) {
            targetRegion = target == null ? null : target.region();
        } else if (target != null && !target.isUnresolved()) {
            if (record.targetType() == DisruptionTargetType.NODE) {
                Node node = nodesByRef.get(NodeKey.of(target.networkKey(), target.nodeName()));
                targetId = node == null ? null : node.getId();
            } else {
                Link link = linksByRef.get(LinkKey.of(target.networkKey(), target.linkSource(),
                        target.linkTarget()));
                targetId = link == null ? null : link.getId();
            }
        }

        if (record.targetType() != DisruptionTargetType.REGION && targetId == null) {
            targetId = danglingTarget(target);
            findings.add(unresolvedTarget(scenarioName, target, targetId));
        }

        DisruptionEvent event = new DisruptionEvent(scenario, record.targetType(), targetId,
                targetRegion,
                DurationAmount.of(record.startOffsetValue(), record.startOffsetUnit()),
                DurationAmount.of(record.durationValue(), record.durationUnit()),
                record.severity());
        event.setRecoveryProfile(record.recoveryProfile());
        event.setProbability(record.probability());
        return event;
    }

    /**
     * A {@code target_id} for an event whose target does not resolve, guaranteed to stay unresolved.
     *
     * <p>Three constraints meet here and only one value satisfies all of them. {@code ck_event_target}
     * requires a {@code NODE} or {@code LINK} event to carry a non-null {@code target_id}, so the
     * event cannot simply be written without one. Dropping the event instead would turn a scenario
     * that <em>refuses to run</em> in the source instance into one that runs happily with a
     * disruption missing — a network shown absorbing something it never received, which is the
     * failure {@code DisruptionScenarioService} refuses a region for. And writing the id the event
     * held in the source database is worse than either: {@code node.id} is {@code AUTO_INCREMENT} in
     * both instances, so a stale 42 has a real chance of naming a live node of the restored network
     * and silently retargeting the disruption at it.
     *
     * <p>A negative id can never collide, because no generated id is negative, and it keeps the
     * event visible and structurally intact. It carries the source id in its magnitude so the
     * original reference is still legible. Both halves of the fix are load-bearing: the event stays,
     * and it stays broken in exactly the way it was broken before.
     *
     * <p>How does an archive come to contain one? {@code NodeService.delete} says so outright —
     * scenario events targeting a node are "a soft reference and are listed to the user before the
     * delete rather than cascaded". Deleting a targeted node is a supported act that leaves
     * exactly this behind.
     */
    private static long danglingTarget(ElementRef target) {
        Long sourceId = target == null ? null : target.unresolvedSourceId();
        return sourceId == null || sourceId == 0 ? -1L : -Math.abs(sourceId);
    }

    private static Finding unresolvedTarget(String scenarioName, ElementRef target, long written) {
        return new Finding(ArchiveReport.EVENT_TARGET_UNRESOLVED, scenarioName,
                ("An event in scenario '%s' targets %s, which the restored project does not "
                        + "contain — it was already a dangling reference when the archive was "
                        + "written, which the editor permits: deleting a node lists the events "
                        + "targeting it rather than cascading to them. The event was kept rather "
                        + "than dropped, with target_id %d, a negative sentinel that cannot collide "
                        + "with any generated id. So it stays visible and stays unresolvable: a "
                        + "simulation submitted against this scenario is refused with "
                        + "EVENT_TARGET_UNRESOLVED until the target is repointed, exactly as it was "
                        + "in the source. Dropping the event would instead have let the run succeed "
                        + "with the disruption silently missing.")
                        .formatted(scenarioName,
                                target == null ? "an unnamed element" : target.describe(), written));
    }

    /**
     * What a subset archive did and did not bring with it (FR-24).
     *
     * <p>The restored project looks whole, and that is exactly the problem this sentence solves. A
     * catalogue holding entries no restored network uses, a scenario that refuses to run, a variant
     * with no recorded parent — each is a consequence of the selection rather than damage, and a
     * reader with only the project in front of them has no way to tell which. The counts come from
     * the manifest, taken at export where the excluded half was still visible; nothing here could
     * re-derive them.
     *
     * <p>The two conditional halves are printed only when they happened, because a sentence saying
     * "0 events were affected" is a sentence a reader learns to skip.
     */
    private static Finding subsetFinding(ProjectBundle.Selection selection) {
        int selected = selection.selectedNetworks();
        int total = selection.projectNetworks();

        StringBuilder message = new StringBuilder(("This archive holds %d of the source project's "
                + "%d networks — a selection made on the project dashboard (FR-24). It is a copy: "
                + "the source project still holds all %d, their runs and their results, unchanged. "
                + "The whole product catalogue travelled, so a catalogue entry no restored network "
                + "references is expected rather than a fault — a product is named by name on every "
                + "node_product row, and computing the used subset risks writing a reference "
                + "nothing resolves. Every disruption scenario travelled too, because a scenario is "
                + "project-scoped and replayable across variants.")
                .formatted(selected, total, total));

        if (selection.excludedEventTargets() > 0) {
            int events = selection.excludedEventTargets();
            message.append((" %d disruption %s a network that did not travel, and %s restored "
                    + "dangling — a negative target_id, the same convention as a target that was "
                    + "already dangling when archived. A simulation submitted against that scenario "
                    + "is refused with EVENT_TARGET_UNRESOLVED until the target is repointed, which "
                    + "is the intended outcome: a scenario that visibly refuses to run beats one "
                    + "that runs with a disruption silently missing. Each one is listed separately "
                    + "below.")
                    .formatted(events,
                            events == 1 ? "event targeted" : "events targeted",
                            events == 1 ? "was" : "were"));
        }

        if (selection.droppedVariantEdges() > 0) {
            int edges = selection.droppedVariantEdges();
            message.append((" %d configuration-variant fork %s dropped: the network %s forked from "
                    + "was not selected, so the note has no parent to hang from. The "
                    + "networks themselves are all here; what is missing is the record of what they "
                    + "were derived from, which the provenance tree draws.")
                    .formatted(edges,
                            edges == 1 ? "note was" : "notes were",
                            edges == 1 ? "it was" : "they were"));
        }

        return new Finding(ArchiveReport.ARCHIVE_IS_SUBSET,
                "%d of %d networks".formatted(selected, total), message.toString());
    }

    /** What {@link #restoreRuns} wrote, for the report's census. */
    private record RestoredRuns(int runs, int metricResults, int timeseriesRows) {
    }

    /** Completed runs, their metric suites and their curves. */
    private RestoredRuns restoreRuns(List<RunRecord> records, Map<String, Network> byKey,
            Map<String, DisruptionScenario> scenariosByName, Map<NodeKey, Node> nodesByRef,
            Map<LinkKey, Link> linksByRef, Instant importedAt, List<Finding> findings) {

        int runCount = 0;
        int metricCount = 0;
        int seriesCount = 0;

        for (RunRecord record : nullToEmpty(records)) {
            Network network = byKey.get(record.networkKey());
            // A null scenario name is a baseline run (FR-17): the run applied no scenario, so
            // there is nothing to resolve and nothing that can dangle.
            DisruptionScenario scenario = record.scenarioName() == null ? null
                    : scenariosByName.get(ArchiveSchema.key(record.scenarioName()));
            if (network == null || (record.scenarioName() != null && scenario == null)) {
                throw new ArchiveFormatException(("A run in this archive names network '%s' and "
                        + "scenario '%s', and one of them is not in the bundle. A run is only "
                        + "meaningful beside the structure that produced it, so nothing "
                        + "was restored.")
                        .formatted(record.networkKey(), record.scenarioName()));
            }

            if (record.params() == null || record.params().isNull()) {
                throw new ArchiveFormatException(("The run archived as id %s carries no parameter "
                        + "set. params_json is what makes a completed run replayable, and a "
                        + "result without it cannot be read back — nothing was "
                        + "restored.").formatted(record.sourceId()));
            }
            SimulationRun run = new SimulationRun(network, scenario, text(record.params()));
            run.setStatus(SimulationStatus.DONE);
            run.setStartedAt(instant(record.startedAt()));
            run.setFinishedAt(instant(record.finishedAt()));
            // The two provenance columns of V7 — the whole of decision 3 in package-info. A run
            // restored without these is indistinguishable from one this instance computed.
            run.setImportedAt(importedAt);
            run.setSourceRunId(record.sourceId());
            runs.save(run);
            runCount++;

            List<MetricResult> rows = new ArrayList<>();
            for (MetricRecord metric : nullToEmpty(record.metrics())) {
                rows.add(metricRow(metric, network, run, nodesByRef, linksByRef, findings));
            }
            if (!rows.isEmpty()) {
                metrics.saveAll(rows);
                metricCount += rows.size();
            }

            List<RunTimeseries> series = new ArrayList<>();
            for (TimeseriesRecord period : nullToEmpty(record.timeseries())) {
                RunTimeseries row = new RunTimeseries(run, period.period());
                row.setServedDemand(period.servedDemand());
                row.setTotalDemand(period.totalDemand());
                row.setCost(period.cost());
                row.setBaselineServedDemand(period.baselineServedDemand());
                row.setBaselineCost(period.baselineCost());
                series.add(row);
            }
            if (!series.isEmpty()) {
                timeseries.saveAll(series);
                seriesCount += series.size();
            }
        }
        return new RestoredRuns(runCount, metricCount, seriesCount);
    }

    /** Topological results, which belong to a network and have no run. */
    private int restoreNetworkMetrics(List<MetricRecord> records, Map<String, Network> byKey,
            Map<NodeKey, Node> nodesByRef, Map<LinkKey, Link> linksByRef, List<Finding> findings) {

        if (records == null || records.isEmpty()) {
            return 0;
        }
        List<MetricResult> rows = new ArrayList<>();
        for (MetricRecord record : records) {
            Network network = byKey.get(record.network());
            if (network == null) {
                continue;
            }
            rows.add(metricRow(record, network, null, nodesByRef, linksByRef, findings));
        }
        if (rows.isEmpty()) {
            return 0;
        }
        metrics.saveAll(rows);
        return rows.size();
    }

    /**
     * One metric row, with its scope resolved back from a name.
     *
     * <p>An unresolvable scope leaves {@code scope_id} null and is reported. The value itself is
     * still restored: a {@code NODE_CRITICALITY} figure whose node cannot be named is a number
     * without a label, which is worth less than the full row and much more than a silent gap in the
     * suite.
     */
    private MetricResult metricRow(MetricRecord record, Network network, SimulationRun run,
            Map<NodeKey, Node> nodesByRef, Map<LinkKey, Link> linksByRef, List<Finding> findings) {

        Long scopeId = null;
        if (record.scope() == MetricScope.NODE || record.scope() == MetricScope.LINK) {
            ElementRef ref = record.scopeRef();
            if (ref != null && !ref.isUnresolved()) {
                if (record.scope() == MetricScope.NODE) {
                    Node node = nodesByRef.get(NodeKey.of(ref.networkKey(), ref.nodeName()));
                    scopeId = node == null ? null : node.getId();
                } else {
                    Link link = linksByRef.get(LinkKey.of(ref.networkKey(), ref.linkSource(),
                            ref.linkTarget()));
                    scopeId = link == null ? null : link.getId();
                }
            }
            if (scopeId == null) {
                findings.add(new Finding(ArchiveReport.METRIC_SCOPE_UNRESOLVED, record.code(),
                        ("A %s-scoped %s result names %s, which the restored project does not "
                                + "contain. The value is restored without its scope, so it appears "
                                + "in the suite but cannot be attributed to an element.")
                                .formatted(record.scope(), record.code(),
                                        ref == null ? "no element" : ref.describe())));
            }
        }

        MetricResult row = new MetricResult(network, run, record.code(), record.scope(), scopeId,
                record.value());
        row.setCiLow(record.ciLow());
        row.setCiHigh(record.ciHigh());
        row.setDisplayUnit(record.displayUnit());
        return row;
    }

    // ----------------------------------------------------------------------- helpers

    /**
     * Lookup key for a node reference — case-insensitive, as every name match in the importer is.
     *
     * <p>A record rather than a concatenated string: network and node names are free text and both
     * routinely contain whatever separator would have been chosen, so {@code "A" + sep + "B C"} and
     * {@code "A" + sep + "B" + sep + "C"} are one bad name away from colliding.
     */
    private record NodeKey(String networkKey, String nodeName) {

        static NodeKey of(String networkKey, String nodeName) {
            return new NodeKey(ArchiveSchema.key(networkKey), ArchiveSchema.key(nodeName));
        }
    }

    /** The same for a link, named by its two endpoints as the XML document names it. */
    private record LinkKey(String networkKey, String source, String target) {

        static LinkKey of(String networkKey, String source, String target) {
            return new LinkKey(ArchiveSchema.key(networkKey), ArchiveSchema.key(source),
                    ArchiveSchema.key(target));
        }
    }

    /** A JSON tree back to the text a {@code JSON} column takes, or null. */
    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.toString();
    }

    /**
     * An absent list read as empty.
     *
     * <p>This service's own writer never omits one — an empty list serialises as {@code []}, since
     * {@code spring.jackson.default-property-inclusion=non_null} drops nulls and not empties. The
     * guard is for a bundle that was hand-edited between export and restore, which for an archival
     * format is a case to survive rather than to crash on.
     */
    private static <T> List<T> nullToEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }

    /**
     * An ISO-8601 string back to an instant.
     *
     * <p>A timestamp that will not parse is left null rather than failing the restore: it is
     * metadata about when a run executed, and losing it costs less than losing the run.
     */
    private static Instant instant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException malformed) {
            return null;
        }
    }

    /** The errors from a refused network import, for the exception message. */
    private static String errorsOf(ImportReport report) {
        List<String> messages = new ArrayList<>();
        for (ImportDiagnostic diagnostic : report.diagnostics()) {
            if (diagnostic.severity() == ImportSeverity.ERROR) {
                messages.add(diagnostic.message());
            }
        }
        return messages.isEmpty() ? "No error was reported, which should not happen."
                : String.join(" | ", messages);
    }
}
