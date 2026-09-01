/**
 * Project aggregate: the top-level container for one modelling exercise, and its REST
 * resource (FR-01).
 *
 * <p>Networks, products, scenarios and configuration variants are keyed by {@code project_id} but
 * belong to their own modules — each is large enough that loading it with the project would be
 * wasteful, and each has its own lifecycle.
 *
 * <p>{@code owner_id} carries no foreign key because Phase 1 has one research user and no user
 * table. {@link com.snrm.project.ProjectService} nevertheless filters every query by it,
 * so multi-user hardening is a change to {@code auth/CurrentUser} rather than an audit of every
 * query.
 */
package com.snrm.project;
