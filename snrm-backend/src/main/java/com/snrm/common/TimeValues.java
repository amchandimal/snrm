package com.snrm.common;

/**
 * The write direction of the unit-bearing types: API DTO → entity value object (FR-13).
 *
 * <p>Four rules, applied identically by every mapper, which together are the whole PUT/PATCH
 * contract for a duration or a rate:
 *
 * <ol>
 *   <li><strong>Present means both parts.</strong> A {@link DurationDto} or {@link RateDto} that
 *       arrives carries a unit — the DTOs make it a validation error not to — so a stated value is
 *       never reinterpreted against a unit the client did not choose.</li>
 *   <li><strong>Omitted, on a PUT, means the field's empty form</strong> — unconstrained for a
 *       capacity ({@link #replaceCapacity}), zero for a demand or a holding cost
 *       ({@link #replaceQuantity}), zero for a duration ({@link #replace}) — stated in the owning
 *       network's period unit. That unit is the only defensible fallback: it is the one unit the
 *       network is guaranteed to have an opinion about, and a zero or an unconstrained capacity
 *       carries no arithmetic that the choice could distort.</li>
 *   <li><strong>Omitted, on a PATCH, means unchanged</strong> ({@link #patch}) — the contract the
 *       bulk canvas endpoints depend on across a multi-selection.</li>
 *   <li><strong>The entity's own instance is written into, not replaced</strong>, whenever it has
 *       one. An embeddable is owned by exactly one entity ({@link DurationAmount}), and swapping
 *       instances around is how two rows end up sharing one.</li>
 * </ol>
 *
 * <p>Each method returns the amount to store, which is the instance passed in whenever there was
 * one. Call sites read {@code node.setProcessingTime(TimeValues.replace(node.getProcessingTime(),
 * …))} so a null embeddable — possible in principle, never in practice given the field defaults —
 * is filled rather than dereferenced.
 */
public final class TimeValues {

    private TimeValues() {
    }

    // ------------------------------------------------------------------ durations

    /**
     * Full replacement of a duration: an omitted field becomes zero in {@code fallbackUnit}.
     *
     * @param target       the entity's current amount, or null if it has none
     * @param dto          the stated duration, or null if the client omitted it
     * @param fallbackUnit the owning network's period unit
     * @return the amount to store
     */
    public static DurationAmount replace(DurationAmount target, DurationDto dto,
            TimeUnit fallbackUnit) {
        if (dto == null) {
            return set(target, 0, fallbackUnit);
        }
        return set(target, dto.value(), dto.unit());
    }

    /**
     * Partial edit of a duration: an omitted field is left exactly as it was, unit included.
     *
     * @return the amount to store
     */
    public static DurationAmount patch(DurationAmount target, DurationDto dto) {
        return dto == null ? target : set(target, dto.value(), dto.unit());
    }

    // ---------------------------------------------------------------------- rates

    /**
     * Full replacement of a capacity: an omitted field clears it back to unconstrained, keeping a
     * unit so the pair stays well-formed ({@link Rate}).
     *
     * @return the rate to store
     */
    public static Rate replaceCapacity(Rate target, RateDto dto, TimeUnit fallbackUnit) {
        if (dto == null) {
            return set(target, null, unitOf(target, fallbackUnit));
        }
        return set(target, dto.value(), dto.timeUnit());
    }

    /**
     * Full replacement of a quantity rate — demand, holding cost — whose column is not nullable: an
     * omitted field becomes zero in {@code fallbackUnit}.
     *
     * <p>A null {@code value} inside a present DTO is treated as zero here for the same reason: the
     * field has no unconstrained form to express.
     *
     * @return the rate to store
     */
    public static Rate replaceQuantity(Rate target, RateDto dto, TimeUnit fallbackUnit) {
        if (dto == null) {
            return set(target, 0d, fallbackUnit);
        }
        return set(target, dto.value() == null ? 0d : dto.value(), dto.timeUnit());
    }

    /**
     * Partial edit of a rate: an omitted field is left exactly as it was, unit included.
     *
     * <p>Which means a PATCH cannot clear a capacity back to unconstrained — null already means
     * "leave alone", the same limitation every other patchable field has. PUT does that.
     *
     * @return the rate to store
     */
    public static Rate patch(Rate target, RateDto dto) {
        return dto == null ? target : set(target, dto.value(), dto.timeUnit());
    }

    // -------------------------------------------------------------------- helpers

    private static DurationAmount set(DurationAmount target, double value, TimeUnit unit) {
        if (target == null) {
            return DurationAmount.of(value, unit);
        }
        target.setUnit(unit);
        target.setValue(value); // refreshes the derived seconds; order does not matter, both do
        return target;
    }

    private static Rate set(Rate target, Double value, TimeUnit unit) {
        if (target == null) {
            return Rate.of(value, unit);
        }
        target.setTimeUnit(unit);
        target.setValue(value);
        return target;
    }

    /** The unit an existing rate is stated in, or the network's period unit if it has none. */
    private static TimeUnit unitOf(Rate target, TimeUnit fallbackUnit) {
        return target == null || target.getTimeUnit() == null ? fallbackUnit : target.getTimeUnit();
    }
}
