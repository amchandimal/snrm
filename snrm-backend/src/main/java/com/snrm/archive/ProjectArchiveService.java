package com.snrm.archive;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.snrm.archive.ProjectBundle.Counts;
import com.snrm.archive.ProjectBundle.ElementRef;
import com.snrm.archive.ProjectBundle.EventRecord;
import com.snrm.archive.ProjectBundle.Manifest;
import com.snrm.archive.ProjectBundle.MetricRecord;
import com.snrm.archive.ProjectBundle.NetworkRecord;
import com.snrm.archive.ProjectBundle.ProductRecord;
import com.snrm.archive.ProjectBundle.ProjectRecord;
import com.snrm.archive.ProjectBundle.RunRecord;
import com.snrm.archive.ProjectBundle.ScenarioRecord;
import com.snrm.archive.ProjectBundle.Selection;
import com.snrm.archive.ProjectBundle.TimeseriesRecord;
import com.snrm.archive.ProjectBundle.VariantRecord;
import com.snrm.dataimport.NetworkExportService;
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
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a whole experiment out as one archive.
 *
 * <blockquote>"a project export/import (JSON bundle) so an entire experiment is archivable alongside
 * the thesis"</blockquote>
 *
 * <p>The five decisions this implements are argued in {@code package-info.java}. Two of them shape
 * every method here.
 *
 * <p><strong>Networks are not serialised.</strong> Each one is written by
 * {@link NetworkExportService} as the XML document — the same bytes
 * {@code GET /networks/{id}/export?format=xml} returns — and the bundle only records its name, its
 * version, whether it is the baseline, and which entry holds it. There is no second structural
 * format to keep in step with {@code ImportSchema}.
 *
 * <p><strong>No id leaves this class as a reference.</strong> {@link #nodeRefs} and
 * {@link #linkRefs} build the two lookups that turn a {@code node.id} or {@code link.id} into a
 * network key and a name, and every event target and every scoped metric result goes through them.
 * The ids that remain in the bundle are all named {@code source*} and are provenance for a human
 * reader — nothing on the import side resolves them.
 *
 * <p><strong>Only completed runs.</strong> A {@code FAILED}, {@code CANCELLED} or still-{@code QUEUED}
 * run has no results, and the rule that results are interpretable only beside the structure
 * that produced them cuts both ways: there is nothing to interpret. Their absence is not a loss the
 * report needs to mention, because nothing was lost.
 *
 * <h2>Narrowing it to some of the networks (FR-24)</h2>
 *
 * <p>{@link #archive(long, long, List)} takes an optional selection and writes the same archive
 * narrowed to it, so restoring the file yields a separate project holding exactly those networks.
 * <strong>Omitted, this class behaves exactly as it did before subsets existed</strong> — the
 * selection travels only as {@link Selection}, which is null on the whole-project path and therefore
 * not written at all under {@code default-property-inclusion=non_null}. That is the one property the
 * subset feature must not cost: the whole-project archive is the reproducibility artefact
 * and it does not get to change shape because a second caller appeared.
 *
 * <p><strong>It copies and never moves.</strong> The whole class is
 * {@code @Transactional(readOnly = true)} and holds no writer of any kind: the selected networks,
 * their runs and their results stay exactly where they were computed. {@code ProjectSubsetArchiveTest}
 * asserts it rather than leaving it to the annotation.
 *
 * <p><strong>Three rules decide what a subset carries, and each is the safe answer rather than the
 * tidy one</strong> (FR-24). Each is argued at the statement that implements it:
 *
 * <ol>
 *   <li>The <strong>whole product catalogue</strong> travels — see {@code productRecords} below.</li>
 *   <li><strong>Every scenario</strong> travels, and an event pointing into a network that did not
 *       is narrowed to a dangling reference by {@link #narrow}.</li>
 *   <li>A <strong>variant edge whose base network is not selected is dropped</strong>, and
 *       <em>counted</em> — see the variant loop.</li>
 * </ol>
 */
@Service
public class ProjectArchiveService {

    /** Names the tool in the manifest, so a bundle found on its own identifies itself. */
    private static final String APPLICATION = "SNRM — Supply Network Resilience Modelling tool";

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
    private final NetworkExportService networkExports;
    private final ObjectMapper json;

    ProjectArchiveService(ProjectRepository projects, NetworkRepository networks,
            ProductRepository products, ConfigurationVariantRepository variants,
            DisruptionScenarioRepository scenarios, SimulationRunRepository runs,
            MetricResultRepository metrics, RunTimeseriesRepository timeseries,
            NodeRepository nodes, LinkRepository links, NetworkExportService networkExports,
            ObjectMapper json) {
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
        this.networkExports = networkExports;
        this.json = json;
    }

    /** An archive, ready to be handed to the client. */
    public record Archive(String filename, String contentType, byte[] content) {
    }

    /**
     * Archives a whole project.
     *
     * @param projectId the project to write
     * @param ownerId   the calling researcher
     * @throws EntityNotFoundException if the project is not the caller's
     */
    @Transactional(readOnly = true)
    public Archive archive(long projectId, long ownerId) {
        return archive(projectId, ownerId, null);
    }

    /**
     * Archives a project, optionally narrowed to some of its networks (FR-24).
     *
     * <p><strong>A copy, never a move.</strong> This method reads and writes a file; it changes
     * nothing. The selected networks, their runs and their results stay in the project that computed
     * them, and the source project is exactly as it was afterwards.
     *
     * @param projectId  the project to write
     * @param ownerId    the calling researcher
     * @param networkIds the networks to carry, or null/empty for the whole project. Null and empty
     *                   are the <em>only</em> whole-project forms: a request that names every
     *                   network explicitly is still a subset request and is recorded as one, because
     *                   "the caller asked for a selection" is a fact about the archive and a rule
     *                   that depended on the selection's size would be one nobody could predict
     * @throws EntityNotFoundException  if the project is not the caller's, or a named network is not
     *                                  in it
     * @throws IllegalArgumentException if {@code networkIds} is present but names no network
     */
    @Transactional(readOnly = true)
    public Archive archive(long projectId, long ownerId, List<Long> networkIds) {
        Project project = requireProject(projectId, ownerId);
        Instant exportedAt = Instant.now();

        // Every network of the project, selected or not. The two name lookups below are built over
        // all of them deliberately: that is what lets an event target excluded *by this selection*
        // be told apart from one that was already dangling before it, which are two different
        // findings for the researcher who restores the file. It costs a subset export the same
        // element reads a whole-project export already pays.
        List<Network> projectNetworks = networks.findByProjectIdOrderByNameAscVersionAsc(projectId);
        Map<Long, String> keysById = new LinkedHashMap<>();
        for (Network network : projectNetworks) {
            keysById.put(network.getId(), ArchiveSchema.networkKey(network.getName(),
                    network.getVersion()));
        }

        boolean subset = networkIds != null && !networkIds.isEmpty();
        List<Network> networkRows = select(project, projectNetworks, networkIds);
        Set<String> selectedKeys = new LinkedHashSet<>();
        for (Network network : networkRows) {
            selectedKeys.add(keysById.get(network.getId()));
        }

        // The two name lookups every reference in the bundle is written through. Read per network so
        // the query count is two per network rather than one per event or per metric row.
        Map<Long, ElementRef> nodeRefs = nodeRefs(projectNetworks, keysById);
        Map<Long, ElementRef> linkRefs = linkRefs(projectNetworks, keysById);

        List<NetworkRecord> networkRecords = new ArrayList<>(networkRows.size());
        Map<String, byte[]> documents = new LinkedHashMap<>();
        int ordinal = 0;
        for (Network network : networkRows) {
            String key = keysById.get(network.getId());
            String entry = ArchiveSchema.networkEntry(++ordinal, key);
            // Through the ordinary exporter: the archive carries the same document the network
            // endpoint serves, so an entry can be pulled out of the zip and imported on its own.
            documents.put(entry, networkExports
                    .export(network.getId(), ownerId, NetworkExportService.Format.XML).content());
            networkRecords.add(new NetworkRecord(key, network.getName(), network.getVersion(),
                    network.isBaseline(), entry, network.getId()));
        }

        // FR-24, first carry rule: the WHOLE catalogue travels, subset or not — which is why this
        // statement is unchanged by the selection and is meant to stay that way. A product is
        // project-scoped and a `node_product` row names one by *name*, so computing the
        // used subset means computing it right every time or writing a reference nothing resolves.
        // An unused catalogue entry in the restored project is harmless; a dangling one is not.
        List<ProductRecord> productRecords = new ArrayList<>();
        for (Product product : products.findByProjectIdOrderByNameAsc(projectId)) {
            productRecords.add(new ProductRecord(product.getName(), product.getUnitValue()));
        }

        List<VariantRecord> variantRecords = new ArrayList<>();
        int droppedVariantEdges = 0;
        for (ConfigurationVariant variant : variants.findByProjectIdOrderByIdDesc(projectId)) {
            String networkKey = keysById.get(variant.getNetwork().getId());
            String baseKey = keysById.get(variant.getBaseNetwork().getId());
            if (networkKey == null || baseKey == null) {
                continue; // both ends are in this project by construction; a dangling row is not
            }
            if (!selectedKeys.contains(networkKey)) {
                // The forked network itself did not travel, so nothing that did lost anything. Not
                // a drop worth counting: the count exists to warn about a *gap in what arrived*.
                continue;
            }
            if (!selectedKeys.contains(baseKey)) {
                // FR-24, third carry rule: a variant edge whose base network is not selected is
                // dropped — its fork note has no parent to hang from — and COUNTED rather than
                // passed over. `ProjectRestoreService.restoreVariants` skips an unresolvable key
                // silently and is right to, which is precisely why the count has to be taken here:
                // "Baseline v3 was forked from something" is provenance a reader would otherwise
                // find simply absent, with nothing to say it ever existed.
                droppedVariantEdges++;
                continue;
            }
            variantRecords.add(new VariantRecord(networkKey, baseKey, variant.getGeneratedBy(),
                    tree(variant.getLeverChangesJson())));
        }

        // FR-24, second carry rule: EVERY scenario travels. A scenario is project-scoped and
        // replayable across variants, so which networks were selected is not a fact about
        // which scenarios are worth keeping. See `narrow` for what happens to an event pointing at
        // a network that did not travel.
        List<ScenarioRecord> scenarioRecords = new ArrayList<>();
        int eventCount = 0;
        int excludedEventTargets = 0;
        for (DisruptionScenario scenario : scenarios.findByProjectIdOrderByNameAsc(projectId)) {
            List<EventRecord> events = new ArrayList<>();
            for (DisruptionEvent event : scenario.getEvents()) {
                ElementRef resolved = targetRef(event, nodeRefs, linkRefs);
                ElementRef target = narrow(resolved, event.getTargetId(), selectedKeys);
                if (target != resolved) {
                    excludedEventTargets++;
                }
                events.add(new EventRecord(
                        event.getTargetType(),
                        target,
                        event.getStartOffset().getValue(), event.getStartOffset().getUnit(),
                        event.getDuration().getValue(), event.getDuration().getUnit(),
                        event.getSeverity(), event.getRecoveryProfile(), event.getProbability(),
                        event.getId()));
            }
            eventCount += events.size();
            scenarioRecords.add(new ScenarioRecord(scenario.getName(), scenario.getNumReplications(),
                    scenario.getSeed(), scenario.getId(), events));
        }

        // Runs are already per network, so a subset needs no rule of its own: the selected networks'
        // DONE runs travel with their metric suites and their curves, exactly as before. A run of an
        // excluded network has no network to be interpreted beside, which is the same
        // reason a FAILED run is left out.
        List<RunRecord> runRecords = new ArrayList<>();
        int metricCount = 0;
        int seriesCount = 0;
        for (Network network : networkRows) {
            String key = keysById.get(network.getId());
            for (SimulationRun run : runs.findByNetworkIdOrderByIdDesc(network.getId())) {
                if (run.getStatus() != SimulationStatus.DONE) {
                    continue;
                }
                List<MetricRecord> runMetrics = metricRecords(metrics.findByRunId(run.getId()),
                        keysById, nodeRefs, linkRefs);
                List<TimeseriesRecord> series = new ArrayList<>();
                for (RunTimeseries row : timeseries.findSeries(run.getId())) {
                    series.add(new TimeseriesRecord(row.getPeriod(), row.getServedDemand(),
                            row.getTotalDemand(), row.getCost(), row.getBaselineServedDemand(),
                            row.getBaselineCost()));
                }
                metricCount += runMetrics.size();
                seriesCount += series.size();
                // A baseline run (FR-17) has no scenario; the record carries null and the restore
                // reads it back as a baseline run rather than resolving a name.
                runRecords.add(new RunRecord(run.getId(), key,
                        run.getScenario() == null ? null : run.getScenario().getName(),
                        instant(run.getStartedAt()), instant(run.getFinishedAt()),
                        tree(run.getParamsJson()), runMetrics, series));
            }
        }

        // Topological results, which belong to the network rather than to any run.
        // Carried so the comparison view renders straight after a restore rather than
        // needing a recompute — and so a recompute that disagrees with them is visible as a
        // disagreement, which is the more useful failure.
        List<MetricRecord> networkMetrics = new ArrayList<>();
        for (Network network : networkRows) {
            networkMetrics.addAll(metricRecords(
                    metrics.findByNetworkIdAndRunIsNull(network.getId()), keysById, nodeRefs,
                    linkRefs));
        }
        metricCount += networkMetrics.size();

        Counts counts = new Counts(networkRecords.size(), productRecords.size(),
                scenarioRecords.size(), eventCount, runRecords.size(), metricCount, seriesCount);
        // Null on the whole-project path, so `non_null` inclusion drops the member entirely and the
        // document is the one this tool has always written (see the class note).
        Selection selection = subset
                ? new Selection(networkRecords.size(), projectNetworks.size(),
                        List.copyOf(selectedKeys), droppedVariantEdges, excludedEventTargets)
                : null;
        Manifest manifest = new Manifest(exportedAt.toString(), APPLICATION,
                SimulationParams.ENGINE_VERSION, project.getId(), counts, selection);
        ProjectBundle bundle = new ProjectBundle(ArchiveSchema.FORMAT_VERSION, manifest,
                new ProjectRecord(project.getName(), project.getId()), productRecords,
                networkRecords, variantRecords, scenarioRecords, runRecords, networkMetrics);

        byte[] content = zip(bundle, documents, exportedAt);
        String safeName = ArchiveSchema.safeFilename(project.getName());
        String filename = subset
                ? ArchiveSchema.SUBSET_FILENAME_PATTERN.formatted(safeName, networkRecords.size())
                : ArchiveSchema.FILENAME_PATTERN.formatted(safeName);
        return new Archive(filename, ArchiveSchema.CONTENT_TYPE, content);
    }

    // ------------------------------------------------------------------- the selection

    /**
     * The networks that travel, in the project's own order (FR-24).
     *
     * <p><strong>A named network that is not in this project is refused, never skipped.</strong>
     * Skipping it would write a smaller archive than the caller asked for, which restores into a
     * project quietly missing a configuration — and the omission would be discovered, if at all,
     * beside a comparison view with a column that should be there and is not. A refusal names the
     * offending id and nothing is written.
     *
     * <p>Project order rather than request order, unlike {@code ComparisonService.resolveColumns}
     * beside it, and the difference is the point: a comparison's column order is a presentational
     * decision the caller made and the server has no business overriding, whereas an archive has no
     * presentation at all — {@code ProjectRestoreService} sorts the networks by name and version
     * before restoring them. Honouring a request order here would change the bytes of the file
     * without changing anything a reader of it can see.
     *
     * @throws EntityNotFoundException  if a named network is not in this project — including one
     *                                  that exists elsewhere, which is the same answer
     *                                  {@code ComparisonService} and {@code NetworkLookup} give
     * @throws IllegalArgumentException if the parameter is present but names nothing
     */
    private static List<Network> select(Project project, List<Network> projectNetworks,
            List<Long> networkIds) {

        if (networkIds == null || networkIds.isEmpty()) {
            return projectNetworks;
        }
        Set<Long> present = new LinkedHashSet<>();
        for (Network network : projectNetworks) {
            present.add(network.getId());
        }
        Set<Long> wanted = new LinkedHashSet<>();
        for (Long id : networkIds) {
            if (id == null) {
                continue;
            }
            if (!present.contains(id)) {
                throw new EntityNotFoundException(("No network with id %d in project %d. Nothing "
                        + "was written: a named network that is not in this project is refused "
                        + "rather than skipped, because a silently smaller archive restores into a "
                        + "project that is quietly missing a configuration (FR-24).")
                        .formatted(id, project.getId()));
            }
            wanted.add(id);
        }
        if (wanted.isEmpty()) {
            throw new IllegalArgumentException("networkIds was given but names no network. Omit "
                    + "the parameter altogether to archive the whole project (FR-24); an "
                    + "empty selection is refused rather than read as 'everything', because the "
                    + "two requests mean opposite things.");
        }
        return projectNetworks.stream().filter(network -> wanted.contains(network.getId())).toList();
    }

    /**
     * An event target that resolves into a network the selection left behind, made dangling.
     *
     * <p>FR-24's second carry rule, and the reason it is the safe answer rather than the tidy one.
     * Every scenario travels; an event inside one may point at a node or a link of a network that
     * did not. Writing the reference anyway would resolve to nothing on the way back in; writing
     * <em>no</em> event would leave a scenario that runs cleanly with one disruption missing, which
     * is the false negative a resilience study cannot afford. So it travels as
     * {@link ElementRef#unresolved}, the same shape a target that was <em>already</em> dangling when
     * archived takes — and {@code ProjectRestoreService.danglingTarget} restores both to a negative
     * {@code target_id} that no generated id can collide with. The scenario is visible, structurally
     * intact, and refuses a submission with {@code EVENT_TARGET_UNRESOLVED} until its target is
     * repointed. A scenario that visibly refuses to run beats one that runs with a disruption
     * silently missing.
     *
     * <p>Returns the argument unchanged — by identity, which is what the caller counts on — for a
     * region target, a target already unresolved, and every target on the whole-project path, where
     * {@code selectedKeys} holds every network of the project.
     */
    private static ElementRef narrow(ElementRef target, Long targetId, Set<String> selectedKeys) {
        String networkKey = target.networkKey();
        if (networkKey == null || selectedKeys.contains(networkKey)) {
            return target;
        }
        return ElementRef.unresolved(targetId);
    }

    // ------------------------------------------------------------------- references

    /**
     * Every node in the project, keyed by id, as the reference a bundle carries.
     *
     * <p>{@code uq_node (network_id, name)} is what makes the pair (network key, node name) an
     * identity rather than a hint.
     */
    private Map<Long, ElementRef> nodeRefs(List<Network> networkRows, Map<Long, String> keysById) {
        Map<Long, ElementRef> refs = new LinkedHashMap<>();
        for (Network network : networkRows) {
            String key = keysById.get(network.getId());
            for (Node node : nodes.findByNetworkId(network.getId())) {
                refs.put(node.getId(), ElementRef.node(key, node.getName()));
            }
        }
        return refs;
    }

    /** Every link in the project, keyed by id, named by its two endpoints as the XML names it. */
    private Map<Long, ElementRef> linkRefs(List<Network> networkRows, Map<Long, String> keysById) {
        Map<Long, ElementRef> refs = new LinkedHashMap<>();
        for (Network network : networkRows) {
            String key = keysById.get(network.getId());
            for (Link link : links.findByNetworkId(network.getId())) {
                refs.put(link.getId(), ElementRef.link(key, link.getSourceNode().getName(),
                        link.getTargetNode().getName()));
            }
        }
        return refs;
    }

    /**
     * An event's target as a name.
     *
     * <p>A {@code REGION} event already names a {@code node.region} tag, which is a string and needs
     * no resolution — that is precisely why {@code V5__event_region_target.sql} gave it its own
     * column. The two id-based types are looked up, and a target that resolves to nothing is
     * recorded as unresolved rather than dropped: {@code disruption_event.target_id} carries no
     * foreign key by design, so a node deleted before its scenario leaves exactly this.
     */
    private static ElementRef targetRef(DisruptionEvent event, Map<Long, ElementRef> nodeRefs,
            Map<Long, ElementRef> linkRefs) {

        if (event.getTargetType() == DisruptionTargetType.REGION) {
            return ElementRef.region(event.getTargetRegion());
        }
        Map<Long, ElementRef> source = event.getTargetType() == DisruptionTargetType.NODE
                ? nodeRefs
                : linkRefs;
        ElementRef resolved = source.get(event.getTargetId());
        return resolved != null ? resolved : ElementRef.unresolved(event.getTargetId());
    }

    /** Metric rows with their scope turned into a name ({@code METRIC_RESULT.scope_id}). */
    private static List<MetricRecord> metricRecords(List<MetricResult> rows,
            Map<Long, String> keysById, Map<Long, ElementRef> nodeRefs,
            Map<Long, ElementRef> linkRefs) {

        List<MetricRecord> records = new ArrayList<>(rows.size());
        for (MetricResult row : rows) {
            ElementRef scopeRef = null;
            if (row.getScope() == MetricScope.NODE) {
                scopeRef = nodeRefs.getOrDefault(row.getScopeId(),
                        ElementRef.unresolved(row.getScopeId()));
            } else if (row.getScope() == MetricScope.LINK) {
                scopeRef = linkRefs.getOrDefault(row.getScopeId(),
                        ElementRef.unresolved(row.getScopeId()));
            }
            records.add(new MetricRecord(keysById.get(row.getNetwork().getId()),
                    row.getMetricCode(), row.getScope(), scopeRef, row.getValue(), row.getCiLow(),
                    row.getCiHigh(), row.getDisplayUnit()));
        }
        return records;
    }

    // ---------------------------------------------------------------------- writing

    /**
     * The zip: {@code bundle.json} first, then one XML document per network.
     *
     * <p>The manifest is written first deliberately — a reader streaming the archive can learn what
     * it is holding, and refuse an unknown format version, before it has read a network.
     */
    private byte[] zip(ProjectBundle bundle, Map<String, byte[]> documents, Instant exportedAt) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
                ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {

            zip.putNextEntry(entry(ArchiveSchema.BUNDLE_ENTRY, exportedAt));
            // Pretty-printed: an archive that sits beside a thesis is read and diffed by people, and
            // the instance's own spring.jackson.indent-output setting is about API responses.
            zip.write(json.writerWithDefaultPrettyPrinter().writeValueAsBytes(bundle));
            zip.closeEntry();

            for (Map.Entry<String, byte[]> document : documents.entrySet()) {
                zip.putNextEntry(entry(document.getKey(), exportedAt));
                zip.write(document.getValue());
                zip.closeEntry();
            }
            zip.finish();
            return out.toByteArray();
        } catch (IOException failure) {
            // Writing to a byte array cannot fail for any reason the caller can act on.
            throw new UncheckedIOException("Could not write the project archive", failure);
        }
    }

    /** One entry, timestamped with the export rather than with the clock at the moment of writing. */
    private static ZipEntry entry(String name, Instant exportedAt) {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(exportedAt.toEpochMilli());
        return entry;
    }

    /**
     * A {@code JSON} column as a tree, so the bundle carries readable JSON rather than a quoted blob.
     *
     * <p>Null for an absent column. A column that will not parse is a corrupt row rather than a
     * caller's mistake, so it fails loudly here instead of being written into the archive as text
     * that the restore would then fail on.
     */
    private JsonNode tree(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return null;
        }
        try {
            return json.readTree(rawJson);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(
                    "A JSON column in this project does not contain valid JSON: " + rawJson,
                    failure);
        }
    }

    /** ISO-8601, or null — see {@link ProjectBundle} on why timestamps are strings. */
    private static String instant(Instant value) {
        return value == null ? null : value.toString();
    }

    private Project requireProject(long projectId, long ownerId) {
        return projects.findById(projectId)
                .filter(project -> project.getOwnerId() == ownerId)
                .orElseThrow(() -> new EntityNotFoundException("No project with id " + projectId));
    }
}
