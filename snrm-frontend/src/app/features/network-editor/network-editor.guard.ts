import { CanDeactivateFn } from '@angular/router';

import type { NetworkEditorComponent } from './network-editor.component';

/**
 * Flushes pending canvas edits before the editor is left.
 *
 * The component decides: it flushes, and only asks the user when something is *still* unsent
 * afterwards - which means the save failed, so leaving really would lose the edit.
 *
 * `import type` keeps this a compile-time reference only, so the guard does not drag the editor -
 * and Cytoscape with it - into the eagerly loaded bundle.
 */
export const networkEditorGuard: CanDeactivateFn<NetworkEditorComponent> = (component) =>
  component.confirmLeave();
