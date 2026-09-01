import { isTextEntry } from './text-entry';

/**
 * The guard both transports share (FR-18, FR-22).
 *
 * Four tags and a flag is not much arithmetic, but it is the difference between an arrow key
 * stepping a period and an arrow key moving a caret - and it is now read by the editor's playback
 * transport and by the results dashboard's period cursor, so a change to it changes two screens.
 */
describe('isTextEntry', () => {
  function element(tag: string, contentEditable = false): HTMLElement {
    const node = document.createElement(tag);
    if (contentEditable) {
      node.contentEditable = 'true';
    }
    return node;
  }

  it('claims every form control a keystroke can be typed into', () => {
    expect(isTextEntry(element('input'))).toBe(true);
    expect(isTextEntry(element('textarea'))).toBe(true);
    // A `<select>` answers the arrow keys itself, which is why it is here beside the two text ones.
    expect(isTextEntry(element('select'))).toBe(true);
  });

  it('claims a contenteditable element, whatever its tag', () => {
    expect(isTextEntry(element('div', true))).toBe(true);
  });

  it('leaves ordinary elements to the surface behind them', () => {
    expect(isTextEntry(element('div'))).toBe(false);
    expect(isTextEntry(element('button'))).toBe(false);
    expect(isTextEntry(element('svg'))).toBe(false);
  });

  it('treats a null or non-element target as not typing', () => {
    // `document` and `window` are legitimate event targets and neither takes text.
    expect(isTextEntry(null)).toBe(false);
    expect(isTextEntry(document)).toBe(false);
  });
});
