import { ChangeDetectionStrategy, Component, ElementRef, computed, input, output, signal, viewChild } from '@angular/core';

/**
 * The drag-and-drop upload target of the `shared/` inventory; first used by the data-import
 * wizard step 1.
 *
 * Accepts the canonical CSV set or a single `.xlsx` workbook, and hands the files to the
 * caller. It does **not** parse them: the server does that, once, for both the preview and the
 * validated import. A browser-side parser here would be a second CSV and XLSX
 * implementation, and "the delimiter was detected differently" a class of bug.
 *
 * Two ways in, because both are natural for the same task: drop a folder's worth of files, or click to
 * open the picker. Dropped and picked files accumulate rather than replace, so a user who forgot
 * `node_products.csv` can add it without starting again - {@link removed} takes one back out.
 */
@Component({
  selector: 'app-file-drop',
  standalone: true,
  templateUrl: './file-drop.component.html',
  styleUrl: './file-drop.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FileDropComponent {
  /** Extensions to accept, as the `accept` attribute spells them. */
  readonly accept = input('.csv,.tsv,.xlsx,.xlsm,.xml');
  readonly disabled = input(false);

  /**
   * What the target invites the user to drop, and the sentence under it.
   *
   * Null keeps the wizard's own wording, which names the five canonical files and marks
   * them up as code. They are inputs rather than projected content so that the default survives
   * unchanged: a caller that supplies neither is byte-identical to what this component rendered
   * before they existed. The project archive is the second caller and accepts exactly one
   * `.zip`, so it supplies both.
   */
  readonly prompt = input<string | null>(null);
  readonly hint = input<string | null>(null);
  /** Files already chosen; owned by the caller so it survives navigation between wizard steps. */
  readonly files = input<readonly File[]>([]);

  /** Files the user just added. The caller merges them into its own list. */
  readonly added = output<readonly File[]>();
  readonly removed = output<File>();

  private readonly picker = viewChild.required<ElementRef<HTMLInputElement>>('picker');

  /** True while a drag is over the target, for the highlight. */
  readonly dragging = signal(false);

  readonly summary = computed(() => {
    const count = this.files().length;
    if (count === 0) {
      return 'No files chosen yet.';
    }
    const bytes = this.files().reduce((total, file) => total + file.size, 0);
    return `${count} file${count === 1 ? '' : 's'}, ${formatBytes(bytes)}`;
  });

  openPicker(): void {
    if (!this.disabled()) {
      this.picker().nativeElement.click();
    }
  }

  onPicked(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.emit(input.files);
    // Cleared so picking the same file twice in a row still fires a change event.
    input.value = '';
  }

  onDragOver(event: DragEvent): void {
    if (this.disabled()) {
      return;
    }
    // Without preventDefault the browser navigates to the dropped file, losing the whole wizard.
    event.preventDefault();
    this.dragging.set(true);
  }

  onDragLeave(): void {
    this.dragging.set(false);
  }

  onDrop(event: DragEvent): void {
    if (this.disabled()) {
      return;
    }
    event.preventDefault();
    this.dragging.set(false);
    this.emit(event.dataTransfer?.files ?? null);
  }

  onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      this.openPicker();
    }
  }

  remove(file: File): void {
    this.removed.emit(file);
  }

  size(file: File): string {
    return formatBytes(file.size);
  }

  private emit(list: FileList | null): void {
    const files = Array.from(list ?? []);
    if (files.length) {
      this.added.emit(files);
    }
  }
}

/** `12.4 kB` - SI units, because that is what a file manager shows next to the same file. */
function formatBytes(bytes: number): string {
  if (bytes < 1000) {
    return `${bytes} B`;
  }
  if (bytes < 1_000_000) {
    return `${(bytes / 1000).toFixed(1)} kB`;
  }
  return `${(bytes / 1_000_000).toFixed(1)} MB`;
}
