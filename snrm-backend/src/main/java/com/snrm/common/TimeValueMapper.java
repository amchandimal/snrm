package com.snrm.common;

import org.springframework.stereotype.Component;

/**
 * The read direction of the unit-bearing types: entity value object → API DTO.
 *
 * <p>Named in the {@code uses} of every mapper that touches a duration or a rate, so
 * {@code DurationAmount → DurationDto} and {@code Rate → RateDto} are resolved automatically and no
 * per-field {@code @Mapping} is needed for them.
 *
 * <p><strong>There is deliberately no write direction here.</strong> Turning a DTO into a value
 * object is not a mapping, it is three decisions MapStruct has no way to make: an omitted field
 * means different things per site (unconstrained for a capacity, zero for a demand), the unit it
 * falls back to is the owning network's period unit, and the entity's existing embeddable instance
 * must be written into rather than replaced — {@link DurationAmount} and {@link Rate} have no public
 * constructor precisely so a mapper cannot invent one. {@link TimeValues} does that work, called
 * from the {@code @AfterMapping} hook of each mapper.
 */
@Component
public class TimeValueMapper {

    /** @return the pair as the API states it, or null if the entity has none */
    public DurationDto toDto(DurationAmount amount) {
        return amount == null ? null : DurationDto.of(amount.getValue(), amount.getUnit());
    }

    /** @return the pair as the API states it — a null {@code value} inside means unconstrained */
    public RateDto toDto(Rate rate) {
        return rate == null ? null : RateDto.of(rate.getValue(), rate.getTimeUnit());
    }
}
