package com.gridbackgammon.ui.board

import com.gridbackgammon.game.Board
import com.gridbackgammon.game.Player
import com.gridbackgammon.game.Track
import com.gridbackgammon.game.Variant
import kotlin.math.abs
import kotlin.math.floor

/** A point in column units, from the top-left of the playing surface. Kept free of Compose types so it can be unit-tested. */
data class Spot(val x: Float, val y: Float, val scale: Float = 1f)

/** One of the thirty checkers. Checkers are interchangeable to the rules; the board gives them names so each can be animated. */
data class Checker(val player: Player, val id: Int)

/** Where a checker sits: a place in [Board]'s layout, and its height in the stack there. */
data class Seat(val place: Int, val index: Int, val count: Int)

/**
 * The board, upright on a phone: twelve points along the top, twelve along
 * the bottom, a bar down the middle, and a tray for borne-off checkers along
 * each end. Everything is measured in columns -- the width of one point.
 *
 * The short game has Red's home bottom right and Blue's top right, and the two
 * run opposite ways round. The long game is the same board turned upside down,
 * which puts Red's head top right, its home bottom right, and Blue diagonally
 * opposite: both run anticlockwise.
 */
object Geometry {
    const val COLUMNS = 13
    const val BAR_COLUMN = 6

    /** Depth of each tray, the length of a point, and the gap between the two rows of tips. */
    const val TRAY = 1f
    const val POINT = 7f
    const val GAP = 1.2f
    const val HEIGHT = 2 * TRAY + 2 * POINT + GAP

    /** How far into its point a triangle reaches, as a share of the point's length. */
    const val TIP = 0.9f

    /** A stack keeps full spacing up to this many, then closes up to stay within its point. */
    private const val FULL = 5
    private const val FIRST = 0.55f
    private const val SPAN = POINT - FIRST - 0.5f

    /** Borne-off checkers are drawn smaller, fifteen to a tray. */
    const val TRAY_SCALE = 0.74f
    private const val TRAY_PITCH = COLUMNS / Track.CHECKERS.toFloat()

    fun top(variant: Variant, point: Int): Boolean = when (variant) {
        Variant.SHORT -> point >= 12
        Variant.LONG -> point < 12
    }

    /** Column 0..12, skipping the bar. */
    fun column(point: Int): Int {
        val slot = if (point < 12) 11 - point else point - 12
        return if (slot >= 6) slot + 1 else slot
    }

    /** The point in a row and column, or null for the bar. */
    fun point(variant: Variant, top: Boolean, column: Int): Int? {
        if (column == BAR_COLUMN || column !in 0 until COLUMNS) return null
        val slot = if (column > BAR_COLUMN) column - 1 else column
        val low = if (variant == Variant.SHORT) !top else top
        return if (low) 11 - slot else slot + 12
    }

    /** Distance between the centres of neighbours in a stack of [count]. */
    fun pitch(count: Int): Float = if (count <= FULL) 1f else minOf(1f, SPAN / (count - 1))

    fun spot(variant: Variant, seat: Seat): Spot = when {
        seat.place < Track.POINTS -> {
            val reach = FIRST + seat.index * pitch(seat.count)
            val x = column(seat.place) + 0.5f
            if (top(variant, seat.place)) Spot(x, TRAY + reach) else Spot(x, HEIGHT - TRAY - reach)
        }
        seat.place < Board.OFF -> {
            // Out from the middle of the bar, each side into the half where it comes back in.
            val red = seat.place == Board.BAR + Player.RED.ordinal
            val reach = GAP / 2 + FIRST + seat.index * pitch(seat.count)
            val upward = if (variant == Variant.SHORT) red else !red
            Spot(BAR_COLUMN + 0.5f, if (upward) HEIGHT / 2 - reach else HEIGHT / 2 + reach)
        }
        else -> {
            val player = Player.entries[seat.place - Board.OFF]
            val slot = if (fromRight(variant, player)) Track.CHECKERS - 1 - seat.index else seat.index
            Spot((slot + 0.5f) * TRAY_PITCH, trayY(player), TRAY_SCALE)
        }
    }

    /** Each tray is filled from the end next to its owner's home board. */
    private fun fromRight(variant: Variant, player: Player) = !(variant == Variant.LONG && player == Player.BLUE)

    /** Red bears off at the bottom, towards its player; Blue at the top. */
    fun trayY(player: Player): Float = if (player == Player.RED) HEIGHT - TRAY / 2 else TRAY / 2

    /** Slot [k]'s centre in a tray, for drawing the empty slots. */
    fun traySlot(k: Int): Float = (k + 0.5f) * TRAY_PITCH

    /** The next free place in a stack: where a checker moving to [place] would land. */
    fun landing(variant: Variant, board: Board, player: Player, place: Int): Spot {
        val own = board.at(player, place)
        // Onto a blot it lands where the blot is.
        val index = if (place < Track.POINTS && board.count(player.other, place) > 0) 0 else own
        return spot(variant, Seat(place, index, index + 1))
    }

    /**
     * What a tap at ([x], [y]) is on: a point, a bar or a tray, as a place in
     * [Board]'s layout. Points and bar are split at the middle, so the whole
     * surface answers.
     */
    fun place(variant: Variant, x: Float, y: Float): Int? {
        if (x < 0f || x >= COLUMNS || y < 0f || y >= HEIGHT) return null
        if (y < TRAY) return Board.OFF + Player.BLUE.ordinal
        if (y >= HEIGHT - TRAY) return Board.OFF + Player.RED.ordinal
        val top = y < HEIGHT / 2
        val column = floor(x).toInt()
        if (column == BAR_COLUMN) {
            val redHalf = if (variant == Variant.SHORT) top else !top
            return Board.BAR + if (redHalf) Player.RED.ordinal else Player.BLUE.ordinal
        }
        return point(variant, top, column)
    }

    /**
     * Points are narrower than a fingertip, so a tap that lands on nothing
     * useful is given to a neighbouring point that is, if one is close enough.
     */
    fun snap(variant: Variant, x: Float, y: Float, wanted: Set<Int>): Int? {
        val direct = place(variant, x, y) ?: return null
        // A tray is a wide target already. The bar is a column like any other:
        // with nothing on it, a tap there is meant for a point beside it.
        if (direct in wanted || direct >= Board.OFF) return direct
        val top = y < HEIGHT / 2
        return (-1..1)
            .mapNotNull { d ->
                val column = floor(x).toInt() + d
                val point = point(variant, top, column) ?: return@mapNotNull null
                if (point !in wanted) return@mapNotNull null
                val distance = abs(x - (column + 0.5f))
                if (distance < REACH) point to distance else null
            }
            .minByOrNull { it.second }
            ?.first
            ?: direct
    }

    /** How far from a point's centre line a tap still counts for it, in columns. */
    private const val REACH = 1.1f

    /**
     * Gives every checker a seat on [board], moving as few as possible from
     * [previous]: a stack that shrank gives up its top checkers, a stack that
     * grew takes them. After one step that is exactly one checker -- the one
     * lifted off the top of its point and put down on the next.
     */
    fun seats(previous: Map<Checker, Seat>?, board: Board): Map<Checker, Seat> {
        val result = HashMap<Checker, Seat>(Track.CHECKERS * 2)
        for (player in Player.entries) {
            val stacks = Array(Board.SIZE) { ArrayList<Int>() }
            val free = ArrayDeque<Int>()
            if (previous == null) {
                (0 until Track.CHECKERS).forEach(free::addLast)
            } else {
                (0 until Track.CHECKERS)
                    .sortedBy { previous.getValue(Checker(player, it)).index }
                    .forEach { stacks[previous.getValue(Checker(player, it)).place].add(it) }
                for (place in stacks.indices) {
                    val stack = stacks[place]
                    while (stack.size > board.at(player, place)) free.addLast(stack.removeAt(stack.lastIndex))
                }
            }
            for (place in stacks.indices) {
                val stack = stacks[place]
                while (stack.size < board.at(player, place)) stack.add(free.removeFirst())
                stack.forEachIndexed { index, id -> result[Checker(player, id)] = Seat(place, index, stack.size) }
            }
        }
        return result
    }
}
