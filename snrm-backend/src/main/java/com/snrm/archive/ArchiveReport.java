package com.snrm.archive;

import com.snrm.archive.ProjectBundle.Counts;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * What a restore did, and everything about it a reader of the results has to know.
 *
 * <p>Modelled on {@code ImportReport}: a completed restore that has something to say returns 200
 * carrying this, not a problem document. The findings here are not row errors — an archive that
 * parses has already been written by this tool — they are the three ways a restore can succeed and
 * still mislead, and each is a sentence a researcher has to read before comparing anything.
 *
 * @param projectId       the new project
 * @param projectName     its name, which is suffixed if the owner already had one by that name
 * @param sourceCounts    what the bundle's manifest claimed to hold
 * @param restoredCounts  what was actually written; a difference is always explained by a finding
 * @param engineVersion   the engine that produced the archived runs
 * @param engineMatches   whether that is this instance's engine version. When false, every restored
 *                        run's numbers came from a different model and must not be put beside a
 *                        locally computed run without re-running them
 * @param findings        warnings, most consequential first; empty on a clean restore
 */
@Schema(name = "ArchiveReport",
        description = "The outcome of restoring a project archive.")
public record ArchiveReport(

        @Schema(description = "The project created by the restore.", example = "7")
        Long projectId,

        @Schema(description = "Its name.", example = "Resilience study (restored)")
        String projectName,

        @Schema(description = "What the archive's manifest claimed to contain.")
        Counts sourceCounts,

        @Schema(description = "What was written. Any shortfall against sourceCounts is explained "
                + "by a finding.")
        Counts restoredCounts,

        @Schema(description = "The simulation engine version that produced the archived runs, read "
                + "from the archive's manifest.", example = "1.0")
        String engineVersion,

        @Schema(description = "Whether that engine is the one this installation runs. False means "
                + "the restored results were computed by a different model: they are readable and "
                + "citable, but comparing them against a run computed here compares two engines "
                + "as much as two networks.", example = "true")
        boolean engineMatches,

        @Schema(description = "Warnings, most consequential first. A restore that returns none "
                + "reproduced the archived experiment exactly.")
        List<Finding> findings) {

    /**
     * One thing about the restore worth knowing.
     *
     * @param code    stable identifier, so a client branches on the rule and not on the prose
     * @param subject what it is about — a scenario name, a network key, an event's target
     * @param message the whole of it, in a sentence a researcher can act on
     */
    @Schema(name = "ArchiveFinding")
    public record Finding(

            @Schema(description = "Stable code: ENGINE_VERSION_MISMATCH, ARCHIVE_IS_SUBSET, "
                    + "EVENT_TARGET_UNRESOLVED, METRIC_SCOPE_UNRESOLVED, NETWORK_VERSION_ADJUSTED "
                    + "or PROJECT_RENAMED.",
                    example = "ENGINE_VERSION_MISMATCH")
            String code,

            @Schema(description = "What the finding is about.", example = "Plant outage")
            String subject,

            @Schema(description = "The finding in full.")
            String message) {
    }

    /** The archived runs were produced by an engine this instance is not running. */
    public static final String ENGINE_VERSION_MISMATCH = "ENGINE_VERSION_MISMATCH";

    /**
     * The archive carried some of a project's networks rather than all of them (FR-24).
     *
     * <p>Not a fault, and not a shortfall against {@code sourceCounts} either — the manifest counted
     * what the subset holds, so the arithmetic agrees. It is here because the restored project looks
     * complete: a catalogue with entries no network uses, a scenario refusing to run, a variant with
     * no recorded parent. This finding is what makes those three legible as consequences of a
     * selection rather than as damage.
     */
    public static final String ARCHIVE_IS_SUBSET = "ARCHIVE_IS_SUBSET";

    /** An event's target did not resolve — see {@link ProjectBundle.ElementRef}. */
    public static final String EVENT_TARGET_UNRESOLVED = "EVENT_TARGET_UNRESOLVED";

    /** A node- or link-scoped metric result named an element the restored network does not have. */
    public static final String METRIC_SCOPE_UNRESOLVED = "METRIC_SCOPE_UNRESOLVED";

    /** A network could not keep its archived version number. */
    public static final String NETWORK_VERSION_ADJUSTED = "NETWORK_VERSION_ADJUSTED";

    /** The project's archived name was already taken by this owner ({@code uq_project}). */
    public static final String PROJECT_RENAMED = "PROJECT_RENAMED";
}
