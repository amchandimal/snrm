package com.snrm.network;

/**
 * How badly a resolution finding should be taken.
 *
 * <p>Only two levels, because the distinction this enum
 * draws is operational rather than cosmetic: a warning is a banner the researcher may dismiss and go
 * on modelling, an error is something the import wizard refuses to complete.
 *
 * <p>Declared in increasing order, so {@code compareTo} ranks findings and the report can state its
 * worst.
 */
public enum TimeSeverity {

    /** Worth telling the user; the model still runs and may well be what they meant. */
    WARNING,

    /** The model as stated does not mean what it says; import refuses it. */
    ERROR
}
