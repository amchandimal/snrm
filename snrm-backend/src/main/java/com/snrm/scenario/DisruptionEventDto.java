package com.snrm.scenario;

import com.snrm.common.DurationDto;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * One disruption within a scenario, as the API returns it — one bar on the timeline
 * ({@code DISRUPTION_EVENT}).
 *
 * <p><strong>The timing comes back in the unit it was entered in</strong>, never restated into
 * periods (FR-13). An event stated as "starts after 4 weeks" must read back as 4 weeks,
 * because a scenario is project-scoped and the variants it will be replayed against need not share a
 * period length: converting on the way out would make the stored event mean whichever network was
 * asked last. The timeline positions its bars by the converted value and labels them with the
 * declared one, and it does that conversion itself against the network the user is looking at.
 *
 * @param id             surrogate key
 * @param scenarioId     owning scenario
 * @param targetType     what the event strikes
 * @param targetId       {@code node.id} or {@code link.id}; null for a REGION event
 * @param targetRegion   the {@code node.region} tag; null for a NODE or LINK event
 * @param startOffset    how far into the run the disruption begins, measured from the start of the
 *                       horizon
 * @param duration       how long it lasts; also parameterises the recovery profile
 * @param severity       capacity-availability multiplier reduction in [0,1]; 1.0 is fully offline
 * @param recoveryProfile shape of the ramp back to nominal
 * @param probability    chance the event occurs at all in a given replication
 * @param createdAt      audit timestamp
 * @param updatedAt      audit timestamp
 */
@Schema(name = "DisruptionEvent",
        description = "One disruption within a scenario: what is struck, when, how hard, and how it "
                + "recovers.")
public record DisruptionEventDto(

        @Schema(description = "Surrogate key.", example = "1")
        Long id,

        @Schema(description = "Owning scenario.", example = "1")
        Long scenarioId,

        @Schema(description = "What the event strikes: a single NODE, a single LINK, or every node "
                + "carrying a REGION tag.", example = "NODE")
        DisruptionTargetType targetType,

        @Schema(description = "`node.id` or `link.id` per `targetType`. Null for a REGION event.",
                example = "3", nullable = true)
        Long targetId,

        @Schema(description = "The `node.region` tag a REGION event strikes. Null for NODE and LINK "
                + "events. Which nodes it resolves to is a property of a network, not of the "
                + "scenario — ask `GET /api/v1/networks/{id}/region-nodes`.",
                example = "EU-West", nullable = true)
        String targetRegion,

        @Schema(description = "How far into the run the disruption begins, measured from the start "
                + "of the horizon, in the unit it was entered in.")
        DurationDto startOffset,

        @Schema(description = "How long the disruption lasts, in its own unit. Also parameterises "
                + "the recovery profile.")
        DurationDto duration,

        @Schema(description = "Capacity-availability multiplier reduction, in [0,1]. 1.0 takes the "
                + "target fully offline for the duration.", example = "0.6")
        double severity,

        @Schema(description = "Shape of the ramp back to nominal availability across the recovery "
                + "window.", example = "LINEAR")
        RecoveryProfileType recoveryProfile,

        @Schema(description = "Probability the event occurs at all in a given replication; 1.0 "
                + "makes it deterministic.", example = "0.25")
        double probability,

        @Schema(description = "When the event was created (UTC).")
        Instant createdAt,

        @Schema(description = "When the event last changed (UTC).")
        Instant updatedAt) {
}
