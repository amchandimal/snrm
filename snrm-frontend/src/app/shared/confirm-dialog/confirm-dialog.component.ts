import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';

/**
 * Modal confirmation for a destructive action.
 *
 * Deliberately dumb: the parent decides when it exists (`@if`) and what happens on confirm. That
 * keeps it usable for the editor's delete confirmations, which must list the
 * dependent data that will go with the element - pass that as the message.
 *
 * Bootstrap markup without Bootstrap's JavaScript: the backdrop and visibility are Angular's, so
 * there is no imperative modal instance to keep in sync with a signal.
 *
 * ## Typed confirmation
 *
 * Set {@link requiredPhrase} and the dialog stops being a button and becomes a check: the user must
 * type that exact string before the action is enabled (FR-15). It exists for the one action
 * that has no undo and no fork to fall back on - deleting a network takes its nodes, links and
 * per-product rows with it. A click is proportional to an edit; typing a name is proportional to
 * that.
 *
 * The comparison is exact after trimming the ends. Not case-insensitive: a phrase the user is asked
 * to reproduce is one they have to read, and "close enough" defeats the delay the check is for.
 *
 * ## Projected content
 *
 * The body carries an `<ng-content />` between {@link details} and the phrase field, for a caller
 * whose "what is about to happen" is a *structure* rather than a list of sentences - the set
 * deletion of FR-23 names two groups of networks there, "these will be deleted" and "these are
 * frozen and will not", each row a network, before the typed phrase is accepted.
 * Projecting nothing renders byte-identically to what this component drew before the slot existed,
 * which is what keeps the five existing callers untouched - the same rule `shared/file-drop`'s two
 * optional inputs follow.
 *
 * ## A confirm the caller can withhold
 *
 * {@link confirmBlocked} disables the confirm button **and leaves everything else alone**, for a
 * caller whose projected content carries a field the action depends on - FR-29's rename, where an
 * empty name, one past the server's 160-character limit, or the name the network already has are
 * three reasons not to send a request. It is not {@link busy}: busy means *a request is on
 * its way*, so it also disables Cancel, the close button and the click-away, and a dialog that
 * opened blocked (the rename field is prefilled with the current name, so it does) would have been
 * a dialog nobody could leave. Defaulting to false keeps every existing caller's rendering
 * unchanged, the same rule the projected slot follows.
 *
 * The caller shows the *reason* beside its own field; this component only withholds the button,
 * because the reason is about content it does not own.
 */
@Component({
  selector: 'app-confirm-dialog',
  standalone: true,
  templateUrl: './confirm-dialog.component.html',
  styleUrl: './confirm-dialog.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ConfirmDialogComponent {
  readonly title = input('Please confirm');
  readonly message = input.required<string>();
  /** Extra lines listing what else the action affects, e.g. dependent links. */
  readonly details = input<readonly string[]>([]);
  readonly confirmLabel = input('Confirm');
  readonly cancelLabel = input('Cancel');
  /** Renders the confirm button in danger styling and gives it the focus ring. */
  readonly destructive = input(false);
  /** Disables both buttons while the parent's request is in flight. */
  readonly busy = input(false);
  /**
   * Withholds the confirm button while the parent's own projected field is not ready to be sent.
   *
   * Distinct from {@link busy}, which also disables leaving - see the class note. Cancel, the close
   * button and the click-away all stay live.
   */
  readonly confirmBlocked = input(false);

  /**
   * When set, the exact string the user must type before confirming. Null disables the check.
   *
   * The caller chooses what it is. For a network deletion it is the owning project's name: a network
   * shares its name with every variant of it, so the network's own name would not identify what is
   * about to go, while the project's is unambiguous and is on screen to be read.
   */
  readonly requiredPhrase = input<string | null>(null);
  /** What to call the phrase in the prompt, e.g. `project name`. */
  readonly phraseLabel = input('name');

  readonly confirmed = output<void>();
  readonly cancelled = output<void>();

  /** What the user has typed into the confirmation field. */
  readonly typed = signal('');

  /**
   * True when there is no phrase to type, or the typed text matches it exactly.
   *
   * A phrase that is present but *blank* counts as unsatisfiable, not as satisfied. It means the
   * caller does not yet know what to ask for - the owning record is still loading, say - and the
   * natural implementation ("" equals "") would silently turn the check off in exactly the case where
   * the dialog has the least idea what it is about to delete.
   */
  readonly phraseMatches = computed(() => {
    const required = this.requiredPhrase();
    if (required === null) {
      return true;
    }
    const expected = required.trim();
    return expected.length > 0 && this.typed().trim() === expected;
  });

  readonly canConfirm = computed(
    () => !this.busy() && !this.confirmBlocked() && this.phraseMatches(),
  );

  onTyped(text: string): void {
    this.typed.set(text);
  }

  /** Enter in the confirmation field confirms, but only once the phrase is right. */
  onPhraseKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter' && this.canConfirm()) {
      event.preventDefault();
      this.confirmed.emit();
    }
  }

  /**
   * Clicking outside the dialog cancels, unless a request is already on its way.
   *
   * The handler sits on the scroll container rather than the tinted backdrop beneath it, which
   * Bootstrap's full-viewport `.modal` element covers and would swallow every click on. Comparing
   * target with currentTarget is what separates "clicked the surround" from "clicked in the dialog".
   */
  onBackdropClick(event: MouseEvent): void {
    if (!this.busy() && event.target === event.currentTarget) {
      this.cancelled.emit();
    }
  }
}
