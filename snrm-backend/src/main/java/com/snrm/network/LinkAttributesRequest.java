package com.snrm.network;

import com.snrm.common.DurationDto;
import com.snrm.common.RateDto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PUT /api/v1/links/{linkId}} — full replacement of a link's attributes.
 *
 * <p>The endpoints are not fields. Repointing a link at a different node is not an edit of that arc
 * but a different arc altogether: it would have to be re-checked for self-loops, duplicates and
 * cross-network references, and any metric result computed over the old topology would silently
 * stop matching the network it was computed from. Delete the link and draw the new one.
 *
 * <p>As a PUT, an omitted {@link #capacity} clears it back to unconstrained and an omitted
 * {@link #leadTime} to zero transit, both in the network's period unit; the primitives
 * default to 0. Use {@code PATCH /networks/{networkId}/links} to change one attribute and leave the
 * rest alone.
 *
 * <p>An omitted or blank {@link #caption} clears it, and an omitted {@link #captionVisible} means
 * <em>visible</em> rather than hidden (FR-30); {@code Captions} states the rule for every path.
 */
@Schema(name = "LinkAttributesRequest",
        description = "Full replacement of a link's attributes. Endpoints cannot be changed — "
                + "delete and redraw instead.")
public record LinkAttributesRequest(

        @Schema(description = "Transit time and its unit. Omit to clear it to zero "
                + "transit.", nullable = true)
        @Valid
        DurationDto leadTime,

        @Schema(description = "Throughput ceiling and the unit it is measured over. Omit to clear "
                + "it to unconstrained.", nullable = true)
        @Valid
        RateDto capacity,

        @Schema(description = "Cost per unit shipped. Defaults to 0.", example = "1.75",
                defaultValue = "0")
        double unitCost,

        @Schema(description = "Independent per-period failure probability. Defaults to 0.",
                example = "0.01", defaultValue = "0", minimum = "0", maximum = "1")
        @DecimalMin(value = "0", message = "failureProb must be between 0 and 1")
        @DecimalMax(value = "1", message = "failureProb must be between 0 and 1")
        double failureProb,

        @Schema(description = "Short annotation drawn beneath the arc's label (FR-30). Omit — or "
                + "send an empty string — to clear it.",
                example = "Ocean leg — single carrier", maxLength = 200, nullable = true)
        @Size(max = 200, message = "caption must be at most 200 characters")
        String caption,

        @Schema(description = "Whether the canvas draws the caption. Omitting it means VISIBLE.",
                example = "true", defaultValue = "true", nullable = true)
        Boolean captionVisible) {
}
