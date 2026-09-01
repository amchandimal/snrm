import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

import { NodeType } from '../../../core/models';
import { NODE_TYPE_PROFILES, NodeTypeProfile } from '../echelon-rules';

/**
 * The fixed four-type palette on the left of the canvas.
 *
 * > "a fixed palette on the left lists the four node types as colour-coded tiles. The user drags a
 * > tile onto the canvas (HTML5 drag-and-drop)"
 *
 * The tile only *starts* the gesture: it writes the type into the drag payload and lets
 * `graph-canvas.component.ts` - which owns the coordinate system - decide where the drop landed.
 *
 * Clicking a tile does not create a node. It sets the type that double-clicking empty canvas will
 * use, which is the "last-used type" shortcut made visible instead of implicit.
 */
@Component({
  selector: 'app-node-palette',
  standalone: true,
  templateUrl: './node-palette.component.html',
  styleUrl: './node-palette.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NodePaletteComponent {
  /** Highlighted tile: the type a double-click on empty canvas would create. */
  readonly lastUsedType = input.required<NodeType>();
  /** Frozen networks grey the palette out and route the gesture to the fork prompt. */
  readonly disabled = input(false);

  readonly typePicked = output<NodeType>();

  readonly profiles = NODE_TYPE_PROFILES;

  /**
   * MIME type carrying the node type through the HTML5 drag.
   *
   * A custom type rather than `text/plain` so a stray drag of selected text from elsewhere on the
   * page cannot be mistaken for a palette tile.
   */
  static readonly DRAG_TYPE = 'application/x-snrm-node-type';

  onDragStart(event: DragEvent, profile: NodeTypeProfile): void {
    if (this.disabled() || !event.dataTransfer) {
      event.preventDefault();
      return;
    }
    event.dataTransfer.effectAllowed = 'copy';
    event.dataTransfer.setData(NodePaletteComponent.DRAG_TYPE, profile.type);
    // Some browsers refuse a drag with no standard-format payload; the canvas ignores this one.
    event.dataTransfer.setData('text/plain', profile.label);
    this.typePicked.emit(profile.type);
  }

  onSelect(profile: NodeTypeProfile): void {
    if (!this.disabled()) {
      this.typePicked.emit(profile.type);
    }
  }
}
