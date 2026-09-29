/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.text.keyboard

import kotlin.math.abs

/**
 * How far the finger has to travel up or down the space bar before the cursor changes line (issue #364).
 *
 * The horizontal half of the glide has a natural unit: one of the swipe detector's units is one
 * character, and how wide a unit is, is the user's own `swipeDistanceThreshold` knob. The vertical half
 * has no such luck. A line of text is far taller than a character is wide, so counting lines in the same
 * units sends the cursor up the page several lines per centimetre of finger — and shrinking the
 * threshold, which is a reasonable thing to want for the horizontal half, would make it worse in exact
 * proportion.
 *
 * So the vertical step is pinned to a real distance instead and converted into whatever units are
 * currently in force. Turning the threshold down buys a finer character step and leaves the line step
 * where it is, which is what someone asking for either of them actually means.
 *
 * A thumb does not travel in straight lines, though (issue #428). Scrubbing sideways it swings on its
 * joint, and the arc carries it up or down the space bar as it goes — far enough, at 24 dp, that a glide
 * meant to move three characters jumped a line instead, which in a one-line message means the start or
 * the end of the text. Two rules keep that drift from counting, both below: a glide that set out sideways
 * has to be pushed a whole key row before its first line, and a line once taken is only given back when
 * the finger returns clearly past it.
 */
object SpaceGlide {
    /**
     * Finger travel for one line, in dp. Roughly a line of body text, which is what the gesture is
     * imitating — the cursor should end up where the finger points rather than somewhere it has to be
     * hunted back from. Untested by feel on anything but a phone in portrait; it is one number to turn.
     */
    const val LINE_TRAVEL_DP = 24.0

    /**
     * Finger travel for the first line of a glide that set out sideways, in dp (issue #428) — about a key
     * row, so the finger has to leave the space bar's row for good rather than wander to its edge.
     *
     * Only the first line costs this much. Once the finger has gone that far on purpose it is navigating
     * by line, and every line after it is [LINE_TRAVEL_DP] again. A glide that sets out vertically never
     * pays it at all, so the diagonal of issue #364 — three lines up and a few words in — still starts
     * the way it always did.
     *
     * Downwards is the tight direction: about 77 dp lie between the middle of the space bar and the bottom
     * of the screen, so this leaves room for a first line and a second one, not for a larger number.
     */
    const val SIDEWAYS_FIRST_LINE_TRAVEL_DP = 48.0

    /**
     * How far back past a line's boundary the finger has to come before the cursor returns to the line
     * it came from, in dp (issue #428).
     *
     * Without it a finger resting just past a boundary — which is where it is right after taking a line,
     * and where it stays while it scrubs along that line — sends the cursor back and forth with every
     * tremor. Going further out is never delayed; only the way back is.
     */
    const val RETURN_SLACK_DP = 12.0

    /** The axis that moved the cursor first in a glide: the one the thumb set out on (issue #428). */
    enum class Axis { SIDEWAYS, VERTICAL }

    /**
     * The vertical geometry of one glide in the detector's units: [perLine] for each line, [firstLine]
     * for the first one, and the [returnSlack] a line has to be cleared by on the way back.
     */
    data class LineSteps(val perLine: Int, val firstLine: Int, val returnSlack: Int)

    /**
     * The detector's units that make up one line, given the user's [swipeDistanceThresholdDp]. Its unit
     * is a quarter of that threshold — see `SwipeGesture.Detector.onTouchMove`.
     *
     * Rounded rather than truncated so a threshold that divides badly errs towards the calmer step. The
     * floor of one exists only to survive a threshold outside the slider's 12..72 dp; it is deliberately
     * not two. A floor of two binds nowhere below 65 dp, and above it forces a 36 dp line — the one part
     * of the range where it would be doing the opposite of its job. Jitter needs no guarding there
     * either, since a single unit is already 16 dp by then.
     */
    fun unitsPerLine(swipeDistanceThresholdDp: Int): Int {
        val unitDp = swipeDistanceThresholdDp / 4.0
        if (unitDp <= 0.0) return 1
        return Math.round(LINE_TRAVEL_DP / unitDp).toInt().coerceAtLeast(1)
    }

    /**
     * The [LineSteps] of a glide under the user's [swipeDistanceThresholdDp], depending on whether it
     * [beganSideways] (issue #428). Rounded like [unitsPerLine], and for the same reason.
     *
     * The slack stays below a line. That is what makes the way back land where the glide set out: at
     * least one unit short of the first boundary, the start itself always reads as the starting line.
     * Where a line is a single unit — thresholds of 65 dp and up — there is no slack left at all, and
     * none is needed, since one unit is already 16 dp or more of travel.
     */
    fun lineSteps(swipeDistanceThresholdDp: Int, beganSideways: Boolean): LineSteps {
        val perLine = unitsPerLine(swipeDistanceThresholdDp)
        val unitDp = swipeDistanceThresholdDp / 4.0
        if (unitDp <= 0.0) return LineSteps(perLine = perLine, firstLine = perLine, returnSlack = 0)
        val firstLine = if (beganSideways) {
            Math.round(SIDEWAYS_FIRST_LINE_TRAVEL_DP / unitDp).toInt().coerceAtLeast(perLine)
        } else {
            perLine
        }
        val returnSlack = Math.round(RETURN_SLACK_DP / unitDp).toInt().coerceIn(0, perLine - 1)
        return LineSteps(perLine = perLine, firstLine = firstLine, returnSlack = returnSlack)
    }

    /**
     * Which line the finger stands on, counted from where the glide began. Negative is upwards, matching
     * the screen's y axis. The first line is [unitsForFirstLine] away, every further one [unitsPerLine].
     *
     * Deliberately computed from the travel so far rather than accumulated from each report: a glide that
     * turns around has to retrace exactly, and a sum of rounded steps does not. It also means the first
     * report cannot move anything, which is the same half-unit of grace the horizontal half gets from its
     * `- 1` on the opening move.
     */
    fun lineAt(absUnitCountY: Int, unitsPerLine: Int, unitsForFirstLine: Int = unitsPerLine): Int {
        if (unitsPerLine <= 0) return 0
        val travel = abs(absUnitCountY)
        if (travel < unitsForFirstLine) return 0
        val lines = 1 + (travel - unitsForFirstLine) / unitsPerLine
        return if (absUnitCountY < 0) -lines else lines
    }

    /**
     * The line the cursor should stand on now that the finger is at [absUnitCountY], given that it
     * stands on [current] (issue #428).
     *
     * Further out than [current] is taken at once — the finger plainly went there. Back towards the start
     * is taken only once the travel has cleared the boundary behind the cursor by [LineSteps.returnSlack],
     * which is what keeps a finger scrubbing along a boundary from flicking the cursor between two lines.
     * Still read off the total travel, so a glide that comes all the way back is on its starting line
     * again, exactly.
     */
    fun nextLine(current: Int, absUnitCountY: Int, steps: LineSteps): Int {
        val target = lineAt(absUnitCountY, steps.perLine, steps.firstLine)
        val goesOut = when {
            current > 0 -> target > current
            current < 0 -> target < current
            else -> true
        }
        if (target == current || goesOut) return target
        val held = if (current > 0) absUnitCountY + steps.returnSlack else absUnitCountY - steps.returnSlack
        return lineAt(held, steps.perLine, steps.firstLine)
    }

    /**
     * The line the cursor may actually move to, given that each direction has its own preference and
     * either may be switched off.
     *
     * A refused move must leave the count where it was, not merely skip the arrow presses. The count is
     * the cursor's position, and letting it follow a finger the cursor did not follow makes the way back
     * overshoot by exactly the refused distance — glide three lines up with the downward half turned off,
     * come back, and the cursor would sail three lines past where it started.
     */
    fun allowedLine(current: Int, target: Int, upAllowed: Boolean, downAllowed: Boolean): Int = when {
        target == current -> current
        target < current -> if (upAllowed) target else current
        else -> if (downAllowed) target else current
    }
}
