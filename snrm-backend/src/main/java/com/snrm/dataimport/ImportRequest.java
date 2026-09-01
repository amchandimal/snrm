package com.snrm.dataimport;

import com.fasterxml.jackson.databind.JsonNode;
import com.snrm.common.DurationAmount;
import com.snrm.common.RoundingPolicy;
import com.snrm.common.TimeUnit;
import com.snrm.dataimport.StagedNetwork.TimeBaseSpec;

/**
 * Everything the import needs besides the files themselves.
 *
 * <p>Assembled by the controller from flat multipart form fields rather than from a JSON part. A
 * browser's {@code FormData} appends strings, and the wizard is the primary client; asking it to embed a
 * JSON blob alongside the files, or asking the REST-client scratch file in the repository root to hand-write
 * a mixed multipart body, would add a layer for no gain. The one field that genuinely is structured —
 * the column mapping — is a JSON string, because it is a nested map and there is no flat spelling of it.
 *
 * <p><strong>Why the time base can be overridden.</strong> When {@code network_meta} is absent the
 * wizard asks for confirmation of the 1 DAY default. An answer to that question has to
 * be able to travel with the import, and it has to beat the file — otherwise the question is decorative.
 * When all four parts are absent the file's own {@code network_meta} decides, and when that is absent too
 * the documented defaults apply with a warning.
 *
 * <p><strong>The baseline flag and the variant base are two different things</strong> (FR-28).
 * {@code baseline} is the project's one baseline flag; {@code baseNetworkId} names the
 * network this import is a configuration variant <em>of</em>. The wizard's roles step keeps the two
 * names apart deliberately, because conflating them is how the wrong thing gets marked — a batch
 * imported into a project that already has a baseline sets no flag and still records every edge.
 * Neither implies the other here, and both may be sent.
 *
 * @param projectId       the project to create the network in; products resolve within it
 * @param name            the new network's name; the server assigns the version
 * @param baseline        mark the import as the project's baseline (at most one per project)
 * @param validateOnly    validate and report without creating anything — the wizard's report step
 * @param periodLength    the confirmed period, or null to take the file's
 * @param horizonPeriods  the confirmed horizon, or null
 * @param roundingPolicy  the confirmed policy, or null
 * @param mapping         the user's column mapping from step 2 of the wizard
 * @param baseNetworkId   the network this import is a variant of, recorded as a
 *                        {@code CONFIGURATION_VARIANT} in the same transaction that creates it
 *                        (FR-28); null for an independent network
 * @param leverChanges    the note to store on that edge, or null. Nothing computes one: the tool
 *                        does not diff two networks, so this is what the user wrote or nothing at
 *                        all. Ignored when {@code baseNetworkId} is null, since there is no edge to
 *                        carry it
 */
public record ImportRequest(long projectId, String name, boolean baseline, boolean validateOnly,
        DurationAmount periodLength, Integer horizonPeriods, RoundingPolicy roundingPolicy,
        ImportMapping mapping, Long baseNetworkId, JsonNode leverChanges) {

    public ImportRequest {
        mapping = mapping == null ? ImportMapping.empty() : mapping;
    }

    /**
     * The form every caller used before FR-28: an import that records no variant edge.
     *
     * <p>Kept so that the two fields are additive rather than a signature change every existing
     * caller has to re-read. {@code com.snrm.archive} takes this form deliberately — an archive
     * restores its lineage in a second pass over {@code bundle.json}, where both ends of every edge
     * exist and the origin may be {@code SEARCH} rather than {@code MANUAL}.
     */
    public ImportRequest(long projectId, String name, boolean baseline, boolean validateOnly,
            DurationAmount periodLength, Integer horizonPeriods, RoundingPolicy roundingPolicy,
            ImportMapping mapping) {
        this(projectId, name, baseline, validateOnly, periodLength, horizonPeriods, roundingPolicy,
                mapping, null, null);
    }

    /**
     * The time base the wizard confirmed, or null to let the file decide.
     *
     * <p>Partial confirmations are filled from the defaults rather than refused: a client that sends a
     * period but no horizon has still expressed the thing that matters most, since the period is what
     * every unit-less value in the file is read against.
     */
    public TimeBaseSpec timeBaseOverride() {
        if (periodLength == null && horizonPeriods == null && roundingPolicy == null) {
            return null;
        }
        TimeBaseSpec defaults = TimeBaseSpec.defaults();
        return new TimeBaseSpec(
                periodLength == null ? defaults.periodLength() : periodLength,
                horizonPeriods == null ? defaults.horizonPeriods() : horizonPeriods,
                roundingPolicy == null ? defaults.roundingPolicy() : roundingPolicy,
                true);
    }

    /** Builds the period from the two form fields, or null when neither was sent. */
    public static DurationAmount periodOf(Double value, TimeUnit unit) {
        if (value == null && unit == null) {
            return null;
        }
        // A value without a unit is the one combination that cannot be honoured:
        // a number never means "of whatever the reader assumed". Both or neither.
        if (value == null || unit == null) {
            throw new IllegalArgumentException("periodLengthValue and periodLengthUnit go together: "
                    + "a period length without a unit has no meaning.");
        }
        if (value <= 0) {
            throw new IllegalArgumentException(
                    "periodLengthValue must be greater than zero, was " + value);
        }
        return DurationAmount.of(value, unit);
    }
}
