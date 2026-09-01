package com.snrm.network;

import com.snrm.project.Project;
import com.snrm.project.ProjectRepository;
import com.snrm.simulation.SimulationRun;
import com.snrm.simulation.SimulationRunRepository;
import com.snrm.simulation.SimulationStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Writing, hiding, keeping and clearing a caption, against the real schema (FR-30).
 *
 * <p>{@code CaptionsTest} pins the same contract as arithmetic over {@link Captions} and runs
 * everywhere. This one runs it through {@link NodeService} and {@link LinkService} and MySQL, which
 * is what checks the three things a pure test cannot: that {@code caption_visible} really is
 * {@code NOT NULL DEFAULT TRUE} so a node created without one is visible, that a cleared caption is
 * a SQL {@code NULL} rather than an empty string, and that <strong>the immutability guard was not
 * touched</strong>.
 *
 * <p><strong>The guard assertion is the load-bearing one.</strong> Exempting captions from the
 * freeze, the way the network's own name is exempt from it, was considered and deliberately
 * rejected: a caption sits on an element inside the network, so an exemption would make
 * the editor's one debounced save queue split its batch by field, and the guard would become
 * per-field logic in two places. If a later change makes {@link #aCaptionOnAFrozenNetworkIsRefused}
 * pass by returning a result instead of throwing, that argument has been reversed without anyone
 * writing down why.
 *
 * <p>Skipped when no Docker daemon is reachable, exactly like {@code NetworkRenameTest}.
 * Deliberately not {@code @Transactional}: each service call commits, and {@link #cleanUp()} drops
 * the project, which cascades.
 */
@SpringBootTest
@Testcontainers
@EnabledIf("dockerAvailable")
@DisplayName("Captions on nodes and links — FR-30")
class CaptionEditTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8");

    static boolean dockerAvailable() {
        return DockerClientFactory.instance().isDockerAvailable();
    }

    /** The single research user of Phase 1; the services take it as an argument. */
    private static final long OWNER = 3011L;

    @Autowired
    private NodeService nodeService;
    @Autowired
    private LinkService linkService;
    @Autowired
    private NodeRepository nodes;
    @Autowired
    private LinkRepository links;
    @Autowired
    private NetworkRepository networks;
    @Autowired
    private ProjectRepository projects;
    @Autowired
    private SimulationRunRepository runs;

    private Network network;
    private final List<Long> createdProjects = new ArrayList<>();

    @BeforeEach
    void createNetwork() {
        Project project = projects.save(new Project("FR-30 captions", OWNER));
        createdProjects.add(project.getId());
        network = networks.save(new Network(project, "Captioned", 1, false));
    }

    @AfterEach
    void cleanUp() {
        for (Long id : createdProjects) {
            projects.deleteById(id);
        }
        createdProjects.clear();
    }

    // ------------------------------------------------------------------------ writing one

    @Test
    @DisplayName("a node created with a caption and no flag is VISIBLE — V10's column default")
    void aNewCaptionIsVisibleWithoutASecondGesture() {
        NodeDto created = nodeService.create(network.getId(), OWNER,
                node("DC-1", NodeType.DC, "Nordic hub — 3PL operated", null));

        assertThat(created.caption()).isEqualTo("Nordic hub — 3PL operated");
        assertThat(created.captionVisible())
                .as("typing a caption shows one; a default of false would read as a broken field")
                .isTrue();
        assertThat(nodes.findById(created.id()).orElseThrow().isCaptionVisible()).isTrue();
    }

    @Test
    @DisplayName("a node created with no caption at all carries null, not an empty string")
    void anUncaptionedNodeCarriesNull() {
        NodeDto created = nodeService.create(network.getId(), OWNER,
                node("SUP-1", NodeType.SUPPLIER, null, null));

        assertThat(created.caption()).isNull();
        assertThat(nodes.findById(created.id()).orElseThrow().getCaption()).isNull();
    }

    @Test
    @DisplayName("a caption that is only whitespace is stored as null — one form of 'no caption'")
    void aBlankCaptionIsNull() {
        NodeDto created = nodeService.create(network.getId(), OWNER,
                node("SUP-1", NodeType.SUPPLIER, "   ", null));

        assertThat(created.caption()).isNull();
    }

    // ------------------------------------------------------------- hiding, which is not clearing

    @Test
    @DisplayName("the flag hides a caption and the caption survives — the whole point of FR-30")
    void hidingKeepsTheCaption() {
        NodeDto created = nodeService.create(network.getId(), OWNER,
                node("DC-1", NodeType.DC, "Nordic hub", null));

        List<NodeDto> hidden = nodeService.patchAll(network.getId(), OWNER,
                List.of(patch(created.id(), null, false)));

        assertThat(hidden).singleElement().satisfies(node -> {
            assertThat(node.captionVisible()).isFalse();
            assertThat(node.caption())
                    .as("hidden for a screenshot, kept for the next reader")
                    .isEqualTo("Nordic hub");
        });
    }

    @Test
    @DisplayName("a PATCH that omits the caption leaves it alone, as every other field is left alone")
    void anOmittedCaptionInAPatchIsUnchanged() {
        NodeDto created = nodeService.create(network.getId(), OWNER,
                node("DC-1", NodeType.DC, "Nordic hub", null));

        nodeService.patchAll(network.getId(), OWNER, List.of(patch(created.id(), null, null)));

        assertThat(nodes.findById(created.id()).orElseThrow().getCaption()).isEqualTo("Nordic hub");
    }

    // ------------------------------------------------------------------- the clearing path

    @Test
    @DisplayName("THE CLEARING PATH: an empty string in the bulk PATCH removes the caption")
    void anEmptyStringInAPatchClearsTheCaption() {
        // A caption is an ordinary edit written through the editor's debounced bulk
        // PATCH, and a PATCH cannot express "set to nothing". This is how the two
        // are reconciled, and it is the only field on NodePatch where a blank is a write.
        NodeDto created = nodeService.create(network.getId(), OWNER,
                node("DC-1", NodeType.DC, "Nordic hub", null));

        List<NodeDto> cleared = nodeService.patchAll(network.getId(), OWNER,
                List.of(patch(created.id(), "", null)));

        assertThat(cleared).singleElement()
                .satisfies(node -> assertThat(node.caption()).isNull());
        assertThat(nodes.findById(created.id()).orElseThrow().getCaption())
                .as("a SQL NULL, not an empty string — there is one form of 'no caption'")
                .isNull();
    }

    @Test
    @DisplayName("clearing does not disturb the flag, and hiding does not disturb the text")
    void clearingAndHidingAreIndependent() {
        NodeDto created = nodeService.create(network.getId(), OWNER,
                node("DC-1", NodeType.DC, "Nordic hub", false));

        List<NodeDto> cleared = nodeService.patchAll(network.getId(), OWNER,
                List.of(patch(created.id(), "", null)));

        assertThat(cleared).singleElement().satisfies(node -> {
            assertThat(node.caption()).isNull();
            assertThat(node.captionVisible())
                    .as("the flag was not sent, so it was not written — an empty caption draws "
                            + "nothing either way")
                    .isFalse();
        });
    }

    @Test
    @DisplayName("the PUT clears it too, by omission, like every other nullable field")
    void aPutWithoutACaptionClearsIt() {
        NodeDto created = nodeService.create(network.getId(), OWNER,
                node("DC-1", NodeType.DC, "Nordic hub", false));

        NodeDto replaced = nodeService.replace(created.id(), OWNER,
                node("DC-1", NodeType.DC, null, null));

        assertThat(replaced.caption()).isNull();
        assertThat(replaced.captionVisible())
                .as("and an omitted flag on a PUT is VISIBLE, not the primitive default of false")
                .isTrue();
    }

    // ------------------------------------------------------------------------------ links

    @Test
    @DisplayName("a link carries the pair on exactly the same terms")
    void aLinkIsCaptionedTheSameWay() {
        NodeDto plant = nodeService.create(network.getId(), OWNER,
                node("PLANT-1", NodeType.PLANT, null, null));
        NodeDto dc = nodeService.create(network.getId(), OWNER,
                node("DC-1", NodeType.DC, null, null));

        LinkDto created = linkService.create(network.getId(), OWNER, new LinkRequest(plant.id(),
                dc.id(), null, null, 0, 0, "Ocean leg — one carrier", null));

        assertThat(created.caption()).isEqualTo("Ocean leg — one carrier");
        assertThat(created.captionVisible()).isTrue();

        List<LinkDto> cleared = linkService.patchAll(network.getId(), OWNER,
                List.of(new LinkPatch(created.id(), null, null, null, null, "", null)));

        assertThat(cleared).singleElement()
                .satisfies(link -> assertThat(link.caption()).isNull());
        assertThat(links.findById(created.id()).orElseThrow().getCaption()).isNull();
    }

    // ---------------------------------------------------------------- the guard, unchanged

    @Test
    @DisplayName("a caption on a FROZEN network is refused — no exemption was made")
    void aCaptionOnAFrozenNetworkIsRefused() {
        NodeDto created = nodeService.create(network.getId(), OWNER,
                node("DC-1", NodeType.DC, "Nordic hub", null));
        freeze(network);

        // The bulk PATCH the editor writes captions through is guarded exactly as it was: the guard
        // is one call at the top of NodeService.patchAll, and a caption is not a special case of it.
        assertThatExceptionOfType(NetworkImmutableException.class)
                .isThrownBy(() -> nodeService.patchAll(network.getId(), OWNER,
                        List.of(patch(created.id(), "Annotated for the paper", null))))
                .satisfies(refusal -> assertThat(refusal.code()).isEqualTo("NETWORK_IMMUTABLE"));

        // Including merely hiding one, which changes no text at all — the guard is about the write
        // path, not about how much the write changes.
        assertThatExceptionOfType(NetworkImmutableException.class)
                .isThrownBy(() -> nodeService.patchAll(network.getId(), OWNER,
                        List.of(patch(created.id(), null, false))));

        assertThat(nodes.findById(created.id()).orElseThrow().getCaption()).isEqualTo("Nordic hub");
    }

    // -------------------------------------------------------------------------- fixtures

    private static NodeRequest node(String name, NodeType type, String caption,
            Boolean captionVisible) {
        return new NodeRequest(name, type, null, null, 0, 0, 0, null, null, null, null, null,
                caption, captionVisible);
    }

    private static NodePatch patch(Long nodeId, String caption, Boolean captionVisible) {
        return new NodePatch(nodeId, null, null, null, null, null, null, null, null, null, null,
                null, null, caption, captionVisible);
    }

    /** A DONE run, which is what freezes a network — the freeze is these rows (FR-20). */
    private void freeze(Network frozen) {
        SimulationRun run = new SimulationRun(frozen, null, "{}");
        run.setStatus(SimulationStatus.DONE);
        run.setStartedAt(Instant.now());
        run.setFinishedAt(Instant.now());
        runs.save(run);
    }
}
