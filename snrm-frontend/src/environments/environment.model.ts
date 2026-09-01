/**
 * Shape of the build-time environment configuration.
 *
 * Declared once so `environment.ts` and `environment.production.ts` cannot drift apart -
 * angular.json swaps one file for the other in the production configuration.
 */
export interface AppEnvironment {
  /** True in the production build; drives log verbosity and dev-only affordances. */
  readonly production: boolean;

  /**
   * Base URL every {@link ApiService} call is resolved against.
   *
   * The whole API lives under `/api/v1`. A relative value keeps the browser on one origin:
   * in development `ng serve` proxies `/api` to the backend (proxy.conf.json), and in production
   * Spring Boot serves the compiled bundle itself - neither case needs CORS.
   */
  readonly apiBaseUrl: string;

  /** How often {@link JobPollingService} asks `GET /jobs/{id}` for progress. */
  readonly jobPollIntervalMs: number;

  /** Upper bound on a single poll-until-done cycle before it gives up (5 min target run). */
  readonly jobPollTimeoutMs: number;
}
