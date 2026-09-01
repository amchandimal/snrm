package com.snrm.network;

import com.snrm.common.DurationDto;
import com.snrm.common.RoundingPolicy;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * What {@code GET /api/v1/networks/{id}/time-validation} returns: the network's clock, everything
 * that converts badly onto it, and the period that would fix it.
 *
 * <p>Also the second half of the {@code PUT /networks/{id}/time-base} response, because these
 * checks run on network save — the banner data arrives with the save that caused it rather
 * than on a second round trip.
 *
 * <p>The clock is echoed back rather than left for the client to remember, so a report is
 * self-contained evidence: a finding that says "rounds to 2 periods" is only interpretable next to
 * the period length it was computed against.
 *
 * @param networkId       the network these findings are about
 * @param scenarioId      the scenario whose events were checked, or null if none was named
 * @param context         which severity rule was applied
 * @param periodLength    the network's period, as declared
 * @param roundingPolicy  how remainders were resolved
 * @param horizonPeriods  how many periods a run covers
 * @param suggestedPeriod the coarsest period keeping every duration within 10%, or null
 * @param errorCount      findings of severity ERROR
 * @param warningCount    findings of severity WARNING
 * @param findings        every finding, worst first
 */
@Schema(name = "TimeValidationReport",
        description = "Resolution warnings for a network's time base, plus the suggested "
                + "period.")
public record TimeValidationReport(

        @Schema(description = "The network these findings are about.", example = "1")
        Long networkId,

        @Schema(description = "The scenario whose events were checked against this network's "
                + "horizon. Null when the request named none, in which case no "
                + "EVENT_EXCEEDS_HORIZON finding can appear.", example = "3", nullable = true)
        Long scenarioId,

        @Schema(description = "Which severity rule was applied. In IMPORT a duration that converts "
                + "to zero periods is an error; in EDITOR it is a warning.",
                example = "EDITOR")
        TimeValidationContext context,

        @Schema(description = "The network's period length, as declared.")
        DurationDto periodLength,

        @Schema(description = "How a duration that does not divide evenly into the period is "
                + "rounded.", example = "NEAREST")
        RoundingPolicy roundingPolicy,

        @Schema(description = "How many periods a run over this network covers.",
                example = "52")
        int horizonPeriods,

        @Schema(description = "The coarsest period that keeps every declared duration within the "
                + "10% tolerance — what the editor's \"suggest period\" action offers. "
                + "Null when the network declares no positive duration, so nothing "
                + "constrains the choice.", nullable = true)
        DurationDto suggestedPeriod,

        @Schema(description = "How many findings are errors.", example = "0")
        int errorCount,

        @Schema(description = "How many findings are warnings.", example = "2")
        int warningCount,

        @Schema(description = "Every finding, worst severity first, then by element.")
        List<TimeFinding> findings) {

    /** Defensive copy, so no caller can hand out a mutable report. */
    public TimeValidationReport {
        findings = findings == null ? List.of() : List.copyOf(findings);
    }
}
