import { cloneBody, forkRequestFrom } from './fork-request';

/**
 * The rule two dialogs share (FR-09, FR-26).
 *
 * The editor's fork prompt leaves the name field empty with the base name as a placeholder; the
 * dashboard's Duplicate dialog prefills it with the base name as a value. Untouched, both mean *the
 * same name, next version* - so the assertions below are as much about the two being equal as about
 * either one being right.
 */
describe('fork-request', () => {
  describe('forkRequestFrom', () => {
    it('omits a name the researcher did not type - the fork prompt’s empty field', () => {
      expect(forkRequestFrom('', 'Baseline', '')).toEqual({ name: null, leverChanges: null });
    });

    it('omits a name that is the base network’s - the duplicate dialog’s prefilled field', () => {
      expect(forkRequestFrom('Baseline', 'Baseline', '')).toEqual({
        name: null,
        leverChanges: null,
      });
    });

    it('resolves the two dialogs’ untouched defaults identically', () => {
      // The whole reason this function exists: a placeholder and a prefilled value are two ways of
      // presenting one default, and they must leave the browser as one request.
      expect(forkRequestFrom('', 'Baseline', '')).toEqual(
        forkRequestFrom('Baseline', 'Baseline', ''),
      );
    });

    it('keeps a name the researcher actually changed', () => {
      expect(forkRequestFrom('Baseline + backup supplier', 'Baseline', '').name).toBe(
        'Baseline + backup supplier',
      );
    });

    it('trims both sides before deciding, so trailing space is not a rename', () => {
      expect(forkRequestFrom('  Baseline  ', 'Baseline', '').name).toBeNull();
      expect(forkRequestFrom('  Buffered  ', 'Baseline', '').name).toBe('Buffered');
    });

    it('is case sensitive - a differently cased name is a different name', () => {
      // `uq_network (project, name, version)` resolves the stored string, and nothing else in the
      // tool folds case (`element-matching.ts` states the same rule for node names).
      expect(forkRequestFrom('baseline', 'Baseline', '').name).toBe('baseline');
    });

    it('records a note as the lever annotation the variant keeps', () => {
      expect(forkRequestFrom('', 'Baseline', ' +20% capacity at PLANT-1 ').leverChanges).toEqual({
        note: '+20% capacity at PLANT-1',
      });
    });

    it('records nothing rather than an empty annotation when the note is blank', () => {
      // "Not annotated" and "annotated with nothing" are two different columns in the comparison
      // view, so a whitespace-only note must not become `{ note: '' }`.
      expect(forkRequestFrom('', 'Baseline', '   ').leverChanges).toBeNull();
    });
  });

  describe('cloneBody', () => {
    it('sends an empty object when nothing was asked for, never nulls', () => {
      expect(cloneBody({ name: null, leverChanges: null })).toEqual({});
    });

    it('omits the absent field rather than sending it null', () => {
      const body = cloneBody({ name: 'Buffered', leverChanges: null });

      expect(body).toEqual({ name: 'Buffered' });
      expect('leverChanges' in body).toBeFalse();
    });

    it('carries both when both were given', () => {
      expect(cloneBody({ name: 'Buffered', leverChanges: { note: 'more stock' } })).toEqual({
        name: 'Buffered',
        leverChanges: { note: 'more stock' },
      });
    });
  });
});
