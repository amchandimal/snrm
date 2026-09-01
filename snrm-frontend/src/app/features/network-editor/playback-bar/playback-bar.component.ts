import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';

import { periodReadout } from '../../../core/metric-display';
import { SPEED_LADDER, isLadderSpeed } from '../playback-clock';
import { PlaybackStore } from '../playback.store';

/**
 * The transport bar of visual playback (FR-18).
 *
 * > "a transport bar (play/pause, step, scrub, speed) over the canvas"
 *
 * ## It sits *over* the canvas, not beside it
 *
 * Cytoscape draws into a `<canvas>`, so this is an HTML overlay positioned inside
 * `.editor__canvas` - the same trick the node tooltip uses, and for the same reason: there is no DOM
 * element to attach anything to. Unlike the tooltip it **takes pointer events**, because it is a
 * control rather than a label; it is bottom-centred and narrow so it covers the least interesting
 * part of a left-to-right echelon layout.
 *
 * Putting it in the toolbar was the alternative and is worse: the eye that is watching a node fill
 * would have to travel to the top of the screen and back to step a period, and the bar would be on
 * screen for every network whether or not anything can be played.
 *
 * ## It owns no state
 *
 * Every control is a call into {@link PlaybackStore} and every reading is one of its signals. The
 * clock advances in the store's single `requestAnimationFrame` loop (the same rule applied to
 * animation), so this component has no timer, no interval and nothing to tear down - and the scrub
 * bar's position is `currentPeriod`, which is written when the *period* changes rather than per
 * frame.
 *
 * ## The speed select is the dialog's select
 *
 * Both write through `PlaybackStore.setSpeed` into the one root `PlaybackPreferencesService`,
 * so a speed picked here is remembered for this network on this device and the two
 * controls cannot disagree about what is set. A change lands on the next frame and moves the clock
 * by nothing - the loop reads the speed every frame, which is what makes a mid-play change re-pace
 * without a jump.
 */
@Component({
  selector: 'app-playback-bar',
  standalone: true,
  templateUrl: './playback-bar.component.html',
  styleUrl: './playback-bar.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PlaybackBarComponent {
  readonly playback = inject(PlaybackStore);

  readonly speedLadder = SPEED_LADDER;

  /**
   * `Period 5 of 30 - 5 days`.
   *
   * Both halves, for the reason `formatTimeValued` keeps both of its: the index is what ties the
   * picture to a column of the run's series a reader can go and look at, and the duration is what
   * makes it mean anything to someone who does not hold this network's clock in their head. The
   * restatement goes through `core/metric-display`'s `readablePeriods`, which multiplies by the
   * period's **value** as well as reading its unit - a run stepping in `2 DAY` is at 10 days in
   * period 5, and reading the unit alone would say 5.
   *
   * The clock is the **run's**, never the live network's: a forked variant's later
   * time-base change must not relabel an old run's playback.
   *
   * The string itself is `core/metric-display`'s `periodReadout` since FR-22, because the results
   * dashboard's period cursor prints the same reading of the same run and one researcher moving
   * between the two screens must not be shown two phrasings of period 14. The wording is unchanged
   * from what this component built inline.
   */
  readonly clock = computed(() =>
    periodReadout(
      this.playback.currentPeriod(),
      this.playback.horizonPeriods(),
      this.playback.periodLength(),
    ),
  );

  /**
   * The scrub bar's bounds and position, as the DOM wants them.
   *
   * Strings rather than numbers, following the two range inputs in `scenario-builder/event-editor`:
   * an `<input>`'s `max` and `value` are string properties, and binding through the type they
   * actually have keeps the template honest about where the conversion happens.
   */
  readonly lastPeriodText = computed(() => String(this.playback.lastPeriod()));

  readonly periodText = computed(() => String(this.playback.currentPeriod()));

  /** How a ladder entry reads in the select - the dialog's wording, one place changed at a time. */
  speedLabel(speed: number): string {
    return `${speed} ${speed === 1 ? 'period' : 'periods'} / second`;
  }

  onScrub(value: string): void {
    const period = Number(value);
    if (Number.isFinite(period)) {
      // Deliberately does not pause: dragging the scrubber of a running playback should move the
      // clock and leave it running (see `PlaybackStore.seek`).
      this.playback.seek(period);
    }
  }

  onSpeed(value: string): void {
    const speed = Number(value);
    if (isLadderSpeed(speed)) {
      this.playback.setSpeed(speed);
    }
  }
}
