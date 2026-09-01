package com.snrm.network;

import com.snrm.common.DurationAmount;
import com.snrm.common.DurationDto;
import com.snrm.project.Project;
import com.snrm.project.ProjectRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@link Network} aggregate: its lifecycle, and the fork that the immutability rule points
 * callers at.
 *
 * <p>Every <em>structural</em> write opens with {@link NetworkMutationGuard#assertMutable(long)}.
 * That is the whole enforcement — there is no database trigger and no second line of
 * defence, so a new write path that skips the guard silently loses the guarantee. {@link NodeService},
 * {@link LinkService} and {@link ProductService} follow the same discipline.
 *
 * <p>Two methods here are deliberately unguarded, and the exemptions are exhaustive. {@link #clone}
 * is the remedy the guard advertises, so it works on a frozen network by construction.
 * {@link #update} writes the network's name and baseline flag, which a freeze does not cover
 * (FR-29); its Javadoc carries the argument. Everything
 * else on this class — {@link #setTimeBase}, {@link #delete} — is guarded, and the time base is the
 * guard's most consequential use.
 *
 * <p>Returns DTOs and maps inside the transaction, because {@code spring.jpa.open-in-view=false}
 * means a lazy association touched after the service returns would throw (entities never
 * cross the API).
 */
@Service
@Transactional
public class NetworkService {

    private final NetworkRepository networks;
    private final NodeProductRepository nodeProducts;
    private final ConfigurationVariantRepository variants;
    private final ProjectRepository projects;
    private final NetworkMutationGuard guard;
    private final NetworkLookup lookup;
    private final TimeValidationService timeValidation;
    private final NetworkMapper networkMapper;
    private final ConfigurationVariantMapper variantMapper;

    NetworkService(NetworkRepository networks, NodeProductRepository nodeProducts,
            ConfigurationVariantRepository variants, ProjectRepository projects,
            NetworkMutationGuard guard, NetworkLookup lookup, TimeValidationService timeValidation,
            NetworkMapper networkMapper, ConfigurationVariantMapper variantMapper) {
        this.networks = networks;
        this.nodeProducts = nodeProducts;
        this.variants = variants;
        this.projects = projects;
        this.guard = guard;
        this.lookup = lookup;
        this.timeValidation = timeValidation;
        this.networkMapper = networkMapper;
        this.variantMapper = variantMapper;
    }

    // ---------------------------------------------------------------- networks

    /** Every network of a project, baseline and variants together, ordered by name then version. */
    @Transactional(readOnly = true)
    public List<NetworkDto> listByProject(long projectId, long ownerId) {
        requireProject(projectId, ownerId);
        return toDtoList(networks.findByProjectIdOrderByNameAscVersionAsc(projectId));
    }

    @Transactional(readOnly = true)
    public NetworkDto get(long networkId, long ownerId) {
        return toDto(lookup.requireNetwork(networkId, ownerId));
    }

    /**
     * Creates a network in a project.
     *
     * <p>The version is the server's to assign: the first network of a given name is version 1 and
     * every later one takes the next number, which is what keeps {@code uq_network} satisfied and
     * the variant history in order.
     *
     * @throws MultipleBaselineException if the project already has a baseline and this asks to be one
     */
    public NetworkDto create(long projectId, long ownerId, NetworkRequest request) {
        Project project = requireProject(projectId, ownerId);
        String name = request.name().trim();
        if (request.baseline()) {
            assertNoOtherBaseline(projectId, null);
        }
        int version = networks.findMaxVersion(projectId, name) + 1;
        Network network = networks.save(new Network(project, name, version, request.baseline()));
        return toDto(network);
    }

    /**
     * Renames a network and/or moves the baseline flag onto it — {@code PUT /networks/{id}}
     * (FR-29).
     *
     * <p><strong>Deliberately unguarded: a rename is not a structural edit.</strong> The freeze
     * covers everything a result was computed <em>from</em> — nodes, links, per-product rows,
     * the time base. A name and a baseline flag are neither. Neither is an input to any metric:
     * {@code NetworkGraph} carries the name only as a label whose accessor
     * ({@code NetworkGraph.networkName()}) has no caller anywhere in this codebase, carries no
     * baseline flag at all, and no {@code MetricCalculator}, no simulation code and no persisted
     * result reads either. Freezing them therefore protects nothing the rule exists to protect, while
     * making a network unnameable for the rest of its life on the strength of one trial run.
     *
     * <p>Two things make the carve-out the common case rather than a curiosity. A batch import names
     * networks after their files (FR-28), so the network most in need of renaming is routinely one
     * that has already been run — {@code Baseline_v3_FINAL.xlsx} becomes a row in a table that will
     * outlive the file. And designating which configuration is the project's baseline is a judgement
     * a researcher usually reaches <em>after</em> comparing runs, not before.
     *
     * <p><strong>What this does not relax.</strong> The guard is removed from this method and from
     * nothing else. {@link #setTimeBase} in particular stays guarded and is the most consequential
     * use of it, because a result stated in periods stops being comparable when a period is
     * redefined; {@link #delete}, {@link NodeService}, {@link LinkService} and
     * {@link ProductService}'s node-product writes are untouched.
     *
     * <p><strong>What the guard was masking in {@link #assertNoOtherBaseline}.</strong> That check
     * refuses a second baseline; it never moves the flag, so designating a new baseline is two
     * requests — clear the incumbent, then set the new one. While this method was guarded, a
     * <em>frozen</em> incumbent could not be cleared, which made the flag permanently stuck and
     * {@code BASELINE_ALREADY_SET} unresolvable in exactly the case FR-29 names: a baseline chosen
     * after the runs that justify it. Unguarding this method is what makes the two-request sequence
     * possible at all. The check itself is unchanged and still reads-then-writes without a database
     * constraint behind it ({@code is_baseline} is a plain column and MySQL has no partial unique
     * index), so it is exactly as race-prone as it was before — the guard never contributed to that,
     * since a network the check reads is one no run has to be touching.
     */
    public NetworkDto update(long networkId, long ownerId, NetworkRequest request) {
        Network network = lookup.requireNetwork(networkId, ownerId);
        long projectId = network.getProject().getId();

        String name = request.name().trim();
        if (request.baseline() && !network.isBaseline()) {
            assertNoOtherBaseline(projectId, networkId);
        }
        network.setName(name);
        network.setBaseline(request.baseline());
        return toDto(network);
    }

    /**
     * Sets the network's clock — period length, horizon and rounding policy — and reports what the
     * new clock does to every duration underneath it ({@code PUT /networks/{id}/time-base}).
     *
     * <p><strong>Guarded, and this is the guard's most consequential use.</strong> A completed run's
     * results are stated in periods, so changing what a period means turns "TTR = 14" from fourteen
     * days into fourteen hours without touching a stored number — the results are not wrong, they
     * are no longer comparable, which is worse because nothing about them looks different. The
     * editor makes the same call from the other side: changing the period is a structural
     * edit and raises the fork-to-variant prompt. The remedy is the one
     * {@link NetworkImmutableException} already names — clone the network and set the time base on
     * the copy, which then carries its own clock and its own results.
     *
     * <p>The validation runs against the values just written, in this transaction, so
     * the response carries the warning banner the change itself produced rather than the state
     * before it.
     *
     * @throws NetworkImmutableException if a simulation run holds the network frozen
     * @throws IllegalArgumentException  if the period length is not strictly positive, or is under
     *                                   one second and so rounds to nothing in the canonical form;
     *                                   every conversion divides by it
     */
    public TimeBaseResponse setTimeBase(long networkId, long ownerId, TimeBaseRequest request) {
        guard.assertMutable(networkId);
        Network network = lookup.requireNetwork(networkId, ownerId);

        DurationDto period = request.periodLength();
        if (period.value() == null || period.value() <= 0) {
            throw new IllegalArgumentException(
                    "periodLength.value must be greater than zero, was " + period.value());
        }
        DurationAmount periodLength = DurationAmount.of(period.value(), period.unit());
        if (periodLength.getSeconds() < 1) {
            // The canonical form is a whole number of seconds, so a period under half a
            // second rounds to nothing at all. ck_network_period would reject it, but as a 409 from
            // the driver with a message no client can act on — and GlobalExceptionHandler treats
            // reaching that path as a missing service-level check, which is exactly what this is.
            throw new IllegalArgumentException(("periodLength of %s %s is under one second, which "
                    + "is the finest the canonical form resolves: it rounds to "
                    + "zero seconds and every duration in the network would divide by it.")
                    .formatted(period.value(), period.unit()));
        }
        network.setPeriodLength(periodLength);
        network.setHorizonPeriods(request.horizonPeriods());
        network.setRoundingPolicy(request.roundingPolicy());

        return new TimeBaseResponse(toDto(network),
                timeValidation.validate(networkId, null, ownerId, TimeValidationContext.EDITOR));
    }

    /**
     * Deletes a network and everything beneath it.
     *
     * <p>Guarded for the same reason edits are: dropping a network a run points at would destroy
     * the inputs of a persisted result.
     */
    public void delete(long networkId, long ownerId) {
        guard.assertMutable(networkId);
        networks.delete(lookup.requireNetwork(networkId, ownerId));
    }

    // ------------------------------------------------------------------- fork

    /**
     * Forks a network into a configuration variant — the remedy
     * {@link NetworkImmutableException} advertises, and {@code POST /networks/{id}/clone}.
     *
     * <p>The copy takes the next version number under its name, so variants of one network sort
     * together, and is never a baseline: there is exactly one of those per project, and the fork is
     * by definition not it.
     *
     * <p>Products are <em>not</em> copied. They are project-scoped, and a variant is a different
     * structure over the same catalogue — the per-node {@code node_product} rows are
     * copied and keep pointing at the original products.
     *
     * <p>Deliberately unguarded: being frozen is the normal reason to call this.
     */
    public ConfigurationVariantDto clone(long baseNetworkId, long ownerId,
            CloneNetworkRequest request) {
        Network base = lookup.requireNetwork(baseNetworkId, ownerId);
        Project project = base.getProject();

        String name = request.name() == null || request.name().isBlank()
                ? base.getName()
                : request.name().trim();
        int version = networks.findMaxVersion(project.getId(), name) + 1;
        Network copy = new Network(project, name, version, false);
        // The variant inherits the base network's clock. It is not a lever: two networks
        // compared side by side must step on the same grid and discretise durations the
        // same way, or the comparison measures the time model rather than the configuration.
        copy.setPeriodLength(base.getPeriodLength().copy());
        copy.setHorizonPeriods(base.getHorizonPeriods());
        copy.setRoundingPolicy(base.getRoundingPolicy());

        Map<Long, Node> copiesByOriginalId = new HashMap<>();
        for (Node original : base.getNodes()) {
            Node node = copyOf(original, copy);
            copy.addNode(node);
            copiesByOriginalId.put(original.getId(), node);
        }

        // One query for the whole network's per-product rows rather than one per node.
        for (NodeProduct original : nodeProducts.findByNetworkId(baseNetworkId)) {
            Node node = copiesByOriginalId.get(original.getId().getNodeId());
            node.addProduct(copyOf(original, node));
        }

        for (Link original : base.getLinks()) {
            copy.addLink(copyOf(original, copy,
                    copiesByOriginalId.get(original.getSourceNode().getId()),
                    copiesByOriginalId.get(original.getTargetNode().getId())));
        }

        networks.save(copy); // cascades nodes, their products, and links

        ConfigurationVariant variant = variants.save(new ConfigurationVariant(project, base, copy,
                VariantOrigin.MANUAL, variantMapper.toJsonString(request.leverChanges())));
        return variantMapper.toDto(variant, toDto(copy));
    }

    /** The variants forked from a network — the provenance tree of a baseline. */
    @Transactional(readOnly = true)
    public List<ConfigurationVariantDto> variantsOf(long baseNetworkId, long ownerId) {
        lookup.requireNetwork(baseNetworkId, ownerId);
        List<ConfigurationVariantDto> dtos = new ArrayList<>();
        for (ConfigurationVariant variant : variants.findByBaseNetworkId(baseNetworkId)) {
            dtos.add(variantMapper.toDto(variant, toDto(variant.getNetwork())));
        }
        return dtos;
    }

    // ----------------------------------------------------------------- helpers

    /**
     * Maps with the {@code editable} flag the editor needs, which costs one indexed
     * {@code EXISTS} per network — {@code ix_run_network_status} covers it.
     */
    private NetworkDto toDto(Network network) {
        return networkMapper.toDto(network, guard.isMutable(network.getId()));
    }

    private List<NetworkDto> toDtoList(List<Network> found) {
        List<NetworkDto> dtos = new ArrayList<>(found.size());
        for (Network network : found) {
            dtos.add(toDto(network));
        }
        return dtos;
    }

    private Project requireProject(long projectId, long ownerId) {
        return projects.findById(projectId)
                .filter(project -> project.getOwnerId() == ownerId)
                .orElseThrow(() -> new EntityNotFoundException("No project with id " + projectId));
    }

    /**
     * @param exemptNetworkId the network being updated, which may already hold the flag, or null
     * @throws MultipleBaselineException if another network of the project is the baseline
     */
    private void assertNoOtherBaseline(long projectId, Long exemptNetworkId) {
        networks.findByProjectIdAndBaselineIsTrue(projectId)
                .filter(existing -> !existing.getId().equals(exemptNetworkId))
                .ifPresent(existing -> {
                    throw new MultipleBaselineException(projectId, existing.getId());
                });
    }

    // The time-unit value objects are copied, not shared: an embeddable is mutable and owned by
    // exactly one entity, so handing the clone the original's instance would make a later edit to
    // either one show up in both. Copying the whole object also carries the unit across,
    // which the deprecated per-period bridges would silently drop.
    private static Node copyOf(Node original, Network target) {
        Node node = new Node(target, original.getName(), original.getType());
        node.setCapacity(original.getCapacity().copy());
        node.setProcessingTime(original.getProcessingTime().copy());
        node.setFixedCost(original.getFixedCost());
        node.setVarCost(original.getVarCost());
        node.setFailureProb(original.getFailureProb());
        node.setRegion(original.getRegion());
        node.setLat(original.getLat());
        node.setLng(original.getLng());
        node.setPosX(original.getPosX());
        node.setPosY(original.getPosY());
        return node;
    }

    private static Link copyOf(Link original, Network target, Node source, Node destination) {
        Link link = new Link(target, source, destination);
        link.setLeadTime(original.getLeadTime().copy());
        link.setCapacity(original.getCapacity().copy());
        link.setUnitCost(original.getUnitCost());
        link.setFailureProb(original.getFailureProb());
        return link;
    }

    private static NodeProduct copyOf(NodeProduct original, Node target) {
        NodeProduct nodeProduct = new NodeProduct(target, original.getProduct());
        nodeProduct.setDemand(original.getDemand().copy());
        nodeProduct.setInitialInventory(original.getInitialInventory());
        nodeProduct.setSafetyStock(original.getSafetyStock());
        nodeProduct.setHoldingCost(original.getHoldingCost().copy());
        return nodeProduct;
    }
}
