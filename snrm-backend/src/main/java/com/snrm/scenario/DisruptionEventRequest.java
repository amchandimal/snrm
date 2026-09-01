package com.snrm.scenario;

import com.snrm.common.DurationDto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/scenarios/{scenarioId}/events} and
 * {@code PUT /api/v1/events/{eventId}} — one bar on the timeline.
 *
 * <p><strong>Everything the annotations cannot say is enforced by
 * {@link DisruptionScenarioService} against the {@code networkId} the request names:</strong>
 *
 * <ul>
 *   <li>a NODE or LINK target must be an id in <em>that</em> network, and must not also carry a
 *       region; a REGION target must name a tag at least one of its nodes carries, and must not
 *       carry an id — {@code EVENT_TARGET_INVALID}, 422;</li>
 *   <li>{@link #startOffset} + {@link #duration}, discretised on that network's clock, must land
 *       within its horizon — {@code EVENT_EXCEEDS_HORIZON}, 422.</li>
 * </ul>
 *
 * <p><strong>Why the timing is unit-bearing (FR-13).</strong> Before FR-13 an event said
 * "period 4", and a scenario is project-scoped precisely so it can be replayed against every
 * configuration variant — variants that need not share a period length. "Period 4" in a
 * network stepping in hours and in one stepping in weeks are different moments, so the comparison
 * the scenario exists to support would have been measuring the units. Both fields are therefore real
 * durations measured from the start of the horizon, discretised against whichever network a run
 * evaluates.
 *
 * <p>Neither may be omitted, unlike the durations on a node or a link: a bar with no position and no
 * length is not a partially specified event, it is not an event. {@link #duration} must additionally
 * be positive — {@code ck_event_window} — since an event that lasts no time has no recovery to
 * profile.
 */
@Schema(name = "DisruptionEventRequest",
        description = "One disruption within a scenario. Validated against the `networkId` query "
                + "parameter, which names the network the event is authored against.")
public record DisruptionEventRequest(

        @Schema(description = "What the event strikes. NODE and LINK take a `targetId`; REGION "
                + "takes a `targetRegion` and resolves to every node carrying that tag.",
                example = "NODE", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "targetType is required")
        DisruptionTargetType targetType,

        @Schema(description = "`node.id` or `link.id` per `targetType`, belonging to the network in "
                + "`networkId`. Required for NODE and LINK; must be omitted for REGION.",
                example = "3", nullable = true)
        Long targetId,

        @Schema(description = "The `node.region` tag to strike. Required for REGION and must match "
                + "at least one node of the network in `networkId`; must be omitted for NODE and "
                + "LINK.", example = "EU-West", nullable = true)
        @Size(max = 60, message = "targetRegion must be at most 60 characters")
        String targetRegion,

        @Schema(description = "How far into the run the disruption begins, measured from the start "
                + "of the horizon — e.g. `{\"value\": 4, \"unit\": \"WEEK\"}`. A real duration, not "
                + "a count of periods.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "startOffset is required")
        @Valid
        DurationDto startOffset,

        @Schema(description = "How long the disruption lasts, e.g. `{\"value\": 10, \"unit\": "
                + "\"DAY\"}`. Must be positive — an event that lasts no time has no recovery to "
                + "profile.", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "duration is required")
        @Valid
        DurationDto duration,

        @Schema(description = "Capacity-availability multiplier reduction, in [0,1]. 1.0 takes the "
                + "target fully offline for the duration; 0.4 leaves 60% of its capacity.",
                example = "0.6", requiredMode = Schema.RequiredMode.REQUIRED,
                minimum = "0", maximum = "1")
        @NotNull(message = "severity is required")
        @DecimalMin(value = "0", message = "severity must be between 0 and 1")
        @DecimalMax(value = "1", message = "severity must be between 0 and 1")
        Double severity,

        @Schema(description = "Shape of the ramp back to nominal across the recovery window. Omit "
                + "for STEP — availability held at the disrupted level for the whole duration, then "
                + "restored.",
                example = "LINEAR", defaultValue = "STEP", nullable = true)
        RecoveryProfileType recoveryProfile,

        @Schema(description = "Probability the event occurs at all in a given replication, in [0,1]. "
                + "Omit for 1.0, which makes it deterministic — a scenario of certain events is a "
                + "single future rather than a distribution of them.",
                example = "0.25", defaultValue = "1", minimum = "0", maximum = "1", nullable = true)
        @DecimalMin(value = "0", message = "probability must be between 0 and 1")
        @DecimalMax(value = "1", message = "probability must be between 0 and 1")
        Double probability) {
}
