package com.snrm.network;

import com.snrm.common.DuplicateNameException;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Node CRUD and the batched canvas writes.
 *
 * <p>Every method guards the network first, then resolves ids through
 * {@link NetworkLookup} so a node from another network — or another user — cannot be reached
 * through a path that names this one.
 *
 * <p><strong>Batches are all-or-nothing.</strong> Each bulk method validates every element before
 * writing any of them, inside one transaction. A partially applied batch would leave the editor's
 * client-side undo stack describing a state the server never reached, and the user with no way to
 * tell which half landed.
 */
@Service
@Transactional
public class NodeService {

    private final NodeRepository nodes;
    private final NetworkMutationGuard guard;
    private final NetworkLookup lookup;
    private final NodeMapper mapper;

    NodeService(NodeRepository nodes, NetworkMutationGuard guard, NetworkLookup lookup,
            NodeMapper mapper) {
        this.nodes = nodes;
        this.guard = guard;
        this.lookup = lookup;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<NodeDto> listByNetwork(long networkId, long ownerId) {
        lookup.requireNetwork(networkId, ownerId);
        return mapper.toDtoList(nodes.findByNetworkId(networkId));
    }

    @Transactional(readOnly = true)
    public NodeDto get(long nodeId, long ownerId) {
        return mapper.toDto(lookup.requireNode(nodeId, ownerId));
    }

    /**
     * Adds a node — the palette drag-and-drop, where {@code posX}/{@code posY} are the
     * drop coordinates.
     *
     * @throws DuplicateNameException if the network already has a node with that name
     *                                ({@code uq_node}); import resolves link endpoints by name, so
     *                                the constraint is load-bearing
     */
    public NodeDto create(long networkId, long ownerId, NodeRequest request) {
        guard.assertMutable(networkId);
        Network network = lookup.requireNetwork(networkId, ownerId);

        String name = request.name().trim();
        if (nodes.existsByNetworkIdAndName(networkId, name)) {
            throw new DuplicateNameException("node", "Network " + networkId, name);
        }

        Node node = new Node(network, name, request.type());
        mapper.replace(request, node);
        node.setName(name); // the mapper copies the raw name back over; keep the trimmed one
        network.addNode(node);
        return mapper.toDto(nodes.save(node));
    }

    /** Full replacement: an omitted nullable field is cleared (see {@link NodeMapper}). */
    public NodeDto replace(long nodeId, long ownerId, NodeRequest request) {
        Node node = lookup.requireNode(nodeId, ownerId);
        long networkId = node.getNetwork().getId();
        guard.assertMutable(networkId);

        String name = request.name().trim();
        if (!node.getName().equals(name) && nodes.existsByNetworkIdAndName(networkId, name)) {
            throw new DuplicateNameException("node", "Network " + networkId, name);
        }

        mapper.replace(request, node);
        node.setName(name);
        return mapper.toDto(node);
    }

    /**
     * Deletes a node.
     *
     * <p>Its links and {@code node_product} rows go with it, cascaded by {@code fk_link_source},
     * {@code fk_link_target} and {@code fk_node_product_node}. Scenario events targeting it are a
     * soft reference and are listed to the user before the delete rather than cascaded.
     */
    public void delete(long nodeId, long ownerId) {
        Node node = lookup.requireNode(nodeId, ownerId);
        guard.assertMutable(node.getNetwork().getId());
        nodes.delete(node);
    }

    // ------------------------------------------------------ batched canvas writes

    /**
     * Applies a batch of canvas moves — what a drag of a box-selection produces.
     *
     * @return the moved nodes, in the order they were sent
     */
    public List<NodeDto> moveAll(long networkId, long ownerId, List<NodePositionPatch> positions) {
        guard.assertMutable(networkId);
        lookup.requireNetwork(networkId, ownerId);

        List<Long> ids = positions.stream().map(NodePositionPatch::nodeId).toList();
        Map<Long, Node> byId = resolveAllIn(networkId, ids);

        List<NodeDto> moved = new ArrayList<>(positions.size());
        for (NodePositionPatch position : positions) {
            Node node = byId.get(position.nodeId());
            node.setPosX(position.posX());
            node.setPosY(position.posY());
            moved.add(mapper.toDto(node));
        }
        return moved;
    }

    /**
     * Applies a batch of attribute edits. Omitted fields are left unchanged, which is
     * what makes this usable across a multi-selection of dissimilar nodes.
     *
     * <p>Renames are checked against the rest of the network <em>and</em> against the other names in
     * the same batch, so swapping two names in one request is rejected rather than half-applied.
     *
     * @return the edited nodes, in the order they were sent
     */
    public List<NodeDto> patchAll(long networkId, long ownerId, List<NodePatch> patches) {
        guard.assertMutable(networkId);
        lookup.requireNetwork(networkId, ownerId);

        List<Long> ids = patches.stream().map(NodePatch::nodeId).toList();
        Map<Long, Node> byId = resolveAllIn(networkId, ids);

        Set<String> namesInBatch = new LinkedHashSet<>();
        for (NodePatch patch : patches) {
            if (patch.name() == null) {
                continue;
            }
            String name = patch.name().trim();
            Node node = byId.get(patch.nodeId());
            if (!namesInBatch.add(name)) {
                throw new DuplicateNameException("node", "This batch", name);
            }
            if (!node.getName().equals(name) && nodes.existsByNetworkIdAndName(networkId, name)) {
                throw new DuplicateNameException("node", "Network " + networkId, name);
            }
        }

        List<NodeDto> edited = new ArrayList<>(patches.size());
        for (NodePatch patch : patches) {
            Node node = byId.get(patch.nodeId());
            mapper.patch(patch, node);
            if (patch.name() != null) {
                node.setName(patch.name().trim());
            }
            edited.add(mapper.toDto(node));
        }
        return edited;
    }

    /**
     * Loads every id in one query and proves each one belongs to this network, before any of them
     * is written.
     *
     * @throws IllegalArgumentException       if an id repeats within the batch — two edits to one
     *                                        node in a single request have no defined order
     * @throws EntityNotFoundException        if an id matches no node
     * @throws CrossNetworkReferenceException if an id matches a node of another network
     */
    private Map<Long, Node> resolveAllIn(long networkId, List<Long> ids) {
        Set<Long> distinct = new LinkedHashSet<>(ids);
        if (distinct.size() != ids.size()) {
            throw new IllegalArgumentException(
                    "The same node id appears more than once in this batch; each node may be "
                            + "changed at most once per request.");
        }

        Map<Long, Node> byId = new HashMap<>(distinct.size());
        for (Node node : nodes.findAllById(distinct)) {
            byId.put(node.getId(), node);
        }
        for (Long id : distinct) {
            Node node = byId.get(id);
            if (node == null) {
                throw new EntityNotFoundException("No node with id " + id);
            }
            long actualNetworkId = node.getNetwork().getId();
            if (actualNetworkId != networkId) {
                throw new CrossNetworkReferenceException(networkId, "node", id, actualNetworkId);
            }
        }
        return byId;
    }
}
