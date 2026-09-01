import { HttpErrorResponse } from '@angular/common/http';

import { ProblemDetail } from './models';

/**
 * Reading RFC 7807 `problem+json` error bodies.
 *
 * Every failure from the API carries a machine-readable `code` and a human `detail`. Branch on the
 * code; show the detail. Never parse the sentence.
 */

/** True when the response body is an RFC 7807 problem document. */
export function isProblemDetail(body: unknown): body is ProblemDetail {
  return typeof body === 'object' && body !== null && ('detail' in body || 'title' in body || 'code' in body);
}

/** The problem document inside an `HttpErrorResponse`, or null if the failure was not one. */
export function problemOf(error: unknown): ProblemDetail | null {
  if (error instanceof HttpErrorResponse && isProblemDetail(error.error)) {
    return error.error;
  }
  return null;
}

/** The backend's `code` extension, or null. Compare against {@link ProblemCode}. */
export function problemCode(error: unknown): string | null {
  return problemOf(error)?.code ?? null;
}

/**
 * A sentence to put in front of the user.
 *
 * Prefers the problem's `detail`, then its `title`, then the transport-level message. A network
 * failure (status 0) is reported as such rather than as an empty error, because "is the backend
 * running?" is the actual question in that case.
 */
export function problemMessage(error: unknown, fallback = 'Something went wrong.'): string {
  const problem = problemOf(error);
  if (problem) {
    const fieldErrors = problem.fieldErrors;
    if (fieldErrors) {
      const listed = Object.entries(fieldErrors)
        .map(([field, message]) => `${field}: ${message}`)
        .join('; ');
      if (listed) {
        return problem.detail ? `${problem.detail} (${listed})` : listed;
      }
    }
    if (problem.violations?.length) {
      return problem.violations.join('; ');
    }
    return problem.detail ?? problem.title ?? fallback;
  }

  if (error instanceof HttpErrorResponse) {
    if (error.status === 0) {
      return 'No response at all. Is the dev server (ng serve) still running?';
    }
    if (isUnansweredByApi(error)) {
      return (
        `The SNRM API did not answer (HTTP ${error.status}). Is the backend running? ` +
        'In development `ng serve` proxies /api to it - see proxy.conf.json.'
      );
    }
    return error.message || fallback;
  }

  return error instanceof Error ? error.message : fallback;
}

/**
 * True when a failure did not come from the API at all.
 *
 * The backend answers *every* failure with `problem+json`, 500 included - its
 * `GlobalExceptionHandler` maps even an unexpected exception to a problem document. A 5xx carrying
 * no structured body therefore came from whatever sits in front of the API rather than from the API
 * itself. In development that is the `ng serve` proxy, which answers **500 with a plain-text note**
 * when the backend refuses the connection: a stopped backend never reaches the browser as a
 * network-level failure, so status 0 is not the signal to look for.
 */
function isUnansweredByApi(error: HttpErrorResponse): boolean {
  return error.status >= 500 && !hasStructuredBody(error);
}

/** A parsed JSON body came back. Angular leaves an unparseable error body as the raw string. */
function hasStructuredBody(error: HttpErrorResponse): boolean {
  return typeof error.error === 'object' && error.error !== null;
}
