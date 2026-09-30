package com.gridbackgammon.game

import kotlin.math.sqrt

/**
 * One clock for the view model and the board. The model waits out exactly the
 * time the board spends animating, so the next step starts when a checker lands.
 */
object Timing {
    /** Faces a thrown die shows, the last of them the result. */
    const val TUMBLES = 10

    /** The whole throw, tap to result. */
    const val TUMBLE_MS = 600

    /**
     * When face [k] of a throw appears, in ms from the tap.
     *
     * The faces are laid along a quadratic ease-out, so the die starts as a
     * blur and slows into its result the way a real one loses its spin: about
     * 30 ms between the first faces, 190 before the last. Evenly spaced faces
     * read as a ticking counter instead.
     */
    fun tumbleAt(k: Int): Long =
        (TUMBLE_MS * (1.0 - sqrt(1.0 - k.coerceIn(0, TUMBLES) / TUMBLES.toDouble()))).toLong()

    /** The wait before face [k], for k in 1..[TUMBLES]. */
    fun tumbleGap(k: Int): Long = tumbleAt(k) - tumbleAt(k - 1)

    const val BOT_ROLL_MS = 520L

    /** The least a bot seems to think, however quickly it has decided. */
    const val BOT_THINK_MS = 420L

    /** Between a bot's steps, so each lands before the next is lifted. */
    const val BOT_GAP_MS = 140L

    /** A bot's finished turn stays on the board this long before the dice pass. */
    const val BOT_DONE_MS = 480L

    /** How long a throw that cannot be played stays on screen before the turn passes. */
    const val NO_MOVE_MS = 1100L

    /** The opening throw is read before anything acts on it; a tie before it is thrown again. */
    const val OPENING_MS = 700L

    /** One checker, one die: lifted, carried, put down. */
    const val STEP_MS = 320

    /** A hit blot leaves this long after the hitter lifts off, so it goes as the hitter arrives. */
    const val HIT_LAG_MS = 200

    /** Checkers going back to the start for a new game, or back a step on undo. */
    const val RETURN_MS = 420
}
