package com.gridbackgammon.ui.board

import com.gridbackgammon.game.Board
import com.gridbackgammon.game.Player
import com.gridbackgammon.game.Rules
import com.gridbackgammon.game.Track
import com.gridbackgammon.game.Variant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryTest {

    @Test
    fun `every point has its own column and row, and a tap in it finds it again`() {
        for (variant in Variant.entries) {
            val cells = HashSet<Pair<Int, Boolean>>()
            for (point in 0 until Track.POINTS) {
                val column = Geometry.column(point)
                val top = Geometry.top(variant, point)
                assertTrue(column != Geometry.BAR_COLUMN)
                assertTrue(cells.add(column to top))
                val y = if (top) Geometry.TRAY + 2f else Geometry.HEIGHT - Geometry.TRAY - 2f
                assertEquals(point, Geometry.place(variant, column + 0.5f, y))
            }
        }
    }

    @Test
    fun `homes and heads sit where the rules say`() {
        // Short: Red's one-point bottom right, Blue's top right.
        assertEquals(12, Geometry.column(Track.point(Variant.SHORT, Player.RED, 23)))
        assertTrue(!Geometry.top(Variant.SHORT, Track.point(Variant.SHORT, Player.RED, 23)))
        assertTrue(Geometry.top(Variant.SHORT, Track.point(Variant.SHORT, Player.BLUE, 23)))
        assertEquals(12, Geometry.column(Track.point(Variant.SHORT, Player.BLUE, 23)))

        // Long: Red's head top right, its home bottom right; Blue's head bottom left, home top left.
        val redHead = Track.point(Variant.LONG, Player.RED, Track.HEAD)
        assertTrue(Geometry.top(Variant.LONG, redHead))
        assertEquals(12, Geometry.column(redHead))
        val redLast = Track.point(Variant.LONG, Player.RED, 23)
        assertTrue(!Geometry.top(Variant.LONG, redLast))
        assertEquals(12, Geometry.column(redLast))
        val blueHead = Track.point(Variant.LONG, Player.BLUE, Track.HEAD)
        assertTrue(!Geometry.top(Variant.LONG, blueHead))
        assertEquals(0, Geometry.column(blueHead))
        val blueLast = Track.point(Variant.LONG, Player.BLUE, 23)
        assertTrue(Geometry.top(Variant.LONG, blueLast))
        assertEquals(0, Geometry.column(blueLast))
    }

    @Test
    fun `fifteen on one point still fit inside it`() {
        for (variant in Variant.entries) {
            for (index in 0 until 15) {
                val bottom = Geometry.spot(variant, Seat(Track.point(variant, Player.RED, 23), index, 15))
                assertTrue(bottom.y > Geometry.HEIGHT - Geometry.TRAY - Geometry.POINT)
                assertTrue(bottom.y < Geometry.HEIGHT - Geometry.TRAY)
            }
        }
    }

    @Test
    fun `a tap between points goes to the nearest one that is any use`() {
        val variant = Variant.SHORT
        // Bottom row, column 3 is point 8; column 4 is point 7.
        assertEquals(8, Geometry.place(variant, 3.5f, 12f))
        assertEquals(7, Geometry.snap(variant, 3.9f, 12f, setOf(7)))
        assertEquals(8, Geometry.snap(variant, 3.9f, 12f, setOf(8, 7)))
        // Too far to count: the tap stays on what it hit.
        assertEquals(8, Geometry.snap(variant, 3.1f, 12f, setOf(10)))
        assertNull(Geometry.place(variant, -1f, 3f))
        // The bar gives way to the point beside it when the bar itself is no use.
        assertEquals(5, Geometry.snap(variant, 6.6f, 12f, setOf(5)))
        val redBar = Board.BAR + Player.RED.ordinal
        assertEquals(redBar, Geometry.snap(variant, 6.6f, 3f, setOf(redBar, 17)))
    }

    @Test
    fun `each tray and bar half answers for its own side only`() {
        for (variant in Variant.entries) {
            val bottom = Geometry.place(variant, 9f, Geometry.HEIGHT - 0.2f)!!
            val top = Geometry.place(variant, 2f, 0.2f)!!
            assertEquals(Track.OFF, pos(variant, Player.RED, bottom))
            assertNull(pos(variant, Player.BLUE, bottom))
            assertEquals(Track.OFF, pos(variant, Player.BLUE, top))
        }
        // Short game: Red's bar checkers wait in the top half, beside where they come back in.
        val redBar = Geometry.place(Variant.SHORT, 6.5f, 3f)!!
        assertEquals(Track.BAR, pos(Variant.SHORT, Player.RED, redBar))
        assertTrue(Geometry.spot(Variant.SHORT, Seat(redBar, 0, 1)).y < Geometry.HEIGHT / 2)
    }

    @Test
    fun `after one step exactly one checker has a new seat`() {
        val start = Rules.start(Variant.SHORT)
        val seats = Geometry.seats(null, start)
        assertEquals(30, seats.size)

        var s = Rules.roll(Rules.newGame(Variant.SHORT, vsBot = false).copy(opening = false), 6, 5)
        s = Rules.apply(s, s.legal.first { it.from == 0 && it.to == 6 })
        val next = Geometry.seats(seats, s.board)
        val moved = next.filter { (checker, seat) -> seats.getValue(checker).place != seat.place }
        assertEquals(1, moved.size)
        assertEquals(Track.point(Variant.SHORT, Player.RED, 6), moved.values.single().place)

        // Counts agree with the board everywhere.
        for (player in Player.entries) {
            for (place in 0 until Board.SIZE) {
                assertEquals(s.board.at(player, place), next.count { it.key.player == player && it.value.place == place })
            }
        }
    }
}
