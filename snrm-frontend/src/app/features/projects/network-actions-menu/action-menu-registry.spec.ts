import { ActionMenuRegistry, RegisteredMenu } from './action-menu-registry';

/**
 * A stand-in for one `NetworkActionsMenuComponent`, closing itself the way the component does.
 *
 * `own` is the node it considers part of itself, which is what `contains` answers about.
 */
class FakeMenu implements RegisteredMenu {
  quiet = 0;
  restored = 0;

  constructor(
    private readonly registry: ActionMenuRegistry,
    private readonly own: Node | null = null,
  ) {}

  contains(node: Node): boolean {
    return this.own !== null && this.own.contains(node);
  }

  closeQuietly(): void {
    this.quiet += 1;
    this.registry.close(this);
  }

  closeAndRestoreFocus(): void {
    this.restored += 1;
    this.registry.close(this);
  }
}

/**
 * "Which menu is open" is one value (FR-23, FR-26).
 *
 * FR-26 puts one of these menus on every row, which is what makes the single value worth asserting:
 * with a signal per row, two menus open at once is representable and the closing of one becomes
 * something the other has to remember to do.
 */
describe('ActionMenuRegistry', () => {
  let registry: ActionMenuRegistry;
  let host: HTMLElement;

  beforeEach(() => {
    registry = new ActionMenuRegistry();
    host = document.createElement('div');
    host.innerHTML = '<button type="button">Actions</button>';
    document.body.appendChild(host);
  });

  afterEach(() => {
    // A test that leaves a menu open leaves the registry's document listeners attached with it.
    // Closing here is the component's `DestroyRef` hook said in a spec.
    const open = registry.openMenu();
    if (open) {
      registry.close(open);
    }
    host.remove();
  });

  it('holds nothing until a menu opens', () => {
    expect(registry.openMenu()).toBeNull();
  });

  it('opening one menu closes the other, without being told to', () => {
    const first = new FakeMenu(registry);
    const second = new FakeMenu(registry);

    registry.open(first);
    registry.open(second);

    expect(registry.openMenu()).toBe(second);
    // Nothing was called on `first`: its `open` is a computed over this one value, so replacing the
    // value *is* closing it. That is the whole point of there being one.
    expect(first.quiet).toBe(0);
    expect(first.restored).toBe(0);
  });

  it('closing a menu that is no longer the open one changes nothing', () => {
    const first = new FakeMenu(registry);
    const second = new FakeMenu(registry);
    registry.open(first);
    registry.open(second);

    registry.close(first);

    expect(registry.openMenu()).toBe(second);
  });

  it('closes quietly on a click outside the open menu', () => {
    const menu = new FakeMenu(registry, host);
    registry.open(menu);

    document.body.dispatchEvent(new MouseEvent('click', { bubbles: true }));

    expect(menu.quiet).toBe(1);
    expect(registry.openMenu()).toBeNull();
  });

  it('leaves the menu open when the click is inside it', () => {
    const menu = new FakeMenu(registry, host);
    registry.open(menu);

    host.querySelector('button')!.dispatchEvent(new MouseEvent('click', { bubbles: true }));

    expect(menu.quiet).toBe(0);
    expect(registry.openMenu()).toBe(menu);
  });

  it('closes on Escape and hands focus back, so a keyboard user is never left nowhere', () => {
    const menu = new FakeMenu(registry, host);
    registry.open(menu);

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));

    expect(menu.restored).toBe(1);
    expect(menu.quiet).toBe(0);
  });

  it('ignores keys that are not Escape', () => {
    const menu = new FakeMenu(registry, host);
    registry.open(menu);

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown' }));

    expect(registry.openMenu()).toBe(menu);
  });

  it('closes quietly when the page scrolls under a row menu', () => {
    // A row menu is pinned to the toggle's viewport rectangle (`.table-responsive` would clip an
    // absolutely positioned one), so a page that moves leaves it pointing at the wrong row.
    // Quietly, because a scroll is a mouse gesture and pulling focus back would scroll it home.
    const menu = new FakeMenu(registry, host);
    registry.open(menu);

    document.dispatchEvent(new Event('scroll'));

    expect(menu.quiet).toBe(1);
    expect(menu.restored).toBe(0);
    expect(registry.openMenu()).toBeNull();
  });

  it('closes on a resize for the same reason', () => {
    const menu = new FakeMenu(registry, host);
    registry.open(menu);

    window.dispatchEvent(new Event('resize'));

    expect(menu.quiet).toBe(1);
  });

  it('does nothing once nothing is open', () => {
    const menu = new FakeMenu(registry, host);
    registry.open(menu);
    registry.close(menu);

    document.body.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    document.dispatchEvent(new Event('scroll'));

    expect(menu.quiet).toBe(0);
    expect(menu.restored).toBe(0);
  });
});
