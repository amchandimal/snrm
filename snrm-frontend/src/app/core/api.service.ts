import {
  HttpClient,
  HttpContext,
  HttpHeaders,
  HttpParams,
  HttpResponse,
} from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../environments/environment';

/** Query parameters accepted by {@link ApiService}; `undefined` values are dropped. */
export type QueryParams = Record<
  string,
  string | number | boolean | readonly (string | number | boolean)[] | undefined | null
>;

/** Per-call options. Deliberately narrow - anything wider belongs in a feature service. */
export interface ApiOptions {
  readonly params?: QueryParams;
  readonly headers?: HttpHeaders | Record<string, string>;
  readonly context?: HttpContext;
}

/**
 * The single seam between the SPA and the SNRM REST API.
 *
 * Every request in the application goes through here: it resolves paths against the configured base
 * URL (`environment.apiBaseUrl`, `/api/v1`) and is the request the auth interceptor
 * attaches the bearer token to. Components never inject `HttpClient` directly - feature services
 * call this, so the base URL, the token and the error contract have exactly one implementation.
 *
 * Errors are left as `HttpErrorResponse` with the RFC 7807 body intact; read them with the helpers
 * in `problem-details.ts`.
 */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);

  /** Base URL of the API, without a trailing slash. */
  readonly baseUrl = environment.apiBaseUrl.replace(/\/$/, '');

  /**
   * Absolute URL for an API path.
   *
   * Exposed for the few callers that cannot go through the methods below - a multipart upload with
   * progress events, for instance.
   */
  url(path: string): string {
    return `${this.baseUrl}${path.startsWith('/') ? path : `/${path}`}`;
  }

  get<T>(path: string, options?: ApiOptions): Observable<T> {
    return this.http.get<T>(this.url(path), this.toHttpOptions(options));
  }

  /**
   * A binary GET - a file download rather than JSON.
   *
   * Goes through here rather than through `HttpClient` in a feature service so that the base URL and
   * the bearer token stay in one place: an export is an authenticated request, so it cannot be an
   * `<a download>` pointing at the API and has to come back as a blob the client saves.
   *
   * The whole response is returned, not just the body, because the filename the server chose lives in
   * `Content-Disposition` and is the one thing a download needs that the body does not carry.
   */
  download(path: string, options?: ApiOptions): Observable<HttpResponse<Blob>> {
    // The options are spelled out rather than spread from toHttpOptions: `observe` and `responseType`
    // have to reach the overload as literal types for it to resolve to HttpResponse<Blob>.
    return this.http.get(this.url(path), {
      params: options?.params ? this.toHttpParams(options.params) : undefined,
      headers: options?.headers,
      context: options?.context,
      observe: 'response',
      responseType: 'blob',
    });
  }

  post<T>(path: string, body?: unknown, options?: ApiOptions): Observable<T> {
    return this.http.post<T>(this.url(path), body ?? null, this.toHttpOptions(options));
  }

  put<T>(path: string, body?: unknown, options?: ApiOptions): Observable<T> {
    return this.http.put<T>(this.url(path), body ?? null, this.toHttpOptions(options));
  }

  patch<T>(path: string, body?: unknown, options?: ApiOptions): Observable<T> {
    return this.http.patch<T>(this.url(path), body ?? null, this.toHttpOptions(options));
  }

  delete<T>(path: string, options?: ApiOptions): Observable<T> {
    return this.http.delete<T>(this.url(path), this.toHttpOptions(options));
  }

  private toHttpOptions(options?: ApiOptions): {
    params?: HttpParams;
    headers?: HttpHeaders | Record<string, string>;
    context?: HttpContext;
  } {
    return {
      params: options?.params ? this.toHttpParams(options.params) : undefined,
      headers: options?.headers,
      context: options?.context,
    };
  }

  /** Drops null/undefined entries and expands arrays into repeated keys (`?networkIds=1&networkIds=2`). */
  private toHttpParams(params: QueryParams): HttpParams {
    let httpParams = new HttpParams();
    for (const [key, value] of Object.entries(params)) {
      if (value === undefined || value === null) {
        continue;
      }
      if (Array.isArray(value)) {
        for (const entry of value as readonly (string | number | boolean)[]) {
          httpParams = httpParams.append(key, String(entry));
        }
      } else {
        httpParams = httpParams.set(key, String(value));
      }
    }
    return httpParams;
  }
}
