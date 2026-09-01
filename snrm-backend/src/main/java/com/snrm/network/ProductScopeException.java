package com.snrm.network;

import com.snrm.common.DomainException;

/**
 * A {@code node_product} row that would join a node to a product from a different project.
 *
 * <p>Products are project-scoped precisely so every configuration variant of a project draws on one
 * catalogue. {@code node_product} has foreign keys to both sides but nothing that ties
 * them to the same project, so the check has to live here — and it matters: demand recorded against
 * a product the network's project does not own would be invisible to the comparison view,
 * which reads variants of one project side by side.
 */
public class ProductScopeException extends DomainException {

    /** Problem-detail {@code code}; part of the API contract. */
    public static final String CODE = "PRODUCT_OUT_OF_SCOPE";

    public ProductScopeException(long productId, long productProjectId, long nodeProjectId) {
        super(("Product %d belongs to project %d, but the node belongs to project %d. Products are "
                + "project-scoped so that every configuration variant shares one catalogue.")
                .formatted(productId, productProjectId, nodeProjectId));
    }

    @Override
    public String code() {
        return CODE;
    }
}
