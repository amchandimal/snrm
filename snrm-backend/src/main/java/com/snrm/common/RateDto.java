package com.snrm.common;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * A quantity per unit time on the wire: the number and the unit it is measured over (FR-13).
 *
 * <p>Every rate in the API is one of these — a node's and a link's throughput capacity, a
 * node–product row's demand and holding cost. "500 per day" and "500 per week" are different
 * networks, and before FR-13 both were the number 500.
 *
 * <p><strong>A null {@link #value()} means unconstrained</strong>, which is how an uncapped node or
 * link is expressed and what the nullable {@code capacity_per_period} column meant before V3. The
 * unit stays required whenever the object is present, so the pair is always well-formed and giving
 * the capacity a number later does not need a unit invented for it. Omitting the whole object is
 * allowed and documented per field: unconstrained for a capacity, zero for demand and holding cost,
 * in both cases over the owning network's period unit.
 *
 * <p>Unlike {@link DurationDto} a rate never rounds — it is only rescaled onto the period
 * — which is why the resolution validation evaluates durations and not
 * rates: nothing here can be lost, only restated. The property panel still shows the restatement,
 * since 120 per week is 17.14 per period and the second number is the one the engine
 * uses.
 *
 * @param value    the quantity, or null for unconstrained
 * @param timeUnit the unit the quantity is measured over
 */
@Schema(name = "Rate",
        description = "A quantity per unit time, as a value and the unit it is measured over. "
                + "A null value means unconstrained.")
public record RateDto(

        @Schema(description = "The quantity. Null — or the whole object omitted — means "
                + "unconstrained where the field allows it.",
                example = "120", nullable = true, minimum = "0")
        @PositiveOrZero(message = "value must not be negative")
        Double value,

        @Schema(description = "The unit the quantity is measured over. Required whenever the object "
                + "is present, including when the value is null.",
                example = "WEEK", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "timeUnit is required")
        TimeUnit timeUnit) {

    /** {@code value} per {@code timeUnit}, for a mapper or a report that has the pair already. */
    public static RateDto of(Double value, TimeUnit timeUnit) {
        return new RateDto(value, timeUnit);
    }
}
