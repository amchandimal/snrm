package com.snrm.common;

/**
 * A name already taken within the scope that has to keep it unique — the {@code uq_project},
 * {@code uq_network}, {@code uq_node} and {@code uq_product} keys of {@code V2__domain.sql}
 *
 * <p>Raised by the services before the insert is attempted, so the caller gets the name back in a
 * message it can put next to the field rather than a driver-level constraint error. The unique keys
 * remain the backstop for anything that slips past, and land on
 * {@link GlobalExceptionHandler#handleDataIntegrityViolation} instead.
 *
 * <p>Node names in particular are not cosmetic: the CSV/XLSX import resolves
 * {@code links.source} and {@code links.target} by name, so a duplicate would make a network
 * ambiguous to import and to export.
 */
public class DuplicateNameException extends ConflictException {

    /** Problem-detail {@code code}; part of the API contract. */
    public static final String CODE = "DUPLICATE_NAME";

    private final String resource;
    private final String name;

    /**
     * @param resource what carries the name, in lower case — {@code "node"}, {@code "product"}
     * @param scope    where it has to be unique, phrased for the start of a sentence —
     *                 {@code "Network 7"}
     * @param name     the offending name
     */
    public DuplicateNameException(String resource, String scope, String name) {
        super("%s already has a %s named '%s'; %s names must be unique within it."
                .formatted(scope, resource, name, resource));
        this.resource = resource;
        this.name = name;
    }

    @Override
    public String code() {
        return CODE;
    }

    /** The kind of thing that was being named. */
    public String getResource() {
        return resource;
    }

    /** The name that was already taken. */
    public String getName() {
        return name;
    }
}
