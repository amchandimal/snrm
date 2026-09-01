import { Injectable, signal } from '@angular/core';

/**
 * A menu the registry can talk to while it is the open one.
 *
 * Deliberately three methods and no fields: the registry never reads a menu's state, only tells it
 * that something happened outside itself.
 */
export interface RegisteredMenu {
  /** True when the node is inside this menu's own host element - a click on itself, not away. */
  contains(node: Node): boolean;
  /** Close without touching focus: whatever closed it has already put focus where it belongs. */
  closeQuietly(): void;
  /** Close and hand focus back to the toggle - Escape, so a keyboard user is never left nowhere. */
  closeAndRestoreFocus(): void;
}

/**
 * Which action menu is open - **one value for the whole page** (FR-23, FR-26).
 *
 * ## Why this exists at all
 *
 * FR-23 put one menu above the table. FR-26 puts one on **every row**, and the two things a
 * per-instance `open` signal was fine for stop being fine at that scale:
 *
 * - **"Which menu is open" is one question and would otherwise have N answers.** Two menus open at
 *   once is representable with a signal per row; here it is not, because each menu's `open` is a
 *   computed over this single value rather than a flag it sets and clears. Opening one *is* closing
 *   the others, and nothing has to remember to do it.
 * - **The document listeners belong to the open menu, not to every menu.** A dropdown needs a
 *   document-level click (to close on an outside click) and keydown (Escape). Bound per component,
 *   a fifty-network project would carry a hundred listeners, ninety-eight of which exist to return
 *   immediately. Here they are attached when a menu opens and removed when it closes, so a page
 *   with nothing open has none at all.
 *
 * `scroll` and `resize` join them for a reason particular to the row menus: those are positioned
 * against the viewport (see the component - a `.table-responsive` ancestor computes
 * `overflow-y: auto` and would clip an absolutely positioned menu on the lower rows), so a page
 * that moves under an open menu would leave it pointing at the wrong row. Closing is the honest
 * answer; re-anchoring on every scroll frame is not, for a control the reader can simply open
 * again. It closes **quietly**: a scroll is a mouse gesture, and pulling focus back to the toggle
 * would scroll the page back to where the reader just left.
 *
 * ## Not in `core/`
 *
 * It has exactly one component, and `core/` in this repository is where a fact lands when *two
 * features* need it (`lever-changes.ts`, `run-discard.ts`, `text-entry.ts`). This is the private
 * machinery of one menu that happens to have many instances, so it sits beside it -
 * `providedIn: 'root'` because the instances are what must share it, not the injector they were
 * created in.
 */
@Injectable({ providedIn: 'root' })
export class ActionMenuRegistry {
  private readonly _open = signal<RegisteredMenu | null>(null);

  /** The open menu, or null. Read by each menu's `open` computed - the single value. */
  readonly openMenu = this._open.asReadonly();

  /**
   * Opens a menu, closing whichever was open by the act of replacing it.
   *
   * There is no call into the previous menu: its `open` is a computed over {@link openMenu}, so it
   * closes on the same signal write. That is the whole reason this is one value.
   */
  open(menu: RegisteredMenu): void {
    this._open.set(menu);
    this.listen();
  }

  /** Closes a menu, if it is still the open one. A stale caller is a no-op rather than a bug. */
  close(menu: RegisteredMenu): void {
    if (this._open() !== menu) {
      return;
    }
    this._open.set(null);
    this.unlisten();
  }

  private listening = false;

  /** A click landed somewhere; only one outside the open menu closes it. */
  private readonly onDocumentClick = (event: MouseEvent): void => {
    const menu = this._open();
    const target = event.target;
    if (!menu || (target instanceof Node && menu.contains(target))) {
      return;
    }
    menu.closeQuietly();
  };

  private readonly onDocumentKeydown = (event: KeyboardEvent): void => {
    const menu = this._open();
    if (!menu || event.key !== 'Escape') {
      return;
    }
    event.preventDefault();
    menu.closeAndRestoreFocus();
  };

  /**
   * The page moved under the menu.
   *
   * Capturing, because the scroll that matters is usually an ancestor's - the table's own
   * `.table-responsive` box - and scroll events do not bubble.
   */
  private readonly onPageMoved = (): void => {
    this._open()?.closeQuietly();
  };

  private listen(): void {
    if (this.listening) {
      return;
    }
    this.listening = true;
    document.addEventListener('click', this.onDocumentClick);
    document.addEventListener('keydown', this.onDocumentKeydown);
    document.addEventListener('scroll', this.onPageMoved, true);
    window.addEventListener('resize', this.onPageMoved);
  }

  private unlisten(): void {
    if (!this.listening) {
      return;
    }
    this.listening = false;
    document.removeEventListener('click', this.onDocumentClick);
    document.removeEventListener('keydown', this.onDocumentKeydown);
    document.removeEventListener('scroll', this.onPageMoved, true);
    window.removeEventListener('resize', this.onPageMoved);
  }
}
