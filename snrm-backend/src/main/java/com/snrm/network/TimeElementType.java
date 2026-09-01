package com.snrm.network;

/**
 * What kind of thing a {@link TimeFinding} is about, so the editor can select it on the canvas when
 * the user clicks the entry in the warning banner.
 *
 * <p>The three element kinds that own a duration in the model. Networks are not in the list:
 * a network owns the period itself, and a finding against the yardstick would have nothing to
 * measure.
 */
public enum TimeElementType {

    /** {@code node.processing_time}. */
    NODE,

    /** {@code link.lead_time}. */
    LINK,

    /** {@code disruption_event.start_offset} and {@code duration}. */
    DISRUPTION_EVENT
}
