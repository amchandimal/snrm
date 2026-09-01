package com.snrm.common;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * A length of time on the wire: the number and the unit it is counted in (FR-13).
 *
 * <p>Every duration in the API is one of these — a link's lead time, a node's processing time, a
 * network's period length, a disruption event's offset and window. There is no bare number
 * anywhere, and that is the point: before FR-13 a lead time was the integer 2, which meant two of
 * whatever the reader assumed, and two networks with different period lengths disagreed about what
 * the same payload said.
 *
 * <p><strong>Both fields are required when the object is sent.</strong> A value without a unit is
 * the bug this type exists to make impossible, so the unit is never inferred from context. What
 * <em>is</em> allowed is omitting the whole object, which the request DTOs document as meaning zero
 * in the owning network's period unit — enough for the editor to create a link from a mouse gesture
 * and let the user fill in the panel afterwards.
 *
 * @param value how many {@code unit}s; fractional values are legitimate — half a day is a lead time
 * @param unit  the unit {@code value} counts
 */
@Schema(name = "Duration",
        description = "A length of time as a value and the unit it is counted in. "
                + "Both fields are required when the object is present; omitting the whole object "
                + "means zero, in the owning network's period unit.")
public record DurationDto(

        @Schema(description = "How many units. Fractional values are allowed.", example = "36",
                requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0")
        @NotNull(message = "value is required")
        @PositiveOrZero(message = "value must not be negative")
        Double value,

        @Schema(description = "The unit the value counts. MONTH is always 30 days and YEAR always "
                + "365 — neither follows the calendar, so a duration converts identically whatever "
                + "date a run starts on.",
                example = "HOUR", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "unit is required")
        TimeUnit unit) {

    /** {@code value} {@code unit}s, for a mapper or a report that has the pair already. */
    public static DurationDto of(double value, TimeUnit unit) {
        return new DurationDto(value, unit);
    }
}
