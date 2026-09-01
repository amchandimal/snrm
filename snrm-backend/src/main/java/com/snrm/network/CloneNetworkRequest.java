package com.snrm.network;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/networks/{networkId}/clone}.
 *
 * <p>Both fields are optional; an empty object {@code {}} is a valid request and produces an
 * unannotated copy under the base network's name.
 *
 * <p>{@code generatedBy} is deliberately not a field. A clone requested through the API is a user
 * fork from the editor, so it is always {@code MANUAL}; {@code SEARCH} belongs to candidates the
 * Phase 2 configuration engine persists programmatically, and letting a client claim it
 * would corrupt the provenance the comparison view reads.
 *
 * @param name         name for the copy, or null to keep the base network's name and take the next
 *                     version number
 * @param leverChanges structured diff from the base network, stored verbatim in
 *                     {@code configuration_variant.lever_changes_json}
 */
@Schema(name = "CloneNetworkRequest",
        description = "Options for forking a network into a configuration variant.")
public record CloneNetworkRequest(

        @Schema(description = "Name for the copy. Omit to reuse the base network's name, in which "
                + "case the variant simply takes the next version number and the two sort together.",
                example = "Baseline + backup supplier", maxLength = 160, nullable = true)
        @Size(max = 160, message = "name must be at most 160 characters")
        String name,

        @Schema(description = """
                Structured diff from the base network, stored as-is in \
                `configuration_variant.lever_changes_json`. Any JSON object is accepted — the \
                persistence layer stays agnostic of the lever vocabulary the Phase 2 strategy owns. \
                It is what makes lever-level attribution of metric improvements possible, so an \
                empty diff is a missed opportunity rather than an error.""",
                example = """
                        {"sourcing":{"addedBackupSupplier":"SUP-2"},"redundancy":{"capacityMultiplier":1.2}}""",
                nullable = true)
        JsonNode leverChanges) {
}
