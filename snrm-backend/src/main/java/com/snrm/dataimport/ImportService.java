package com.snrm.dataimport;

import com.snrm.dataimport.ImportPreview.CanonicalColumn;
import com.snrm.dataimport.ImportPreview.ColumnSuggestion;
import com.snrm.dataimport.ImportPreview.SheetPreview;
import com.snrm.dataimport.ImportSchema.ColumnSpec;
import com.snrm.dataimport.SourceTable.SourceRow;
import com.snrm.dataimport.StagedNetwork.StagedLink;
import com.snrm.dataimport.StagedNetwork.StagedNode;
import com.snrm.dataimport.StagedNetwork.StagedNodeProduct;
import com.snrm.dataimport.StagedNetwork.StagedProduct;
import com.snrm.dataimport.StagedNetwork.TimeBaseSpec;
import com.snrm.network.ConfigurationVariant;
import com.snrm.network.ConfigurationVariantMapper;
import com.snrm.network.ConfigurationVariantRepository;
import com.snrm.network.Link;
import com.snrm.network.MultipleBaselineException;
import com.snrm.network.Network;
import com.snrm.network.NetworkDto;
import com.snrm.network.NetworkLookup;
import com.snrm.network.NetworkMapper;
import com.snrm.network.NetworkRepository;
import com.snrm.network.Node;
import com.snrm.network.NodeProduct;
import com.snrm.network.Product;
import com.snrm.network.ProductRepository;
import com.snrm.network.VariantOrigin;
import com.snrm.network.TimeElementType;
import com.snrm.network.TimeFinding;
import com.snrm.network.TimeSeverity;
import com.snrm.network.TimeValidationContext;
import com.snrm.network.TimeValidationInput;
import com.snrm.network.TimeValidationInput.DeclaredDuration;
import com.snrm.network.TimeValidationReport;
import com.snrm.network.TimeValidationService;
import com.snrm.project.Project;
import com.snrm.project.ProjectRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The CSV/XLSX network import, end to end.
 *
 * <pre>
 *   parse (SourceReader)  →  map columns (MappedTable)  →  stage 1 rows (RowValidator)
 *                         →  stage 2 network (NetworkChecks + TimeValidationService)
 *                         →  persist, if and only if nothing is an error
 * </pre>
 *
 * <p><strong>Transactional in the strict sense.</strong> A network is created only if
 * validation passes, so no partially imported network can exist. Two things deliver that here, and both
 * are needed. The write happens in one place, at the end, after every check has run — so there is no
 * window in which half a network exists to be rolled back. And the method is {@code @Transactional}, so
 * a failure in the write itself (a schema constraint the service checks missed) takes the whole graph
 * with it rather than leaving nodes without their links. The variant edge of FR-28 is written inside
 * that same transaction for the same reason, one statement after the network it points at.
 *
 * <p><strong>A batch is N of these calls, not a batch method.</strong> FR-28 imports several
 * workbooks in one pass, and the wizard sequences them — baseline first, because a variant edge
 * needs its base to exist. Nothing here changes for a batch: the two-stage report is per network and
 * so is the transaction, which is exactly what lets eight workbooks with a bad row in the sixth
 * import seven and name the sixth. One file, one call, one outcome.
 *
 * <p><strong>Why stage 2 runs even when stage 1 failed.</strong> The obvious alternative is to gate it:
 * no row errors, then check the graph. It would be less useful and no safer. A researcher fixing a
 * fifty-node file wants both the bad cells and the unreachable customer in one pass, and gating means
 * discovering the structural problem only after a round of edits — while nothing is written either way,
 * because an error anywhere refuses the import. What the gate would buy is that stage-2 findings are
 * never provisional; that is bought instead by {@link StagedNetwork#complete()} and the single
 * {@code NETWORK_CHECKS_ON_PARTIAL_GRAPH} warning, which says so explicitly.
 *
 * <p><strong>The 5.5.4 checks are not reimplemented here.</strong> They run through
 * {@link TimeValidationService#check}, the same code path the editor's warning banner uses, in
 * {@link TimeValidationContext#IMPORT} — which is the one context where a duration that converts to zero
 * periods is an error rather than a warning. Their findings are mirrored into the row-level
 * table and carried whole in {@link ImportReport#timeValidation()}.
 */
@Service
public class ImportService {

    /** Rows shown in the wizard's mapping preview. Enough to recognise a column, short enough to read. */
    private static final int SAMPLE_ROWS = 5;

    /** Name of the product created when the {@code products} sheet is absent. */
    private static final String DEFAULT_PRODUCT_NAME = "Default product";

    private final SourceReader sourceReader;
    private final RowValidator rowValidator;
    private final NetworkChecks networkChecks;
    private final TimeValidationService timeValidation;
    private final ProjectRepository projects;
    private final NetworkRepository networks;
    private final ProductRepository products;
    private final ConfigurationVariantRepository variants;
    private final NetworkLookup lookup;
    private final NetworkMapper networkMapper;
    private final ConfigurationVariantMapper variantMapper;

    ImportService(SourceReader sourceReader, RowValidator rowValidator, NetworkChecks networkChecks,
            TimeValidationService timeValidation, ProjectRepository projects,
            NetworkRepository networks, ProductRepository products,
            ConfigurationVariantRepository variants, NetworkLookup lookup,
            NetworkMapper networkMapper, ConfigurationVariantMapper variantMapper) {
        this.sourceReader = sourceReader;
        this.rowValidator = rowValidator;
        this.networkChecks = networkChecks;
        this.timeValidation = timeValidation;
        this.projects = projects;
        this.networks = networks;
        this.products = products;
        this.variants = variants;
        this.lookup = lookup;
        this.networkMapper = networkMapper;
        this.variantMapper = variantMapper;
    }

    // --------------------------------------------------------------------- preview

    /**
     * Step 2 of the wizard: what the upload contains, and the mapping to propose.
     *
     * <p>Parses and nothing more — no rows are validated, because the columns they would be validated
     * against are what the user is about to choose. The mapping the client sends here is echoed through
     * {@link MappedTable}, so a user can adjust it and re-preview to see the effect before validating.
     */
    public ImportPreview preview(List<MultipartFile> files, ImportMapping mapping) {
        Diagnostics diagnostics = new Diagnostics();
        Map<ImportSheet, SourceTable> tables = sourceReader.read(files, diagnostics);
        Map<ImportSheet, MappedTable> mapped = map(tables, mapping);

        List<SheetPreview> sheets = new ArrayList<>();
        Map<String, Integer> rowsRead = new LinkedHashMap<>();
        for (ImportSheet sheet : ImportSheet.declarationOrder()) {
            MappedTable table = mapped.get(sheet);
            if (table == null) {
                continue;
            }
            sheets.add(previewOf(table));
            rowsRead.put(sheet.canonicalName(), table.rows().size());
            // The same column-level findings the import would raise, from the same code — so the
            // mapping step and the report step cannot disagree about whether a mapping is complete.
            rowValidator.reportColumnProblems(table, diagnostics);
        }

        // The time base only — the preview validates no rows, but it has to show the clock the import
        // would use, because the wizard has to ask for confirmation when that clock is a default.
        // Findings from reading it are collected here rather than discarded: a malformed
        // period_length_unit is exactly the kind of thing to see before mapping columns.
        MappedTable meta = mapped.get(ImportSheet.NETWORK_META);
        TimeBaseSpec timeBase = rowValidator.timeBaseOf(meta, null, diagnostics);
        return new ImportPreview(sheets, RowValidator.declaredNameOf(meta),
                ImportTimeBase.of(timeBase), schema(), rowsRead, diagnostics.sorted());
    }

    // ---------------------------------------------------------------------- import

    /**
     * Validates the upload and, unless this is a dry run, creates the network.
     *
     * @param files   the CSV set or the single workbook
     * @param request the project, name and the wizard's confirmed mapping and time base
     * @param ownerId the calling researcher
     * @throws EntityNotFoundException    if the project, or a named base network, is not the caller's
     * @throws MultipleBaselineException  if the import asks to be a baseline and the project has one
     * @throws BaseNetworkScopeException  if the named base network is in another project (FR-28)
     */
    @Transactional
    public ImportReport importNetwork(List<MultipartFile> files, ImportRequest request, long ownerId) {
        Diagnostics diagnostics = new Diagnostics();
        return importNetwork(sourceReader.read(files, diagnostics), request, ownerId, diagnostics);
    }

    /**
     * The same import, over content that never came through a request.
     *
     * <p>{@code com.snrm.archive} restores each network in a project archive by handing its stored
     * XML document to this method. The point of routing an archive through the ordinary importer
     * rather than writing entities directly is that a restored network is validated exactly as a
     * hand-uploaded one is: a bundle cannot smuggle in a network the importer would have refused,
     * and there is no second write path to keep in step with {@link #persist}.
     *
     * <p>Named apart from {@link #importNetwork(List, ImportRequest, long)} rather than overloading
     * it: generics erase, so two methods taking a {@code List} of different element types have the
     * same signature and will not compile side by side.
     *
     * @param files   the documents; for an archive, one XML file per network
     * @param request the project, name, baseline flag and any time-base override
     * @param ownerId the calling researcher
     */
    @Transactional
    public ImportReport importNetworkFiles(List<UploadedFile> files, ImportRequest request,
            long ownerId) {
        Diagnostics diagnostics = new Diagnostics();
        return importNetwork(sourceReader.readFiles(files, diagnostics), request, ownerId,
                diagnostics);
    }

    /** Everything after the parse, which is all both entry points have in common. */
    private ImportReport importNetwork(Map<ImportSheet, SourceTable> tables, ImportRequest request,
            long ownerId, Diagnostics diagnostics) {

        Project project = requireProject(request.projectId(), ownerId);
        // Before anything is staged, so a dry run refuses a base it could not have linked to and the
        // wizard's roles step is corrected before the batch commits its first file (FR-28).
        Network baseNetwork = requireBaseNetwork(request, project, ownerId);
        Map<ImportSheet, MappedTable> mapped = map(tables, request.mapping());

        Map<String, Product> existing = existingProducts(project);
        StagedNetwork staged = rowValidator.stage(mapped, request.timeBaseOverride(),
                existing.keySet(), diagnostics);

        // Stage 2: the graph, then the clock. Both over the staged network, neither touching the
        // database.
        diagnostics.addAll(networkChecks.check(staged));
        TimeValidationReport resolution = timeValidation.check(null, null, timeInput(staged),
                TimeValidationContext.IMPORT);
        diagnostics.addAll(mirror(resolution, staged));
        reportProductCatalogue(staged, mapped, existing, diagnostics);
        reportName(request, staged, diagnostics);

        Map<String, Integer> rowsRead = new LinkedHashMap<>();
        for (Map.Entry<ImportSheet, MappedTable> entry : mapped.entrySet()) {
            rowsRead.put(entry.getKey().canonicalName(), entry.getValue().rows().size());
        }

        boolean valid = !diagnostics.hasErrors();
        // The one branch that writes anything. A dry run creates no network, and therefore no
        // variant edge either — an edge whose network does not exist is not a lesser version of
        // this feature, it is a foreign key with nothing behind it (FR-28).
        NetworkDto created = valid && !request.validateOnly()
                ? persist(project, baseNetwork, request, staged, existing)
                : null;

        return new ImportReport(valid, created != null, created == null ? null : created.id(), created,
                ImportTimeBase.of(staged.timeBase()), resolution, rowsRead,
                diagnostics.errorCount(), diagnostics.warningCount(), staged.complete(),
                diagnostics.sorted());
    }

    // -------------------------------------------------------------------- persistence

    /**
     * Writes the staged network: products first, then the network with its nodes, their per-product rows
     * and its links, in one {@code save} that cascades.
     *
     * <p>The version number is the server's, exactly as for {@code POST /projects/{id}/networks}: the
     * first network of a name is version 1 and each later one takes the next, which is what keeps
     * {@code uq_network} satisfied and the variant history in order. An import is therefore a
     * legitimate way to add a variant of an existing network — export, edit in Excel, re-import — and
     * that round trip is a supported workflow.
     *
     * @param baseNetwork the network to record this one as a variant of, already resolved and checked
     *                    by {@link #requireBaseNetwork}, or null for an independent network (FR-28)
     */
    private NetworkDto persist(Project project, Network baseNetwork, ImportRequest request,
            StagedNetwork staged, Map<String, Product> existing) {

        if (request.baseline()) {
            networks.findByProjectIdAndBaselineIsTrue(project.getId()).ifPresent(other -> {
                throw new MultipleBaselineException(project.getId(), other.getId());
            });
        }

        String name = nameOf(request, staged);
        int version = networks.findMaxVersion(project.getId(), name) + 1;
        Network network = new Network(project, name, version, request.baseline());
        TimeBaseSpec timeBase = staged.timeBase();
        network.setPeriodLength(timeBase.periodLength().copy());
        network.setHorizonPeriods(timeBase.horizonPeriods());
        network.setRoundingPolicy(timeBase.roundingPolicy());

        Map<String, Product> catalogue = catalogue(staged, existing, project);

        Map<String, Node> nodesByKey = new LinkedHashMap<>();
        for (StagedNode row : staged.nodes()) {
            Node node = new Node(network, row.name(), row.type());
            node.setCapacity(row.capacity().copy());
            node.setProcessingTime(row.processingTime().copy());
            node.setFixedCost(row.fixedCost());
            node.setVarCost(row.varCost());
            node.setFailureProb(row.failureProb());
            node.setRegion(row.region());
            node.setLat(row.lat());
            node.setLng(row.lng());
            // Carried when the file has them — an XML export always does, and the tabular formats
            // do too. Left null otherwise, which is what the editor's auto-layout
            // answers; writing 0,0 instead would stack the whole network on the origin and
            // look like a layout rather than the absence of one.
            node.setPosX(row.posX());
            node.setPosY(row.posY());
            // FR-30. Carried on the same terms as the layout: annotation is modelling work, and a
            // round trip that drops it loses it. The flag arrives already resolved —
            // an omitted column or an empty cell became true in RowValidator, because an absent
            // caption_visible reads as VISIBLE.
            node.setCaption(row.caption());
            node.setCaptionVisible(row.captionVisible());
            network.addNode(node);
            nodesByKey.put(RowValidator.key(row.name()), node);
        }

        for (StagedNodeProduct row : staged.nodeProducts()) {
            Node node = nodesByKey.get(RowValidator.key(row.node()));
            Product product = catalogue.get(RowValidator.key(row.product()));
            if (node == null || product == null) {
                continue; // unresolvable rows never reach here; a validated import has none
            }
            NodeProduct nodeProduct = new NodeProduct(node, product);
            nodeProduct.setDemand(row.demand().copy());
            nodeProduct.setInitialInventory(row.initialInventory());
            nodeProduct.setSafetyStock(row.safetyStock());
            nodeProduct.setHoldingCost(row.holdingCost().copy());
            node.addProduct(nodeProduct);
        }

        for (StagedLink row : staged.links()) {
            Link link = new Link(network, nodesByKey.get(RowValidator.key(row.source())),
                    nodesByKey.get(RowValidator.key(row.target())));
            link.setLeadTime(row.leadTime().copy());
            link.setCapacity(row.capacity().copy());
            link.setUnitCost(row.unitCost());
            link.setFailureProb(row.failureProb());
            link.setCaption(row.caption());
            link.setCaptionVisible(row.captionVisible());
            network.addLink(link);
        }

        networks.save(network); // cascades nodes, their per-product rows, and links
        recordVariantEdge(project, baseNetwork, network, request);
        // A freshly imported network has no simulation runs, so it is editable by definition
        // — no need to ask the mutation guard.
        return networkMapper.toDto(network, true);
    }

    /**
     * Records the imported network as a {@code CONFIGURATION_VARIANT} of the base it names — the
     * whole of FR-28's backend.
     *
     * <p><strong>In the same transaction as the network, one statement after it.</strong> That is
     * the requirement, not an implementation detail, and it has two halves. An imported variant is
     * never briefly a network with no edge: nothing outside this transaction can observe the network
     * before the edge exists, so the provenance tree cannot draw a batch mid-import as a
     * forest of roots. And a rolled-back import leaves no orphan edge either — a constraint failure
     * in the write above, or anywhere after this line, takes both rows with it, where a second
     * request issued after a successful import could fail on its own and leave the edge behind or
     * the network unlinked. There is no intermediate state to reconcile because there is no
     * intermediate state.
     *
     * <p><strong>Nothing is diffed.</strong> {@code lever_changes_json} carries what the request
     * sent or nothing at all. This tool does not compare two networks, by design; an invented diff
     * would be worse than an absent one — the comparison view reads
     * these notes as the explanation of a metric difference, so a generated "12 nodes
     * added, 3 links removed" would read as a lever the researcher chose rather than an artefact of
     * two files. <strong>Structural diffing of an imported variant against its base is future
     * work</strong>, and this is where it would go: it wants a node-and-link comparison producing
     * the same lever vocabulary the editor's fork prompt collects, which nothing in this codebase
     * has yet.
     *
     * <p>{@code MANUAL} rather than {@code SEARCH}, because a person chose these files and said what
     * each one was; {@code SEARCH} belongs to the Phase 2 configuration engine.
     */
    private void recordVariantEdge(Project project, Network baseNetwork, Network network,
            ImportRequest request) {

        if (baseNetwork == null) {
            return;
        }
        variants.save(new ConfigurationVariant(project, baseNetwork, network, VariantOrigin.MANUAL,
                variantMapper.toJsonString(request.leverChanges())));
    }

    /**
     * The base network an import names, resolved and checked before anything is staged (FR-28).
     *
     * <p>Three answers, and only one of them creates a network. No {@code baseNetworkId} is an
     * independent import, which is what every import once was. A base that does not exist,
     * or belongs to another user, is the 404 {@link NetworkLookup} gives every other id — missing
     * and not-yours are the same answer. A base in another project is
     * {@link BaseNetworkScopeException}, which argues there why it is a refusal rather than a
     * network created with the edge quietly dropped.
     *
     * <p>Resolved here rather than inside {@link #persist} so that {@code validateOnly=true} refuses
     * it too. A dry run has to be able to say everything the commit would refuse — the same reason
     * {@link #reportName} runs during validation — and for a batch that matters more than for one
     * file: the wizard sends its roles step's base id with every file, so a wrong one is caught
     * before the first of eight commits rather than after the seventh.
     */
    private Network requireBaseNetwork(ImportRequest request, Project project, long ownerId) {
        if (request.baseNetworkId() == null) {
            return null;
        }
        Network base = lookup.requireNetwork(request.baseNetworkId(), ownerId);
        long baseProjectId = base.getProject().getId();
        if (baseProjectId != project.getId()) {
            throw new BaseNetworkScopeException(request.baseNetworkId(), baseProjectId,
                    project.getId());
        }
        // A frozen base is the ordinary case, not an exception: the reason to import a variant of a
        // configuration is usually that the configuration has results worth building on. The guard
        // governs edits to the base, and this writes nothing to it (the same rule
        // NetworkService.clone follows).
        return base;
    }

    /**
     * The product rows the import will point at, creating what is missing.
     *
     * <p>Three rules, in this order. A product already in the project is reused untouched — products are
     * project-scoped and shared by every variant, so an import that rewrote {@code unit_value} would
     * change the economics of networks it was never asked to touch. A product named by the file and not
     * in the project is created. And when the {@code products} sheet was absent entirely, the names
     * {@code node_products} used become the catalogue, or a single default product if there are none —
     * which is what "a default product is created if absent" means in practice.
     */
    private Map<String, Product> catalogue(StagedNetwork staged, Map<String, Product> existing,
            Project project) {

        Map<String, Product> catalogue = new LinkedHashMap<>(existing);
        List<Product> created = new ArrayList<>();

        Set<String> wanted = new LinkedHashSet<>();
        Map<String, Double> unitValues = new LinkedHashMap<>();
        for (StagedProduct row : staged.products()) {
            wanted.add(row.name());
            unitValues.put(RowValidator.key(row.name()), row.unitValue());
        }
        for (StagedNodeProduct row : staged.nodeProducts()) {
            wanted.add(row.product());
        }
        if (wanted.isEmpty() && existing.isEmpty()) {
            // "a default product is created if absent" — but only when the project has no
            // catalogue at all. Where one exists, a network that references nothing from it is a
            // legitimate structure-only import, and inventing a product would pollute a catalogue every
            // variant of the project shares.
            wanted.add(DEFAULT_PRODUCT_NAME);
        }

        for (String name : wanted) {
            String key = RowValidator.key(name);
            if (catalogue.containsKey(key)) {
                continue;
            }
            Product product = new Product(project, name, unitValues.getOrDefault(key, 0d));
            created.add(product);
            catalogue.put(key, product);
        }
        if (!created.isEmpty()) {
            products.saveAll(created);
        }
        return catalogue;
    }

    /**
     * Warnings about what the import will do to the project's product catalogue.
     *
     * <p>Raised during validation rather than during the write, so a dry run shows them: both cases are
     * things a researcher would want to know <em>before</em> confirming, since one of them silently keeps
     * a value the file disagrees with.
     */
    private void reportProductCatalogue(StagedNetwork staged, Map<ImportSheet, MappedTable> mapped,
            Map<String, Product> existing, Diagnostics diagnostics) {

        for (StagedProduct row : staged.products()) {
            Product product = existing.get(RowValidator.key(row.name()));
            if (product != null && product.getUnitValue() != row.unitValue()) {
                diagnostics.add(ImportDiagnostic.row(ImportSheet.PRODUCTS, row.line(),
                        ImportSeverity.WARNING, ImportCheck.PRODUCT_ALREADY_IN_PROJECT, row.name(),
                        ("'%s' is already in this project with unit_value %s; the existing value is "
                                + "kept and this row's %s is ignored. Products are shared by every "
                                + "variant of the project, so an import does not rewrite the "
                                + "catalogue.")
                                .formatted(row.name(), product.getUnitValue(), row.unitValue())));
            }
        }

        if (mapped.containsKey(ImportSheet.PRODUCTS)) {
            return;
        }
        Set<String> implied = new LinkedHashSet<>();
        for (StagedNodeProduct row : staged.nodeProducts()) {
            if (!existing.containsKey(RowValidator.key(row.product()))) {
                implied.add(row.product());
            }
        }
        if (!implied.isEmpty()) {
            diagnostics.add(ImportDiagnostic.sheet(ImportSheet.PRODUCTS, null,
                    ImportSeverity.WARNING, ImportCheck.PRODUCT_CREATED_IMPLICITLY,
                    ("No products sheet, so the names used in node_products become the catalogue: %s "
                            + "will be created with unit_value 0. Add a products sheet to give them "
                            + "values.").formatted(String.join(", ", implied))));
        } else if (staged.nodeProducts().isEmpty() && existing.isEmpty()) {
            diagnostics.add(ImportDiagnostic.sheet(ImportSheet.PRODUCTS, null,
                    ImportSeverity.WARNING, ImportCheck.PRODUCT_CREATED_IMPLICITLY,
                    ("No products sheet and no node_products rows, so a single product '%s' will be "
                            + "created and no node will carry demand.")
                            .formatted(DEFAULT_PRODUCT_NAME)));
        }
    }

    /**
     * Reports a network that would have no name, during validation rather than at the write.
     *
     * <p>A dry run has to be able to say everything the commit would refuse — that is what makes the
     * report step a decision rather than a guess — and "the network needs a name" is exactly
     * the kind of thing a user would rather learn before pressing Confirm.
     */
    private static void reportName(ImportRequest request, StagedNetwork staged,
            Diagnostics diagnostics) {
        boolean requested = request.name() != null && !request.name().isBlank();
        boolean declared = staged.declaredName() != null && !staged.declaredName().isBlank();
        if (requested || declared) {
            return;
        }
        diagnostics.add(ImportDiagnostic.sheet(ImportSheet.NETWORK_META, "name",
                ImportSeverity.ERROR, ImportCheck.NETWORK_NAME_MISSING,
                "The network has no name. Give one in the import request, or add a 'name' to "
                        + "network_meta — an XML export always carries one."));
    }

    // ------------------------------------------------------------------ 5.5.4 bridge

    /**
     * The staged network as the input to the resolution checks — a clock and every duration
     * declared under it.
     *
     * <p>No ids, because nothing is persisted; no events, because an import carries no scenario, which is
     * why {@code EVENT_EXCEEDS_HORIZON} cannot fire here. The element name is what
     * {@link #mirror} matches back to a line number.
     */
    private static TimeValidationInput timeInput(StagedNetwork staged) {
        List<DeclaredDuration> declared = new ArrayList<>();
        for (StagedNode node : staged.nodes()) {
            declared.add(new DeclaredDuration(TimeElementType.NODE, null, node.name(),
                    "processingTime", node.processingTime()));
        }
        for (StagedLink link : staged.links()) {
            declared.add(new DeclaredDuration(TimeElementType.LINK, null, linkName(link), "leadTime",
                    link.leadTime()));
        }
        TimeBaseSpec timeBase = staged.timeBase();
        return TimeValidationInput.of(timeBase.periodLength(), timeBase.roundingPolicy(),
                timeBase.horizonPeriods(), declared);
    }

    /**
     * Each resolution finding as a row-addressed diagnostic.
     *
     * <p>The finding knows which element it is about but not which line of which file that element came
     * from — it is deliberately ignorant of files (time validation runs in the editor too). This is where
     * the two are joined, by name, so the wizard's per-row table can show "line 8: lead time 6 h rounds to
     * nothing" next to the cell problems rather than in a separate list the user has to correlate by
     * hand.
     */
    private static List<ImportDiagnostic> mirror(TimeValidationReport resolution,
            StagedNetwork staged) {

        Map<String, Integer> nodeLines = new LinkedHashMap<>();
        for (StagedNode node : staged.nodes()) {
            nodeLines.put(node.name(), node.line());
        }
        Map<String, Integer> linkLines = new LinkedHashMap<>();
        for (StagedLink link : staged.links()) {
            linkLines.put(linkName(link), link.line());
        }

        List<ImportDiagnostic> mirrored = new ArrayList<>();
        for (TimeFinding finding : resolution.findings()) {
            boolean isNode = finding.elementType() == TimeElementType.NODE;
            ImportSheet sheet = isNode ? ImportSheet.NODES : ImportSheet.LINKS;
            String column = isNode ? "processing_time_value" : "lead_time_value";
            Integer line = isNode
                    ? nodeLines.get(finding.elementName())
                    : linkLines.get(finding.elementName());
            ImportSeverity severity = finding.severity() == TimeSeverity.ERROR
                    ? ImportSeverity.ERROR
                    : ImportSeverity.WARNING;
            mirrored.add(ImportDiagnostic.timeResolution(sheet, line, column, severity,
                    finding.code().name(), finding.elementName(), finding.message()));
        }
        return mirrored;
    }

    // ----------------------------------------------------------------------- helpers

    private Map<ImportSheet, MappedTable> map(Map<ImportSheet, SourceTable> tables,
            ImportMapping mapping) {
        Map<ImportSheet, MappedTable> mapped = new EnumMap<>(ImportSheet.class);
        for (ImportSheet sheet : ImportSheet.declarationOrder()) {
            SourceTable table = tables.get(sheet);
            if (table != null) {
                mapped.put(sheet, MappedTable.of(table, mapping.forSheet(sheet)));
            }
        }
        return mapped;
    }

    /** The project's products by lower-cased name — the catalogue an import draws on. */
    private Map<String, Product> existingProducts(Project project) {
        Map<String, Product> byKey = new LinkedHashMap<>();
        for (Product product : products.findByProjectIdOrderByNameAsc(project.getId())) {
            byKey.put(RowValidator.key(product.getName()), product);
        }
        return byKey;
    }

    private static SheetPreview previewOf(MappedTable table) {
        List<ColumnSuggestion> columns = new ArrayList<>();
        for (Map.Entry<String, String> entry : table.effectiveMapping().entrySet()) {
            columns.add(new ColumnSuggestion(entry.getKey(), entry.getValue()));
        }
        List<List<String>> sample = new ArrayList<>();
        for (SourceRow row : table.table().sample(SAMPLE_ROWS)) {
            sample.add(row.cells());
        }
        return new SheetPreview(table.sheet().canonicalName(), table.table().sourceName(),
                table.table().origin(), table.table().headers(), table.rows().size(), columns,
                table.missingRequired(), sample);
    }

    /** The canonical schema, for the wizard's mapping dropdowns. */
    private static Map<String, List<CanonicalColumn>> schema() {
        Map<String, List<CanonicalColumn>> schema = new LinkedHashMap<>();
        for (ImportSheet sheet : ImportSheet.declarationOrder()) {
            List<CanonicalColumn> columns = new ArrayList<>();
            for (ColumnSpec column : ImportSchema.columnsOf(sheet)) {
                columns.add(new CanonicalColumn(column.name(), column.required(), column.unitOwner(),
                        column.note()));
            }
            schema.put(sheet.canonicalName(), columns);
        }
        return schema;
    }

    /**
     * The name the new network takes: the request's, or the one the file declared.
     *
     * <p>The request wins, because a user renaming an import in the wizard is making a decision and the
     * file cannot overrule it. The file's name is what makes an XML document importable on its own
     * — an export carries the network's name, so re-importing it needs nothing typed. With
     * neither, there is nothing to fall back on, and inventing something like "Imported network" would
     * bury a real mistake under a plausible-looking result.
     *
     * @throws IllegalArgumentException if the request and the file both leave it blank
     */
    private static String nameOf(ImportRequest request, StagedNetwork staged) {
        if (request.name() != null && !request.name().isBlank()) {
            return request.name().trim();
        }
        if (staged.declaredName() != null && !staged.declaredName().isBlank()) {
            return staged.declaredName().trim();
        }
        throw new IllegalArgumentException("The network needs a name: send one as the 'name' field, "
                + "or import a file whose network_meta declares one.");
    }

    private Project requireProject(long projectId, long ownerId) {
        return projects.findById(projectId)
                .filter(project -> project.getOwnerId() == ownerId)
                .orElseThrow(() -> new EntityNotFoundException("No project with id " + projectId));
    }

    /** How a link names itself in a finding — the same form the editor's banner uses. */
    private static String linkName(StagedLink link) {
        return link.source() + " → " + link.target();
    }
}
