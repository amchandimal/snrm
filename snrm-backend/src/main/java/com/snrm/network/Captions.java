package com.snrm.network;

/**
 * What a caption means on each write path — the whole of FR-30's null handling, in one place.
 *
 * <p>Written down here rather than spread across two mappers and four DTOs because the feature has
 * one requirement the ordinary PUT/PATCH contract of this API cannot meet on its own: <strong>the
 * editor has to be able to clear a caption</strong>, and it writes every attribute through the
 * debounced bulk PATCH, where a null already means "leave alone". The problem is
 * plain enough — a PATCH cannot express "set to nothing" — and this is the answer.
 *
 * <h2>The three rules</h2>
 *
 * <ol>
 *   <li><strong>Null is the only form of "no caption".</strong> A caption is trimmed on every path,
 *       and one that is blank after trimming is stored as {@code null}. There is no second empty
 *       state for a reader to interpret: the canvas asks "is there a caption", not "is the caption
 *       the empty string", and {@code ""} in the column would eventually be drawn as a blank second
 *       line by something.</li>
 *   <li><strong>On a PUT, an omitted or blank caption clears it</strong> ({@link #replace}) — the
 *       full-replacement contract every other nullable field on {@code NodeRequest} already
 *       follows.</li>
 *   <li><strong>On a PATCH, {@code null} leaves the caption alone and a present-but-blank string
 *       clears it</strong> ({@link #patch}). This is the one field on the patch DTOs where an empty
 *       value is a write rather than a refusal, and it is deliberate: {@code NodePatch.name} rejects
 *       a blank with {@code @Pattern} precisely because a nameless node is not a thing the API
 *       should be able to produce, whereas a captionless one is the normal case. The alternative —
 *       route a clear through {@code PUT /nodes/{id}} — would make the one caption edit that removes
 *       something take a different endpoint from every caption edit that adds something, and that
 *       PUT would replace the node's whole attribute set with whatever the property panel happened
 *       to be holding.</li>
 * </ol>
 *
 * <p><strong>The flag has no such problem and gets no such rule.</strong> {@code captionVisible} is
 * a {@code Boolean} on the wire: absent means "unchanged" on a PATCH and "visible" on a PUT
 * ({@link #replaceVisible}), matching the {@code NOT NULL DEFAULT TRUE} column of V10 and the
 * omission rules — a caption arriving with no flag beside it is shown. There is
 * no third state to express, so nothing needs a convention.
 *
 * @see Node#getCaption()
 */
final class Captions {

    /** {@code node.caption} / {@code link.caption} — {@code VARCHAR(200)} in V10. */
    static final int MAX_LENGTH = 200;

    private Captions() {
    }

    /** A caption as a PUT means it: omitted or blank clears, anything else is trimmed and kept. */
    static String replace(String submitted) {
        return normalise(submitted);
    }

    /**
     * A caption as a PATCH means it: {@code null} keeps what is there, blank clears it.
     *
     * @param current   the caption on the entity now
     * @param submitted what the patch carried, or null if it carried nothing for this field
     */
    static String patch(String current, String submitted) {
        return submitted == null ? current : normalise(submitted);
    }

    /** The visibility flag as a PUT means it: omitted is <em>visible</em>, never hidden. */
    static boolean replaceVisible(Boolean submitted) {
        return submitted == null || submitted;
    }

    /** The visibility flag as a PATCH means it: omitted is unchanged. */
    static boolean patchVisible(boolean current, Boolean submitted) {
        return submitted == null ? current : submitted;
    }

    private static String normalise(String submitted) {
        if (submitted == null) {
            return null;
        }
        String trimmed = submitted.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
