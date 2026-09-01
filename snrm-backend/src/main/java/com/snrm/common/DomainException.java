package com.snrm.common;

/**
 * Base type for violations of a domain rule — as opposed to a bad request,
 * a missing row, or an infrastructure failure.
 *
 * <p>Rules of this kind are enforced in services, never in database triggers: the guard has to be
 * able to explain the remedy to the caller (network immutability is the first example),
 * and a trigger can only reject. {@link GlobalExceptionHandler} maps these to RFC 7807
 * {@code problem+json} and copies {@link #code()} into the response so the SPA can branch on the
 * rule rather than on a message string.
 */
public abstract class DomainException extends RuntimeException {

    protected DomainException(String message) {
        super(message);
    }

    /**
     * For a rule violation detected while reading something — a malformed archive, a document that
     * will not parse — where the underlying failure is worth keeping in the log even though the
     * caller only ever sees {@link #getMessage()} and {@link #code()}.
     */
    protected DomainException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Stable machine-readable identifier for the violated rule, e.g. {@code NETWORK_IMMUTABLE}.
     * Surfaced as the {@code code} member of the problem detail; treat it as part of the API
     * contract once a client branches on it.
     */
    public abstract String code();
}
