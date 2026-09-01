/**
 * Typed models for the REST contract - one import site for every stage that follows.
 *
 * These mirror the backend **DTOs**, never its JPA entities or database shapes: entities never
 * cross the API. Where a controller does not exist yet, the file says so at the top and
 * the shape is the one the endpoint is expected to serve; those are the ones to re-check against
 * `/v3/api-docs` when the endpoint lands.
 *
 * Every model here now mirrors a shipped controller: auth, project, network, node, link, product,
 * scenario, metric, job, simulation, import and comparison. Nothing in this folder is provisional.
 *
 * Usage: `import { Project, NetworkNode } from '../core/models';`
 */
export * from './common.model';
export * from './time.model';
export * from './problem.model';
export * from './auth.model';
export * from './project.model';
export * from './network.model';
export * from './node.model';
export * from './link.model';
export * from './product.model';
export * from './scenario.model';
export * from './metric.model';
export * from './job.model';
export * from './simulation.model';
export * from './import.model';
export * from './comparison.model';
export * from './archive.model';
