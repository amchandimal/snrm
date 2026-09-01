import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';

import { RunResultsStore } from '../run-results.store';

/**
 * The period cursor's controls - scrub, step, and the readout (FR-22).
 *
 * > "A period cursor - scrub slider, step buttons, arrow keys - is shared by every chart and every
 * > per-period figure on the page, and the miniature tints availability and fill at the cursor's
 * > period."
 *
 * ## Three controls and no fourth
 *
 * There is no play button here, and its absence is the design rather than an omission. This is
 * deliberately a cursor, not a clock: the dashboard navigates a run; animating one is
 * playback, which belongs to the editor canvas and is one `requestAnimationFrame` loop in the
 * whole application, not two. So this component owns no timer, no frame loop and no speed - it
 * writes one integer, and every surface on the page reads it. Compare
 * `network-editor/playback-bar`, which has all four of those and is the other thing.
 *
 * ## It owns no state, and the arrow keys are not here
 *
 * Every control is a call into {@link RunResultsStore} and every reading is one of its signals, for
 * the reason the playback bar states: the cursor is shared with four other surfaces, so it cannot
 * live in the component that happens to draw its slider. The **arrow keys** are on
 * `ResultsDashboardComponent` instead, because they have to work while the reader's eye is on a
 * chart at the bottom of the page rather than on this bar - a listener here would only fire while
 * one of these three controls had focus, which is precisely when the browser already handles them.
 *
 * ## The slider's own arrow keys are the reason the guard matters
 *
 * A focused `<input type="range">` moves by one step - one period - per arrow key, natively. The
 * page-level listener excludes form controls (`core/text-entry.ts`), so the two never both fire and
 * the cursor never jumps two periods for one keystroke. That is the editor's own reasoning about
 * its transport bar's scrub slider, applied to this one.
 */
@Component({
  selector: 'app-period-cursor',
  standalone: true,
  templateUrl: './period-cursor.component.html',
  styleUrl: './period-cursor.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PeriodCursorComponent {
  readonly store = inject(RunResultsStore);

  /**
   * The slider's bounds and position, as the DOM wants them.
   *
   * Strings rather than numbers, following the playback bar and the two range inputs in
   * `scenario-builder/event-editor`: an `<input>`'s `max` and `value` are string properties, and
   * binding through the type they actually have keeps the template honest about where the conversion
   * happens.
   */
  readonly lastPeriodText = computed(() => String(this.store.lastCursorPeriod()));

  readonly periodText = computed(() => String(this.store.cursorPeriod()));

  /** True at the run's first period - the step-back control has nowhere to go. */
  readonly atStart = computed(() => this.store.cursorPeriod() <= 0);

  readonly atEnd = computed(() => this.store.cursorPeriod() >= this.store.lastCursorPeriod());

  onScrub(value: string): void {
    const period = Number(value);
    if (Number.isFinite(period)) {
      this.store.setCursor(period);
    }
  }

  stepBack(): void {
    this.store.stepCursor(-1);
  }

  stepForward(): void {
    this.store.stepCursor(1);
  }

  /** Back to the run's end state - where the page opened, and what its scalars describe. */
  toEnd(): void {
    this.store.setCursor(this.store.lastCursorPeriod());
  }
}
