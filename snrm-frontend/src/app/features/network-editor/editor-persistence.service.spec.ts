import { HttpErrorResponse } from '@angular/common/http';
import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { Observable, delay, of, throwError } from 'rxjs';

import { ApiService } from '../../core/api.service';
import { BulkNodePositionRequest, MAX_BULK_BATCH } from '../../core/models';
import { EditorPersistenceService } from './editor-persistence.service';

/** One recorded PATCH, so a spec can assert what actually went over the wire. */
interface RecordedPatch {
  readonly path: string;
  readonly body: unknown;
}

describe('EditorPersistenceService', () => {
  let service: EditorPersistenceService;
  let patches: RecordedPatch[];
  let respond: (path: string) => Observable<unknown>;

  beforeEach(() => {
    patches = [];
    respond = () => of({ updated: 0, nodes: [], links: [] });

    const api = {
      patch: (path: string, body: unknown) => {
        patches.push({ path, body });
        return respond(path);
      },
    };

    TestBed.configureTestingModule({
      providers: [EditorPersistenceService, { provide: ApiService, useValue: api }],
    });
    service = TestBed.inject(EditorPersistenceService);
    service.configure(1);
  });

  afterEach(() => service.destroy());

  it('starts clean', () => {
    expect(service.status()).toBe('saved');
    expect(service.dirty()).toBeFalse();
  });

  it('coalesces repeated moves of the same node into one entry', fakeAsync(() => {
    service.queuePosition(5, 10, 10);
    service.queuePosition(5, 20, 20);
    service.queuePosition(5, 30, 30);
    expect(service.pending()).toBe(1);

    tick(EditorPersistenceService.FLUSH_INTERVAL_MS);

    expect(patches.length).toBe(1);
    const body = patches[0].body as BulkNodePositionRequest;
    expect(body.positions).toEqual([{ nodeId: 5, posX: 30, posY: 30 }]);
  }));

  it('flushes on a ceiling measured from the first edit, not a trailing debounce', fakeAsync(() => {
    service.queuePosition(1, 0, 0);
    tick(1500);
    // A trailing debounce would restart here and never fire while the drag continues.
    service.queuePosition(2, 0, 0);
    tick(499);
    expect(patches.length).toBe(0);

    tick(1);
    expect(patches.length).toBe(1);
    expect((patches[0].body as BulkNodePositionRequest).positions.length).toBe(2);
  }));

  it('merges successive attribute patches for the same node', fakeAsync(() => {
    service.queueNodePatch(3, { fixedCost: 10 });
    service.queueNodePatch(3, { varCost: 2 });
    expect(service.pending()).toBe(1);

    tick(EditorPersistenceService.FLUSH_INTERVAL_MS);

    expect(patches.length).toBe(1);
    expect(patches[0].body).toEqual({ nodes: [{ nodeId: 3, fixedCost: 10, varCost: 2 }] });
  }));

  it('sends positions, node attributes and link attributes to their own endpoints', fakeAsync(() => {
    service.queuePosition(1, 5, 5);
    service.queueNodePatch(2, { name: 'DC 9' });
    service.queueLinkPatch(3, { leadTime: { value: 4, unit: 'HOUR' } });

    tick(EditorPersistenceService.FLUSH_INTERVAL_MS);

    expect(patches.map((patch) => patch.path)).toEqual([
      '/networks/1/nodes/positions',
      '/networks/1/nodes',
      '/networks/1/links',
    ]);
  }));

  it('flush() sends immediately, which is what blur and the route guard use', fakeAsync(() => {
    service.queuePosition(1, 5, 5);
    void service.flush();
    tick();

    expect(patches.length).toBe(1);
    expect(service.pending()).toBe(0);
    expect(service.status()).toBe('saved');
  }));

  it('is a no-op when nothing is pending', fakeAsync(() => {
    void service.flush();
    tick();
    expect(patches.length).toBe(0);
  }));

  it('reports the four dirty states', fakeAsync(() => {
    expect(service.status()).toBe('saved');
    service.queuePosition(1, 1, 1);
    expect(service.status()).toBe('unsaved');

    tick(EditorPersistenceService.FLUSH_INTERVAL_MS);
    expect(service.status()).toBe('saved');

    respond = () => throwError(() => new HttpErrorResponse({ status: 500 }));
    service.queuePosition(1, 2, 2);
    tick(EditorPersistenceService.FLUSH_INTERVAL_MS);
    expect(service.status()).toBe('error');
    expect(service.saveError()).toBeTruthy();
  }));

  it('keeps failed edits pending so nothing is lost', fakeAsync(() => {
    respond = () => throwError(() => new HttpErrorResponse({ status: 500 }));
    service.queuePosition(7, 1, 1);
    tick(EditorPersistenceService.FLUSH_INTERVAL_MS);

    expect(service.pending()).toBe(1);

    // A retry after the cause is fixed sends the same edit.
    respond = () => of({ updated: 1, nodes: [], links: [] });
    void service.flush();
    tick();
    expect(service.pending()).toBe(0);
    expect(patches.length).toBe(2);
  }));

  it('does not let a restored failure overwrite a newer edit to the same node', fakeAsync(() => {
    // Delayed so the specs can act *between* the snapshot and the failure - which is exactly the
    // window in which the user carries on dragging.
    respond = () => throwError(() => new HttpErrorResponse({ status: 500 })).pipe(delay(10));

    service.queuePosition(7, 1, 1);
    void service.flush();
    tick(1);

    // The user drags the same node again while the failing request is still in flight.
    service.queuePosition(7, 99, 99);
    tick(20);

    expect(service.status()).toBe('error');
    expect(service.pending()).toBe(1);

    respond = () => of({ updated: 1, nodes: [], links: [] });
    void service.flush();
    tick();

    const last = patches.at(-1)?.body as BulkNodePositionRequest;
    expect(last.positions).toEqual([{ nodeId: 7, posX: 99, posY: 99 }]);
  }));

  it('splits a batch larger than MAX_BULK_BATCH', fakeAsync(() => {
    for (let id = 1; id <= MAX_BULK_BATCH + 1; id++) {
      service.queuePosition(id, id, id);
    }
    tick(EditorPersistenceService.FLUSH_INTERVAL_MS);

    expect(patches.length).toBe(2);
    expect((patches[0].body as BulkNodePositionRequest).positions.length).toBe(MAX_BULK_BATCH);
    expect((patches[1].body as BulkNodePositionRequest).positions.length).toBe(1);
  }));

  it('discardPending throws the queue away without sending it', fakeAsync(() => {
    service.queuePosition(1, 1, 1);
    service.discardPending();
    tick(EditorPersistenceService.FLUSH_INTERVAL_MS);

    expect(patches.length).toBe(0);
    expect(service.pending()).toBe(0);
  }));

  it('configure() drops anything left over from the previous network', fakeAsync(() => {
    service.queuePosition(1, 1, 1);
    service.configure(2);
    tick(EditorPersistenceService.FLUSH_INTERVAL_MS);

    expect(patches.length).toBe(0);
  }));
});
