package com.snrm.scenario;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/scenarios/{scenarioId}/duplicate}.
 *
 * <p>The whole body is optional — {@code POST} with no body copies the scenario under a derived
 * name. Duplication is how a researcher explores a variation of a disruption story ("the same fire,
 * but two weeks later"), and asking for a name before they know what they are about to change would
 * put the naming decision at the wrong end of the edit.
 *
 * <p>The copy is deep: every event comes with it, timings and units verbatim. A duplicate that
 * shared its events would not be a scenario a run could be submitted against without disturbing the
 * original.
 */
@Schema(name = "DuplicateScenarioRequest",
        description = "Options for copying a scenario. The whole body may be omitted.")
public record DuplicateScenarioRequest(

        @Schema(description = "Name for the copy. Omit for \"<name> (copy)\", with a numeric suffix "
                + "if that is taken too.", example = "Tier-1 plant fire — delayed onset",
                nullable = true)
        @Size(max = 160, message = "name must be at most 160 characters")
        String name) {
}
