package com.snrm.common;

/**
 * A domain rule violated by the <em>current state</em> of the data rather than by the request
 * itself: the same request would have succeeded a moment earlier, or will succeed once something
 * else changes.
 *
 * <p>Split out from plain {@link DomainException} only so that
 * {@link GlobalExceptionHandler} can answer 409 instead of 422. The distinction is the one RFC 9110
 * draws: 422 means the request is understood but semantically wrong however the server is arranged
 * (a link from a node to itself is never valid); 409 means it clashes with what is already there
 * (that link already exists, that name is taken) and the caller has a remedy.
 *
 * <p>{@link com.snrm.network.NetworkImmutableException} is a 409 too but keeps its own handler: it
 * carries an extra {@code networkId} member the editor branches on.
 */
public abstract class ConflictException extends DomainException {

    protected ConflictException(String message) {
        super(message);
    }
}
