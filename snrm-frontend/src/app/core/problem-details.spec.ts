import { HttpErrorResponse } from '@angular/common/http';

import { ProblemCode } from './models';
import { problemCode, problemMessage } from './problem-details';

/**
 * Error reporting.
 *
 * Runs without a backend, which is the point: these are the cases a researcher hits when something
 * is down or misconfigured, and they are hard to reproduce by hand at the moment you need them.
 */
describe('problemMessage', () => {
  it('shows the API\'s own sentence for a domain failure', () => {
    const conflict = new HttpErrorResponse({
      status: 409,
      statusText: 'Conflict',
      error: {
        title: 'Conflict',
        status: 409,
        detail: 'A project named "Automotive tier-1 case" already exists.',
        code: ProblemCode.DUPLICATE_NAME,
      },
    });

    expect(problemMessage(conflict)).toBe('A project named "Automotive tier-1 case" already exists.');
    expect(problemCode(conflict)).toBe(ProblemCode.DUPLICATE_NAME);
  });

  it('lists the offending fields on a validation failure', () => {
    const invalid = new HttpErrorResponse({
      status: 400,
      error: {
        detail: 'Request body failed validation.',
        code: ProblemCode.VALIDATION_FAILED,
        fieldErrors: { name: 'name is required' },
      },
    });

    expect(problemMessage(invalid)).toBe('Request body failed validation. (name: name is required)');
  });

  it('reports a stopped backend as unreachable, not as a server error', () => {
    // What `ng serve`'s proxy actually returns when the backend refuses the connection: a 500 with
    // a plain-text body. The browser never sees a network-level failure, so status is 500, not 0.
    const proxied = new HttpErrorResponse({
      status: 500,
      statusText: 'Internal Server Error',
      url: 'http://localhost:4200/api/v1/projects',
      error: 'Error occured while trying to proxy: localhost:4200/api/v1/projects',
    });

    expect(problemMessage(proxied)).toContain('Is the backend running?');
  });

  it('still shows a genuine backend 500, which always carries a problem document', () => {
    const serverError = new HttpErrorResponse({
      status: 500,
      error: { title: 'Internal Server Error', status: 500, detail: 'Unexpected error.' },
    });

    expect(problemMessage(serverError)).toBe('Unexpected error.');
  });

  it('reports a dead dev server distinctly from a dead backend', () => {
    const noResponse = new HttpErrorResponse({ status: 0, error: new ProgressEvent('error') });

    expect(problemMessage(noResponse)).toContain('ng serve');
  });
});
