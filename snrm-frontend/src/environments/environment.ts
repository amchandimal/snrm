import { AppEnvironment } from './environment.model';

/**
 * Development configuration (`ng serve`).
 *
 * `/api/v1` is relative on purpose: proxy.conf.json forwards `/api` to the Spring Boot backend on
 * http://localhost:8080, so the browser sees a single origin and the backend needs no CORS
 * configuration (its SecurityConfig deliberately has none yet). Point the proxy elsewhere rather
 * than putting an absolute URL here.
 */
export const environment: AppEnvironment = {
  production: false,
  apiBaseUrl: '/api/v1',
  jobPollIntervalMs: 1500,
  jobPollTimeoutMs: 30 * 60 * 1000,
};
