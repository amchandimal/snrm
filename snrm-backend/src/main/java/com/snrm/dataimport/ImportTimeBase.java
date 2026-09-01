package com.snrm.dataimport;

import com.snrm.common.DurationDto;
import com.snrm.common.RoundingPolicy;
import com.snrm.dataimport.StagedNetwork.TimeBaseSpec;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The time base an import will give the network, on the wire.
 *
 * <p>Carried by both the preview and the report so that the wizard can show
 * the clock it is about to create and ask for confirmation when it is a default rather than something
 * the file said. {@link #declared()} is that distinction, and it is the whole reason this is a type
 * rather than three loose fields — "1 day because the file said so" and "1 day because nothing said
 * otherwise" call for different UI and lead to different mistakes.
 *
 * @param periodLength    length of one simulation period
 * @param horizonPeriods  how many periods a run covers
 * @param roundingPolicy  how a duration that does not divide evenly is resolved
 * @param declared        true when {@code network_meta} or the wizard supplied this; false for the
 *                        defaults, which the wizard must confirm
 */
@Schema(name = "ImportTimeBase",
        description = "The clock an import will give the network: period, horizon and rounding "
                + "policy, and whether the file declared them or they are the defaults.")
public record ImportTimeBase(

        @Schema(description = "Length of one simulation period.")
        DurationDto periodLength,

        @Schema(description = "How many periods a run over this network covers.", example = "52")
        int horizonPeriods,

        @Schema(description = "How a duration that does not divide evenly into the period is rounded.",
                example = "NEAREST")
        RoundingPolicy roundingPolicy,

        @Schema(description = "False when these are the 1 DAY / 52 / NEAREST defaults because no "
                + "network_meta sheet was supplied — the wizard asks the user to confirm them, since "
                + "every value in a column without a unit is read in this period's unit.",
                example = "true")
        boolean declared) {

    static ImportTimeBase of(TimeBaseSpec spec) {
        return new ImportTimeBase(
                DurationDto.of(spec.periodLength().getValue(), spec.periodLength().getUnit()),
                spec.horizonPeriods(), spec.roundingPolicy(), spec.declared());
    }
}
