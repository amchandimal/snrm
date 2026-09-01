package com.snrm.dataimport;

import com.snrm.common.DomainException;

/**
 * An import whose {@code baseNetworkId} names a network in a different project from the one the
 * network is being imported into (FR-28: the base must be in the same project).
 *
 * <p><strong>Refused, never silently unlinked.</strong> The alternative — create the network and
 * drop the edge — is the worst of the three outcomes available. A batch import is a folder of files
 * turned into a fork forest in one pass, and the researcher reads that forest in the
 * provenance tree afterwards; a variant that quietly arrived as a root looks exactly like a file the
 * wizard was told was independent. The mistake is also the kind that repeats: a base id is chosen
 * once and sent with every file of the batch, so a wrong one produces a whole batch of orphans, each
 * indistinguishable from a deliberate choice.
 *
 * <p><strong>Why a 4xx and not a row in the validation report.</strong> {@link ImportReport} answers
 * for the user's <em>data</em> — the cells of their file, which the wizard renders as a per-row
 * table and which a 200 carries because RFC 7807 cannot hold a hundred rows. {@code baseNetworkId}
 * is not data; it is a request field the wizard composed, in the same class as {@code projectId},
 * which has always been a 404 when it names nothing. A base in another project is a client that
 * paired two ids that do not go together, so it is answered the way every other id mismatch in this
 * codebase is: {@code CrossNetworkReferenceException} and {@code ProductScopeException} are the
 * precedents, and both are 422 — the ids are real, but their combination is not something this
 * project can express.
 *
 * <p>422 through {@code GlobalExceptionHandler}'s {@link DomainException} handler, which is also
 * what makes it visible on a dry run: the check runs before anything is staged, so
 * {@code validateOnly=true} refuses a bad base rather than reporting a clean file and failing at
 * commit (the wizard's review step is a decision, not a guess).
 *
 * <p>A base that does not exist at all, or belongs to another user, is a 404 from
 * {@code NetworkLookup} instead — missing and not-yours are the same answer everywhere in this
 * codebase.
 */
public class BaseNetworkScopeException extends DomainException {

    /** Problem-detail {@code code}; part of the API contract. */
    public static final String CODE = "BASE_NETWORK_OUT_OF_SCOPE";

    private final long baseNetworkId;

    /**
     * @param baseNetworkId  the network named as the base
     * @param baseProjectId  the project it actually belongs to
     * @param importProjectId the project the import is creating a network in
     */
    public BaseNetworkScopeException(long baseNetworkId, long baseProjectId, long importProjectId) {
        super(("Network %d belongs to project %d, but this import creates a network in project %d. "
                + "A configuration variant and its base are two configurations of one experiment, "
                + "so the edge only means anything within a project (FR-28). Import into "
                + "project %d, or name a base from project %d.")
                .formatted(baseNetworkId, baseProjectId, importProjectId, baseProjectId,
                        importProjectId));
        this.baseNetworkId = baseNetworkId;
    }

    @Override
    public String code() {
        return CODE;
    }

    /** The base network that belongs to another project. */
    public long getBaseNetworkId() {
        return baseNetworkId;
    }
}
