package com.snrm.network;

import com.snrm.common.DurationDto;
import com.snrm.common.RateDto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/networks/{networkId}/links} — the mouse gesture
 * expressed as a request.
 *
 * <p>Three rules the endpoint enforces beyond these annotations, because none of them can be
 * expressed on a single field:
 * <ul>
 *   <li>{@link #sourceNodeId} and {@link #targetNodeId} must differ — {@code LINK_SELF_LOOP}, 422;</li>
 *   <li>the ordered pair must be new to the network — {@code LINK_DUPLICATE}, 409;</li>
 *   <li>both nodes must belong to the network in the path — {@code LINK_CROSS_NETWORK}, 422.</li>
 * </ul>
 *
 * <p>The attributes default to the column defaults of {@code V2__domain.sql} when omitted, so the
 * editor can create a link from the gesture alone and let the user fill in the property panel
 * afterwards. For the two unit-bearing ones that means zero transit and an unconstrained
 * capacity, stated in the network's period unit — which is also what the panel's unit dropdown
 * opens on.
 *
 * <p>An omitted {@link #captionVisible} means <em>visible</em>, not hidden (FR-30); the rule for
 * every caption path is stated once in {@code Captions}.
 */
@Schema(name = "LinkRequest", description = "A new directed arc and its attributes.")
public record LinkRequest(

        @Schema(description = "Upstream endpoint. Must belong to the network in the path.",
                example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "sourceNodeId is required")
        Long sourceNodeId,

        @Schema(description = "Downstream endpoint. Must belong to the network in the path and "
                + "differ from the source.", example = "2",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "targetNodeId is required")
        Long targetNodeId,

        @Schema(description = "Transit time and its unit, e.g. `{\"value\": 36, \"unit\": "
                + "\"HOUR\"}`. Omit for zero transit.", nullable = true)
        @Valid
        DurationDto leadTime,

        @Schema(description = "Throughput ceiling and the unit it is measured over, e.g. "
                + "`{\"value\": 300, \"timeUnit\": \"DAY\"}`. Omit for unconstrained.",
                nullable = true)
        @Valid
        RateDto capacity,

        @Schema(description = "Cost per unit shipped. Defaults to 0.", example = "1.5",
                defaultValue = "0")
        double unitCost,

        @Schema(description = "Independent per-period failure probability. Defaults to 0.",
                example = "0.01", defaultValue = "0", minimum = "0", maximum = "1")
        @DecimalMin(value = "0", message = "failureProb must be between 0 and 1")
        @DecimalMax(value = "1", message = "failureProb must be between 0 and 1")
        double failureProb,

        @Schema(description = "Short annotation drawn beneath the arc's label (FR-30). Omit — or "
                + "send an empty string — for none.",
                example = "Ocean leg — single carrier", maxLength = 200, nullable = true)
        @Size(max = 200, message = "caption must be at most 200 characters")
        String caption,

        @Schema(description = "Whether the canvas draws the caption. Omitting it means VISIBLE.",
                example = "true", defaultValue = "true", nullable = true)
        Boolean captionVisible) {
}
