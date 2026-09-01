package com.snrm.dataimport;

/**
 * How badly an import diagnostic should be taken.
 *
 * <p>Two levels, and the distinction is operational rather than cosmetic: one {@link #ERROR} anywhere
 * in the report means no network is created, because the import is transactional — there is
 * no partial import to inspect afterwards, so the decision has to be made from the report. A
 * {@link #WARNING} is something the researcher should see and may knowingly accept; a lateral DC-to-DC
 * link and a lead time that rounds by 15% are both real modelling choices.
 *
 * <p>Declared in increasing order so {@code compareTo} sorts a report worst-first.
 *
 * <p>Deliberately the same two levels as {@code TimeSeverity} in the network module, which is what
 * lets the findings be folded into this report without a severity mapping table that
 * would have to be kept honest.
 */
public enum ImportSeverity {

    /** Worth telling the user; the import can still proceed. */
    WARNING,

    /** The file does not describe a network that can be built. Nothing is written. */
    ERROR
}
