import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';

import { ExportFormat, Network } from '../../../core/models';
import { PANE_LIMIT_SENTENCE, compareBlocker } from '../../comparison/pane-grid';
import { ActionMenuRegistry, RegisteredMenu } from './action-menu-registry';

/**
 * Which question this menu is asking. The default is the one it was born asking.
 *
 * `selection` is FR-23's menu above the table - *act on these five*. `row` is FR-26's menu on each
 * row - *act on this one*. They share every mechanic and no entry.
 */
export type ActionMenuScope = 'selection' | 'row';

/** Unique per instance, because there is now one of these per row and ids must not collide. */
let instances = 0;

/**
 * How much room below the toggle a row menu wants before it gives up and opens upward, in pixels.
 *
 * A guess rather than a measurement, and deliberately: measuring means rendering the menu, reading
 * its height and moving it, which is a frame of the menu in the wrong place on every open. The
 * number is the tallest the row menu gets - seven entries and a sentence, since FR-29 added
 * Rename… - rounded up.
 */
const ROW_MENU_ROOM = 300;

/**
 * The project dashboard's actions menu - above the table for a selection (FR-23), and on every row
 * for one network (FR-26). The table addresses a set as well as a row, and a row is one action and
 * a menu.
 *
 * ## One menu, two scopes, and why it was generalised in place
 *
 * FR-26 reorganises the row: **Open in editor stays a button**, and the three exports,
 * *Duplicate network* and Delete move behind one per-row menu. That menu is the same control the
 * set menu above the table already is, and the same implementation of it rather than a second
 * dropdown with its own keyboard handling.
 *
 * So this component grew a {@link scope} rather than being copied, under this repository's rule
 * for shared components: **a shared component grows by optional addition or not at all.** `scope`
 * defaults to `'selection'` and {@link network} to null, so FR-23's call site passes exactly what it
 * passed before and the menu it draws is the menu it drew - same entries, same classes, same
 * wording, same keyboard. The only thing that changed under it is that the element ids are now per
 * instance, because there is one of these per row and a repeated id breaks every `aria-*` that
 * points at it. What the row scope reuses is the part that is easy to
 * get subtly wrong twice and impossible to notice: the open state, the roving Down/Up/Home/End,
 * Escape restoring focus, the outside click, and closing on `focusout` rather than on a `Tab` case
 * in the keydown handler. What it does not reuse is a single entry - the two scopes answer
 * different questions, which is why an `items` input was rejected: the FR-25 cap sentence, the FR-15
 * absence-with-a-reason and the per-entry tooltips are prose about *these* actions, and flattening
 * them into a data structure would have moved the wording out of the place that explains it.
 *
 * ## Bootstrap's markup, Angular's behaviour
 *
 * `angular.json` loads Bootstrap's **CSS only** (`scripts: []`), which is the same decision
 * `shared/confirm-dialog` states for the modal: the classes give the look the rest of the page has,
 * and the open state is a signal rather than an imperative `bootstrap.Dropdown` instance to keep in
 * sync with one. Nothing here reads `window.bootstrap`, so the dropdown works in a bundle that has
 * never loaded it.
 *
 * That leaves the keyboard to us, and it is the WAI-ARIA menu-button pattern the page can already
 * be driven with: the toggle is a real `<button>`, so Tab reaches it and Enter/Space opens it; the
 * items are real `<button>`s, so Tab walks them; Down/Up/Home/End rove between the enabled ones;
 * Escape closes and puts focus back on the toggle, so a keyboard user is never left with focus
 * inside something that has gone. A click outside closes it, and so does the selection emptying
 * underneath it.
 *
 * ## "Which menu is open" is one value, not one per row
 *
 * See {@link ActionMenuRegistry}. {@link open} is a computed over that single value, so opening a
 * row's menu closes whichever was open without anything having to be told, and the document-level
 * click and keydown listeners belong to the open menu rather than to all fifty of them.
 *
 * ## Why the row menu is positioned against the viewport
 *
 * The table sits in Bootstrap's `.table-responsive`, which sets `overflow-x: auto` - and CSS
 * resolves `overflow-y: visible` beside it to `auto`, so an absolutely positioned menu is clipped
 * by the scroll box on the lower rows. The row scope therefore anchors itself to the toggle's
 * viewport rectangle with `position: fixed`, and the registry closes it if the page moves.
 */
@Component({
  selector: 'app-network-actions-menu',
  standalone: true,
  templateUrl: './network-actions-menu.component.html',
  styleUrl: './network-actions-menu.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NetworkActionsMenuComponent implements RegisteredMenu {
  /** Which menu this is. Omitted, it is the selection menu FR-23 introduced. */
  readonly scope = input<ActionMenuScope>('selection');

  /** How many networks are selected. Zero disables the menu - FR-23's rule, stated once. */
  readonly count = input(0);
  /** An action is already running, so the menu is closed for its duration. */
  readonly busy = input(false);
  /**
   * False while the owning project's name is unknown.
   *
   * The typed phrase of FR-15 *is* that name, so a Delete offered before it has loaded would open a
   * dialog asking the user to type a string it cannot show them. True of both scopes: the row
   * delete and the set delete ask for the same string: destructive actions are typed.
   */
  readonly canDelete = input(true);

  /**
   * The row's network (FR-26). Null in the selection scope, where the menu has no single subject.
   *
   * Read for three things and nothing else: the toggle's accessible name, whether Delete appears at
   * all (`editable`), and the wording of the sentence that replaces it when it does not.
   */
  readonly network = input<Network | null>(null);

  // ------------------------------------------------------------------ the selection scope (FR-23)

  readonly deleteSelected = output<void>();
  /**
   * FR-24: write the selection out as a standalone project archive.
   *
   * No `canExport` beside {@link canDelete}, because the two entries are gated on different things
   * and only one of them has a precondition: the delete needs the project's *name* for the typed
   * phrase, where the export needs only its id, which the route already carries. An input that was
   * always true would be a control the parent could get wrong.
   */
  readonly exportSelected = output<void>();
  /**
   * FR-25: open the selection side by side in a new window.
   *
   * Gated on the count and on nothing else, and the gate is `comparison/pane-grid.compareBlocker` -
   * a **cross-feature import of a pure module**, under the precedent already set for
   * `disruption-overlay.ts → timeline.ts`, and for its reason. FR-25 asks that the cap be stated
   * "where the menu offers the action, not only after the window opens", which makes the limit a
   * fact two surfaces have to agree about: this entry and the route that enforces it. One number in
   * one module, or the menu eventually offers what the window refuses.
   */
  readonly compareSelected = output<void>();

  // ------------------------------------------------------------------------ the row scope (FR-26)

  /**
   * FR-29: rename this network in place.
   *
   * **Gated on nothing, frozen rows included.** The freeze covers what a result
   * was computed *from*, and a name is not among them - which makes this the second entry here
   * whose absence of a gate is the feature, after `duplicateRequested`. First in the menu because
   * it is the entry a researcher reaches for most after a batch import, where every network is
   * named after its file (FR-28).
   */
  readonly renameRequested = output<void>();
  /** One of the three export formats for this row's network. */
  readonly exportRequested = output<ExportFormat>();
  /** FR-26: fork this network into a configuration variant, without opening it first. */
  readonly duplicateRequested = output<void>();
  /** FR-15's typed deletion, unchanged by having moved into a menu. */
  readonly deleteRequested = output<void>();

  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly registry = inject(ActionMenuRegistry);
  private readonly toggleButton = viewChild.required<ElementRef<HTMLButtonElement>>('toggleButton');
  private readonly menu = viewChild<ElementRef<HTMLElement>>('menu');

  /** Ids are per instance: FR-26 puts one of these on every row, and duplicates break `aria-*`. */
  private readonly uid = `network-actions-${++instances}`;
  readonly toggleId = `${this.uid}-toggle`;
  readonly menuId = `${this.uid}-menu`;
  readonly hintId = `${this.uid}-hint`;

  /** Open when this menu is *the* open one - see {@link ActionMenuRegistry}. */
  readonly open = computed(() => this.registry.openMenu() === this);

  readonly enabled = computed(() => {
    if (this.busy()) {
      return false;
    }
    return this.scope() === 'row' ? this.network() !== null : this.count() > 0;
  });

  /**
   * `Actions · 3 selected` for a selection; plain `Actions` for a row.
   *
   * FR-23 asks the set menu to state the count, which is the one piece of state that whole gesture
   * turns on. A row's menu has no count to state - its subject is the row it is in - so the network
   * is named in {@link accessibleName} instead, where a screen reader needs it and the column has no
   * width for it.
   */
  readonly label = computed(() => {
    if (this.scope() === 'row') {
      return 'Actions';
    }
    return this.count() === 0 ? 'Actions' : `Actions · ${this.count()} selected`;
  });

  /**
   * What the toggle is called to a screen reader, or null to let its own text name it.
   *
   * Null in the selection scope, which has one toggle and says the count in the text itself - the
   * label and an `aria-label` repeating it would be a change to a control FR-26 is not touching. A
   * row's toggle reads "Actions" fifty times over, so there it names the network.
   */
  readonly accessibleName = computed(() => {
    const network = this.network();
    return this.scope() === 'row' && network
      ? `Actions for ${network.name} version ${network.version}`
      : null;
  });

  /** FR-15: a frozen network offers no Delete at all, and this is why not. */
  readonly deleteAbsent = computed(
    () => this.scope() === 'row' && this.network()?.editable === false,
  );

  /**
   * Why Compare is not available for this selection, or null (FR-25).
   *
   * Rendered **under the entry**, not only in its `title`: FR-25 asks for the cap to be stated where
   * the action is offered, and a tooltip is not stated - it is available to a mouse that hovers long
   * enough over a control the reader has already been told they cannot use.
   */
  readonly compareBlocked = computed(() => compareBlocker(this.count()));

  /** The cap, in the one wording the window also prints. */
  readonly capSentence = PANE_LIMIT_SENTENCE;

  /**
   * Where a row menu draws itself, in viewport coordinates, or null for the selection menu.
   *
   * Taken once when the menu opens rather than tracked: the registry closes it if the page moves,
   * which is the cheap honest answer for a control that is open for a second at a time.
   */
  readonly anchor = signal<RowMenuAnchor | null>(null);

  constructor() {
    // The selection can empty while the menu is open - the last selected network is deleted from
    // its own row, or a reload reconciles it away (FR-23). A row can go the same way: a set delete
    // takes the row this menu is in. Either way a menu with no subject offers actions over nothing,
    // so it closes with it.
    effect(
      () => {
        if (!this.enabled() && this.open()) {
          this.registry.close(this);
        }
      },
      { allowSignalWrites: true },
    );

    // A row removed while its menu is open would otherwise leave the registry holding a destroyed
    // component - and with it the document listeners that only exist while something is open.
    inject(DestroyRef).onDestroy(() => this.registry.close(this));
  }

  toggle(): void {
    if (!this.enabled()) {
      return;
    }
    if (this.open()) {
      this.close();
      return;
    }
    this.anchorToToggle();
    this.registry.open(this);
  }

  /** Closes, optionally returning focus to the toggle - always do so when the keyboard closed it. */
  close(returnFocus = false): void {
    if (!this.open()) {
      return;
    }
    this.registry.close(this);
    if (returnFocus) {
      this.toggleButton().nativeElement.focus();
    }
  }

  // -------------------------------------------------------------- what the registry calls back

  contains(node: Node): boolean {
    return this.host.nativeElement.contains(node);
  }

  closeQuietly(): void {
    this.close();
  }

  closeAndRestoreFocus(): void {
    this.close(true);
  }

  // ------------------------------------------------------------------------------ choosing

  choose(action: 'delete' | 'export' | 'compare'): void {
    this.close(true);
    switch (action) {
      case 'delete':
        this.deleteSelected.emit();
        break;
      case 'export':
        this.exportSelected.emit();
        break;
      case 'compare':
        this.compareSelected.emit();
        break;
    }
  }

  chooseRename(): void {
    this.close(true);
    this.renameRequested.emit();
  }

  chooseExport(format: ExportFormat): void {
    this.close(true);
    this.exportRequested.emit(format);
  }

  chooseDuplicate(): void {
    this.close(true);
    this.duplicateRequested.emit();
  }

  chooseDelete(): void {
    this.close(true);
    this.deleteRequested.emit();
  }

  // ------------------------------------------------------------------------------ keyboard

  /** Down opens the menu onto its first item, the way a menu button is expected to. */
  onToggleKeydown(event: KeyboardEvent): void {
    if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') {
      return;
    }
    if (!this.enabled()) {
      return;
    }
    event.preventDefault();
    const last = event.key === 'ArrowUp';
    this.anchorToToggle();
    this.registry.open(this);
    // A macrotask, not a microtask: the menu is drawn by change detection, which zone.js runs when
    // the microtask queue *drains*, so a `queueMicrotask` here would look for items that the
    // template has not rendered yet.
    setTimeout(() => this.focusItem(last ? -1 : 0));
  }

  /** Down/Up/Home/End rove the enabled items. Leaving is Escape, or focus going elsewhere. */
  onMenuKeydown(event: KeyboardEvent): void {
    const items = this.items();
    if (!items.length) {
      return;
    }
    const current = items.indexOf(document.activeElement as HTMLButtonElement);

    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault();
        this.focusItem(current + 1);
        break;
      case 'ArrowUp':
        event.preventDefault();
        this.focusItem(current <= 0 ? -1 : current - 1);
        break;
      case 'Home':
        event.preventDefault();
        this.focusItem(0);
        break;
      case 'End':
        event.preventDefault();
        this.focusItem(-1);
        break;
      default:
        break;
    }
  }

  /**
   * Focus leaving the component closes it - which is how Tab gets out.
   *
   * `focusout` rather than a `Tab` case in the handler above, and the difference is not cosmetic:
   * closing on the keydown would remove the focused button from the DOM *before* the browser
   * performs the move, so focus would reset to `<body>` and Tab would then walk from the top of the
   * page. Reading `relatedTarget` after the fact asks the only question that matters - did focus land
   * inside this component or outside it - and gets Shift-Tab and a click-away for free.
   */
  onFocusOut(event: FocusEvent): void {
    if (!this.open()) {
      return;
    }
    const landed = event.relatedTarget;
    if (landed instanceof Node && this.contains(landed)) {
      return;
    }
    this.close();
  }

  // ------------------------------------------------------------------------------ internals

  /**
   * Pins a row menu to the toggle's viewport rectangle (see the class note on `.table-responsive`).
   *
   * Right-aligned, because the actions column is the last one and a left-aligned menu would hang
   * off the table. It opens upward when there is more room above than below and below is short -
   * the last rows of a long table, which is exactly where the clipping this avoids used to bite.
   */
  private anchorToToggle(): void {
    if (this.scope() !== 'row') {
      this.anchor.set(null);
      return;
    }
    const rect = this.toggleButton().nativeElement.getBoundingClientRect();
    const below = window.innerHeight - rect.bottom;
    const flip = below < ROW_MENU_ROOM && rect.top > below;
    this.anchor.set({
      top: flip ? null : rect.bottom + 2,
      bottom: flip ? window.innerHeight - rect.top + 2 : null,
      right: Math.max(0, window.innerWidth - rect.right),
    });
  }

  /** The focusable entries - disabled ones are skipped, as a menu is expected to skip them. */
  private items(): HTMLButtonElement[] {
    const menu = this.menu();
    return menu
      ? Array.from(menu.nativeElement.querySelectorAll<HTMLButtonElement>('button:not([disabled])'))
      : [];
  }

  /** Focuses by index, wrapping; a negative index counts from the end, so `-1` is the last. */
  private focusItem(index: number): void {
    const items = this.items();
    if (!items.length) {
      return;
    }
    const wrapped = ((index % items.length) + items.length) % items.length;
    items[wrapped].focus();
  }
}

/** Where a row menu sits, in viewport coordinates. Exactly one of `top`/`bottom` is set. */
export interface RowMenuAnchor {
  readonly top: number | null;
  readonly bottom: number | null;
  readonly right: number;
}
