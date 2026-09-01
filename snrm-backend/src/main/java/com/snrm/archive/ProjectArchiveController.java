package com.snrm.archive;

import com.snrm.archive.ProjectArchiveService.Archive;
import com.snrm.auth.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Project archive and restore — the reproducibility half.
 *
 * <blockquote>"a project export/import (JSON bundle) so an entire experiment is archivable alongside
 * the thesis"</blockquote>
 *
 * <p>Two endpoints, deliberately asymmetric in shape. The archive is a {@code GET} that streams a
 * file, like the network export beside it. The restore is a multipart {@code POST} that answers with
 * a JSON report rather than with the created project, because a restore that succeeded can still have
 * things to say — an engine version this installation does not run, an event target that no longer
 * resolves — and those are the whole reason to read the response.
 *
 * <p><strong>The archive takes an optional {@code ?networkIds=} (FR-24)</strong> and narrows itself
 * to those networks — one endpoint rather than two, because the subset is restored by the same
 * {@code POST} into the same kind of new project, and a second path would be a second archive writer
 * to keep in step with {@link ArchiveSchema}. Omitted, the response is unchanged in every respect,
 * filename included.
 *
 * <p><strong>A restore with findings still succeeds.</strong> The same judgement
 * {@code NetworkImportController} makes for a failed validation: {@link ArchiveReport} is the answer,
 * and a problem document has nowhere to put it. The restore answers 201 because it did create a
 * project; only an unreadable archive — not a zip, no manifest, a format version this build does not
 * know — is a 4xx, through {@link ArchiveFormatException}.
 */
@Tag(name = "Project archive",
        description = "Export a whole experiment as one file and restore it into a new project.")
@RestController
public class ProjectArchiveController {

    private final ProjectArchiveService archives;
    private final ProjectRestoreService restores;
    private final CurrentUser currentUser;

    ProjectArchiveController(ProjectArchiveService archives, ProjectRestoreService restores,
            CurrentUser currentUser) {
        this.archives = archives;
        this.restores = restores;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Archive a whole project",
            description = """
                    Everything needed to reconstruct one experiment, as a single `.zip`: the \
                    project, its product catalogue, every network, every configuration variant's \
                    lineage, every disruption scenario with its events, and every **completed** run \
                    with its resolved parameter set, its metric results and its full time series.

                    **Why this exists.** The XML export carries a network's inputs — \
                    structure, units, layout — and nothing else: no scenario, no run, no seed, no \
                    result. A reader given only that can rebuild what was modelled but not what was \
                    found. This is the mitigation against models being validated on \
                    synthetic or single-case data alone.

                    **What is inside.** `bundle.json` holds the manifest and every experiment-level \
                    record; `networks/*.xml` holds one interchange document per network, \
                    byte-identical to what `GET /networks/{id}/export?format=xml` returns — so a \
                    single network can be lifted out of the archive and imported on its own.

                    **What is left out.** Runs that failed, were cancelled, or are still queued. \
                    They carry no results, and the rule that a result is interpretable only \
                    beside the structure that produced it leaves nothing to interpret.

                    **The engine version travels with it.** The manifest records the simulation \
                    engine that produced the archived runs, and each run keeps its own in \
                    `params.engineVersion`. A stored parameter set replays exactly only against the \
                    engine that wrote it, and the restore reports any mismatch.

                    ### Narrowing it to some of the networks (`?networkIds=`, FR-24)

                    Give `networkIds` and the archive is the same archive narrowed to those \
                    networks, so restoring it through `POST /projects/archive/import` yields a \
                    separate project holding exactly them. **Omit it and nothing changes**: the \
                    whole project travels, byte for byte as before.

                    **It copies and never moves.** The selected networks, their runs and their \
                    results stay in the project that computed them. Nothing here writes anything.

                    **A named network that is not in this project is refused**, naming the id — \
                    never a silently smaller archive, which would restore into a project quietly \
                    missing a configuration.

                    Three rules decide what a subset carries, and each is the safe answer rather \
                    than the tidy one.

                    - **The whole product catalogue travels.** A product is project-scoped and a \
                      `node_product` row names one by name, so computing the used subset risks \
                      writing a reference nothing resolves. An unused entry in the restored \
                      project is harmless; a dangling one is not.
                    - **Every scenario travels.** A scenario is project-scoped and replayable \
                      across variants. An event whose target sits in a network you did not select \
                      restores through the dangling-target convention already established for a \
                      target that was dangling to begin with — a negative `target_id` — so the \
                      scenario visibly refuses a submission with `EVENT_TARGET_UNRESOLVED` rather \
                      than running with a disruption silently missing.
                    - **A variant edge whose base network is not selected is dropped**, its fork \
                      note having no parent to hang from, and the manifest **counts** it rather \
                      than passing over it. The restore turns that count into an \
                      `ARCHIVE_IS_SUBSET` finding.

                    The subset's `bundle.json` carries a `manifest.selection`; a whole-project \
                    archive carries no such member at all.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The archive.",
                    content = @Content(mediaType = "application/zip",
                            schema = @Schema(type = "string", format = "binary"))),
            @ApiResponse(responseCode = "400",
                    description = "networkIds was given but names no network.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404",
                    description = "No such project for this user, or a named network is not in it.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/api/v1/projects/{projectId}/archive")
    public ResponseEntity<Resource> archive(
            @Parameter(description = "Project id.", example = "1") @PathVariable long projectId,

            @Parameter(description = "Archive only these networks of the project (FR-24). Repeat "
                    + "the parameter or comma-separate. Omit it to archive the whole project, "
                    + "which is what this endpoint did before subsets existed and still does "
                    + "unchanged. Every id must belong to this project.", example = "1,4,5")
            @RequestParam(value = "networkIds", required = false) List<Long> networkIds) {

        Archive archive = archives.archive(projectId, currentUser.ownerId(), networkIds);

        // Both filename and filename*, as the network export does: the ASCII fallback keeps old
        // clients working and the UTF-8 form carries a project name with an accent in it intact.
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(archive.filename(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(HttpHeaders.CONTENT_TYPE, archive.contentType())
                .contentLength(archive.content().length)
                .body(new ByteArrayResource(archive.content()));
    }

    @Operation(summary = "Restore an archived project",
            description = """
                    Reads an archive produced by `GET /projects/{id}/archive` and rebuilds the \
                    experiment **into a new project**. Nothing existing is read or modified.

                    **Never a merge.** A merge would have to decide what happens when the archive's \
                    "Baseline v1" meets a different network of the same name, and every answer to \
                    that silently overwrites or silently renames somebody's results. Restoring \
                    alongside lets the two be compared through the ordinary comparison view \
                    instead.

                    **Restored runs are marked.** Every run this creates carries `importedAt` and \
                    the `sourceRunId` it held where it was computed. A restored run is a genuine \
                    `DONE` run — it freezes its network and appears in the comparison \
                    view — so without the mark, a number computed on another machine would be \
                    indistinguishable from one this installation produced. Identity is re-created \
                    rather than preserved: ids belong to the database that issued them, and \
                    preserving them would make importing the same archive twice impossible.

                    **Read `engineMatches` before comparing anything.** False means the archived \
                    results came from a different simulation engine. They are restored, readable \
                    and citable, but putting them beside a locally computed run compares two \
                    engines as much as two networks. It is a warning and not a refusal, \
                    because an archive's purpose is to be legible later — and "later" is exactly \
                    when the engine has moved on.

                    **A restore with findings still succeeds.** `findings` is the answer; read it. \
                    Only an unreadable archive is a 4xx.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201",
                    description = "The project was restored; `Location` names it. Read `findings` "
                            + "and `engineMatches`.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ArchiveReport.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "422",
                    description = "The upload is not a readable project archive, or declares a "
                            + "format version this build does not read (`ARCHIVE_UNREADABLE`).",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping(value = "/api/v1/projects/archive/import",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ArchiveReport> restore(
            @Parameter(description = "The `.zip` produced by GET /projects/{id}/archive.")
            @RequestParam("file") MultipartFile file,
            @Parameter(description = "Name for the restored project. Omit to take the archived "
                    + "name; either way it is suffixed if this user already has one by that name.",
                    example = "Resilience study (replication)")
            @RequestParam(value = "name", required = false) String name) {

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Send the archive as a 'file' part. It is the .zip "
                    + "that GET /projects/{id}/archive returns.");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("Could not read the uploaded archive", unreadable);
        }

        ArchiveReport report = restores.restore(content, name, currentUser.ownerId());
        // 201 with a Location: the restore created a project, and the report is about that project.
        return ResponseEntity.created(URI.create("/api/v1/projects/" + report.projectId()))
                .body(report);
    }
}
