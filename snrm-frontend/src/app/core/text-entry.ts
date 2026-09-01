/**
 * Whether a keystroke belongs to a form control rather than to the surface behind it.
 *
 * Free of Angular and of the DOM beyond the one `instanceof`, like `core/run-discard.ts` and
 * `core/lever-changes.ts` beside it, and here for the reason those two are: **two features now guard
 * a transport with it**, and one rule must not be spelled two ways.
 *
 * The network editor has always had this test (FR-18): Space plays, the arrow keys step,
 * and none of the three may fire while the researcher is typing a node name or dragging the
 * transport's own scrub slider. The results dashboard's period cursor takes the arrow keys on the
 * same terms (FR-22), so the function moved here rather than being copied - a second copy that
 * gained `SELECT` and lost `isContentEditable` would make one keystroke behave differently on two
 * screens, and nothing on either would say why.
 *
 * ## The scrub slider is the case worth naming
 *
 * An `<input type="range">` is an `INPUT`, so it is excluded here - deliberately, not incidentally.
 * A focused range input already moves by exactly one step per arrow key, which is one period, so
 * stepping it a second time from a document listener would move the cursor by two and make the
 * slider feel broken in precisely the place a reader is most likely to use the keyboard.
 */
export function isTextEntry(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) {
    return false;
  }
  const tag = target.tagName;
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || target.isContentEditable;
}
