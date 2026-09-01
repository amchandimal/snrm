package com.snrm.network;

import com.snrm.common.DurationDto;
import com.snrm.common.RoundingPolicy;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code PUT /api/v1/networks/{networkId}/time-base} — the network's clock.
 *
 * <p>All three fields are required, because this is the one place they are set together and a
 * partial time base is not a thing: changing the period without revisiting the horizon silently
 * changes how much time a run covers, and the rounding policy is what decides whether the change
 * loses any durations at all.
 *
 * <p>The period must be strictly positive — every conversion in {@link com.snrm.common.TimeBasis}
 * divides by it, and {@code ck_network_period} says the same thing in the schema.
 *
 * @param periodLength    length of one simulation period, e.g. 1 DAY
 * @param horizonPeriods  how many of them a run covers
 * @param roundingPolicy  how a duration that does not divide evenly into the period is resolved
 */
@Schema(name = "TimeBaseRequest",
        description = "A network's period length, horizon and rounding policy — the clock every "
                + "duration in the network is discretised against.")
public record TimeBaseRequest(

        @Schema(description = "Length of one simulation period, e.g. "
                + "`{\"value\": 1, \"unit\": \"DAY\"}`. Must be greater than zero.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "periodLength is required")
        @Valid
        DurationDto periodLength,

        @Schema(description = "How many periods a run over this network covers. Changing "
                + "the period without changing this changes how much wall-clock time a run spans.",
                example = "52", requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1")
        @NotNull(message = "horizonPeriods is required")
        @Min(value = 1, message = "horizonPeriods must be at least 1")
        Integer horizonPeriods,

        @Schema(description = "How a duration that does not divide evenly into the period is "
                + "rounded. NEAREST is unbiased over many values; DOWN systematically understates "
                + "lead times and disruption durations and so flatters the network, UP the "
                + "opposite.",
                example = "NEAREST", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "roundingPolicy is required")
        RoundingPolicy roundingPolicy) {
}
