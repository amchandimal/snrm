package com.snrm.metrics;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Something the reader of a comparison has to know before reading it.
 *
 * <p>A comparison can be well-formed and still misleading, and the two ways that happens are both
 * invisible in the numbers themselves: variants clocked in different period lengths, and variants
 * evaluated against different disruption scenarios. Neither is refused — a researcher comparing a
 * daily model against an hourly one is doing something legitimate, and so is one comparing a
 * network's behaviour under two different stories — but neither may be silent.
 *
 * <p>A code and a message rather than a message alone, for the same reason the import report carries
 * codes: the code is the contract a client styles and filters on, the message is prose
 * that may be reworded without breaking anything.
 *
 * @param code    a stable identifier from the constants below
 * @param message one sentence a researcher can act on
 */
@Schema(name = "ComparisonNote",
        description = "A caveat attached to the whole comparison — a mixed time base, a mixed "
                + "scenario, a variant with no completed run.")
public record ComparisonNoteDto(

        @Schema(description = "Stable code: MIXED_TIME_BASES, MIXED_SCENARIOS, NO_RUN or "
                + "PARTIAL_SUITE.", example = "MIXED_TIME_BASES")
        String code,

        @Schema(description = "One sentence naming what is uneven and what was done about it.",
                example = "Compared configurations do not share a period length (1 DAY, 6 HOUR); "
                        + "time-valued metrics have been converted to HOUR.")
        String message) {

    /**
     * The compared variants do not share a period length.
     *
     * <p>Time-valued metrics have already been converted to a common unit by the time a client sees
     * them, so the numbers are comparable — but the <em>models</em> are not identical, and a
     * difference in `TTR` between a daily and an hourly variant is partly a difference in what the
     * two can resolve.
     */
    public static final String MIXED_TIME_BASES = "MIXED_TIME_BASES";

    /**
     * The runs behind the columns applied different disruption scenarios.
     *
     * <p>The comparison then measures the scenarios as much as the configurations. Naming
     * {@code scenarioId} on the request pins every column to one story and removes this note.
     */
    public static final String MIXED_SCENARIOS = "MIXED_SCENARIOS";

    /** At least one variant has no completed run, so its simulated cells are empty. */
    public static final String NO_RUN = "NO_RUN";

    /** A metric is missing from at least one column that has it in another. */
    public static final String PARTIAL_SUITE = "PARTIAL_SUITE";
}
