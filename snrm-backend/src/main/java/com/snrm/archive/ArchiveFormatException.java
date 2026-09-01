package com.snrm.archive;

import com.snrm.common.DomainException;

/**
 * The upload is not a readable project archive.
 *
 * <p>Distinct from a bundle that parses and then fails validation, which is reported rather than
 * thrown — the same split {@code ImportService} makes for a network. This is the case where there is
 * no report to give: no {@code bundle.json}, a format version this build does not know, a missing
 * network document, or JSON that will not bind.
 */
public class ArchiveFormatException extends DomainException {

    /** Machine-readable member of the RFC 7807 problem document. */
    public static final String CODE = "ARCHIVE_UNREADABLE";

    public ArchiveFormatException(String message) {
        super(message);
    }

    public ArchiveFormatException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public String code() {
        return CODE;
    }

    /** An archive written by a later build of the tool than this one. */
    public static ArchiveFormatException unknownVersion(int found) {
        return new ArchiveFormatException(("This archive declares format version %d and this build "
                + "reads version %d. Refused rather than parsed optimistically: a reader that "
                + "cannot understand the layout cannot tell you what it got wrong either. Open it "
                + "with a build of the tool at or above the version that wrote it.")
                .formatted(found, ArchiveSchema.FORMAT_VERSION));
    }
}
