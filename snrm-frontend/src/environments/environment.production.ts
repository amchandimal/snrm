import { AppEnvironment } from './environment.model';

/**
 * Production configuration, swapped in by the `production` build configuration in angular.json.
 *
 * The compiled bundle is packaged inside the Spring Boot application, so the API is same-origin
 * and the relative base URL holds unchanged.
 */
export const environment: AppEnvironment = {
  production: true,
  apiBaseUrl: '/api/v1',
  jobPollIntervalMs: 2500,
  jobPollTimeoutMs: 30 * 60 * 1000,
};
