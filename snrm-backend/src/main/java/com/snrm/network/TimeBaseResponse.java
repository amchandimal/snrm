package com.snrm.network;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What {@code PUT /api/v1/networks/{networkId}/time-base} returns: the network with its new clock,
 * and what that clock does to every duration under it.
 *
 * <p>Two things rather than one because the resolution checks run on network save, and
 * the moment a researcher changes a period is precisely the moment a 6-hour lead time stops being
 * representable. Returning the report with the save means the editor's warning banner can
 * be filled from the response that caused it, instead of the client having to know to ask.
 *
 * @param network    the network as it now stands, including the new period, horizon and policy
 * @param validation every duration that converts badly onto it, plus the suggested period
 */
@Schema(name = "TimeBaseResponse",
        description = "The updated network and the resolution findings its new time base produces.")
public record TimeBaseResponse(

        @Schema(description = "The network as it now stands.")
        NetworkDto network,

        @Schema(description = "What the new clock does to the network's declared durations. Empty "
                + "findings mean every duration survived the change intact.")
        TimeValidationReport validation) {
}
