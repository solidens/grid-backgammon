package com.gridbackgammon.game

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {

    /** A position written the way the rules read it: each player's checkers by their own pos. */
    private class Setup(val variant: Variant) {
        val cells = IntArray(Board.SIZE)
        fun put(player: Player, pos: Int, n: Int = 1) = apply {
            cells[Track.point(variant, player, pos)] += n * player.sign
        }
        fun bar(player: Player, n: Int = 1) = apply { cells[Board.BAR + player.ordinal] += n }
        fun off(player: Player, n: Int) = apply { cells[Board.OFF + player.ordinal] += n }
        fun board() = Board(cells.clone())
    }

    private fun short() = Setup(Variant.SHORT)
    private fun long() = Setup(Variant.LONG)

    /** [player] to play [a]-[b] on [board], the opening already settled. */
    private fun thrown(variant: Variant, board: Board, a: Int, b: Int, player: Player = Player.RED, firstTurn: Boolean = false) =
        Rules.roll(
            Rules.newGame(variant, vsBot = false).copy(
                board = board,
                turn = player,
                opening = false,
                turns = Player.entries.associateWith { if (firstTurn) 0 else 5 },
            ),
            a,
            b,
        )

    private fun GameState.play(vararg steps: Pair<Int, Int>): GameState = steps.fold(this) { s, (from, to) ->
        Rules.apply(s, s.legal.first { it.from == from && it.to == to })
    }

    private fun GameState.moves() = legal.map { it.from to it.to }.toSet()

    @Test
    fun `both games start with fifteen each and the right pip counts`() {
        for (variant in Variant.entries) {
            val board = Rules.start(variant)
            for (player in Player.entries) {
                val total = (0 until Track.POINTS).sumOf { board.count(player, it) }
                assertEquals(Track.CHECKERS, total)
            }
        }
        val short = Rules.start(Variant.SHORT)
        assertEquals(167, short.pips(Variant.SHORT, Player.RED))
        assertEquals(167, short.pips(Variant.SHORT, Player.BLUE))
        // Red's six-point is bottom right, two from the corner... and Blue's 24-point is Red's one-point.
        assertEquals(5, short.count(Player.RED, 5))
        assertEquals(2, short.count(Player.BLUE, 0))

        val long = Rules.start(Variant.LONG)
        assertEquals(360, long.pips(Variant.LONG, Player.RED))
        assertEquals(15, long.count(Player.RED, 0))
        assertEquals(15, long.count(Player.BLUE, 12))
    }

    @Test
    fun `the opening throw decides who starts and, in the short game, is the first move`() {
        val short = Rules.newGame(Variant.SHORT, vsBot = true)
        val tie = Rules.opening(short, 4, 4)
        assertTrue(tie.opening)
        assertEquals(Note.TIE, tie.note)

        val blue = Rules.opening(short, 2, 5)
        assertFalse(blue.opening)
        assertEquals(Player.BLUE, blue.turn)
        assertEquals(Phase.MOVE, blue.phase)
        assertEquals(listOf(5, 2), blue.roll)

        val long = Rules.opening(Rules.newGame(Variant.LONG, vsBot = true), 6, 1)
        assertEquals(Player.RED, long.turn)
        assertEquals(Phase.ROLL, long.phase)
        assertTrue(long.roll.isEmpty())
    }

    @Test
    fun `a double is four moves`() {
        val s = thrown(Variant.SHORT, Rules.start(Variant.SHORT), 3, 3)
        assertEquals(listOf(3, 3, 3, 3), s.left)
        val after = s.play(16 to 19, 16 to 19, 11 to 14, 11 to 14)
        assertEquals(Phase.CONFIRM, after.phase)
        assertTrue(after.left.isEmpty())
    }

    @Test
    fun `a checker on the bar has to come in first, and can hit coming in`() {
        val board = short().bar(Player.RED).put(Player.RED, 10).put(Player.BLUE, 20).put(Player.BLUE, 18, 2).board()
        // Red enters on pos die-1. Blue's single checker at its pos 20 is Red's pos 3.
        val s = thrown(Variant.SHORT, board, 4, 6)
        assertEquals(setOf(Track.BAR to 3), s.moves().filter { it.first == Track.BAR }.toSet())
        assertTrue(s.legal.all { it.from == Track.BAR })
        assertEquals(Track.BAR, s.selected)

        // Blue's pos 18 is Red's pos 5: a made point, so the six does not come in.
        assertFalse(Track.BAR to 5 in s.moves())

        val hit = s.play(Track.BAR to 3)
        assertEquals(1, hit.board.bar(Player.BLUE))
        assertEquals(0, hit.board.bar(Player.RED))
    }

    @Test
    fun `a closed board means no move at all`() {
        val closed = short().bar(Player.RED).apply { for (pos in 18..23) put(Player.BLUE, pos, 2) }.put(Player.RED, 12).board()
        val s = thrown(Variant.SHORT, closed, 5, 3)
        assertTrue(s.legal.isEmpty())
        assertEquals(Note.NO_MOVE, s.note)
    }

    @Test
    fun `the whole throw is played if it can be`() {
        // 6-1. Red at pos 10 and 20; Blue holds Red's pos 16. The 1 from pos 20
        // would leave the 6 unplayable, so the only start is 10 -> 11 -> 17.
        val board = short().put(Player.RED, 10).put(Player.RED, 20).put(Player.BLUE, 23 - 16, 2).board()
        val s = thrown(Variant.SHORT, board, 6, 1)
        assertEquals(setOf(10 to 11), s.moves())
        assertEquals(setOf(11 to 17), s.play(10 to 11).moves())
    }

    @Test
    fun `if only one die can be played it has to be the larger`() {
        // One Red checker left on the board at pos 10; Blue blocks pos 13 and 14 and 15.
        // 5-3: +3 -> 13 blocked, +5 -> 15 blocked... use a position where each alone works but not both.
        val board = short()
            .put(Player.RED, 10)
            .put(Player.BLUE, 23 - 18, 2) // Red's 18 blocked: 10 + 3 + 5
            .board()
        val s = thrown(Variant.SHORT, board, 5, 3)
        // 10 -> 13 and 10 -> 15 are both fine alone; 18 is blocked, so only one die plays.
        assertEquals(setOf(10 to 15), s.moves())
    }

    @Test
    fun `bearing off takes the exact number, a larger one only from the back`() {
        val home = short().put(Player.RED, 19).put(Player.RED, 22).off(Player.RED, 13).put(Player.BLUE, 20, 15).board()
        // 19 needs a 5, 22 needs a 2. With 6-1: the 6 bears off 19 (the rearmost), the 1 moves one.
        val s = thrown(Variant.SHORT, home, 6, 1)
        assertTrue(19 to Track.OFF in s.moves())
        assertFalse(22 to Track.OFF in s.moves())

        // Not while a checker is still outside home.
        val outside = short().put(Player.RED, 17).put(Player.RED, 22).off(Player.RED, 13).put(Player.BLUE, 20, 15).board()
        assertFalse(22 to Track.OFF in thrown(Variant.SHORT, outside, 2, 1).moves())
    }

    @Test
    fun `the last checker off wins, gammon and backgammon count`() {
        val single = thrown(Variant.SHORT, short().put(Player.RED, 23).off(Player.RED, 14).put(Player.BLUE, 20).off(Player.BLUE, 14).board(), 1, 2)
        val won = single.play(23 to Track.OFF)
        assertEquals(Phase.OVER, won.phase)
        assertEquals(Outcome(Player.RED, Win.SINGLE), won.outcome)

        val gammon = thrown(Variant.SHORT, short().put(Player.RED, 23).off(Player.RED, 14).put(Player.BLUE, 20, 15).board(), 1, 2)
        assertEquals(Win.GAMMON, gammon.play(23 to Track.OFF).outcome?.win)

        // A Blue checker still in Red's home board: Blue pos 0..5.
        val bg = thrown(Variant.SHORT, short().put(Player.RED, 23).off(Player.RED, 14).put(Player.BLUE, 2).put(Player.BLUE, 20, 14).board(), 1, 2)
        assertEquals(Win.BACKGAMMON, bg.play(23 to Track.OFF).outcome?.win)
    }

    @Test
    fun `undo puts back the board and the die`() {
        val s = thrown(Variant.SHORT, Rules.start(Variant.SHORT), 6, 5)
        val moved = s.play(0 to 6)
        val back = Rules.undo(moved)
        assertEquals(s.board, back.board)
        assertEquals(s.left.sorted(), back.left.sorted())
        assertTrue(back.history.isEmpty())
    }

    @Test
    fun `one checker can take both dice, touching down on the way`() {
        val s = thrown(Variant.SHORT, Rules.start(Variant.SHORT), 6, 5)
        // The 5 alone would land on Blue's six-point; the 6 then the 5 gets past it.
        val paths = Rules.paths(s, 0)
        assertEquals(setOf(6, 11), paths.keys)
        assertEquals(2, paths.getValue(11).size)

        // Blocked halfway both ways: 0 -> 5 and 0 -> 6 are both Blue's points.
        val blocked = short().put(Player.RED, 0).put(Player.BLUE, 23 - 5, 2).put(Player.BLUE, 23 - 6, 2).put(Player.RED, 12).board()
        val b = thrown(Variant.SHORT, blocked, 6, 5)
        assertFalse(11 in Rules.paths(b, 0))
    }

    @Test
    fun `long game - one checker off the head per turn`() {
        val s = thrown(Variant.LONG, Rules.start(Variant.LONG), 5, 2)
        val one = s.play(0 to 5)
        assertFalse(one.legal.any { it.from == Track.HEAD })
        assertEquals(setOf(5 to 7), one.moves())
    }

    @Test
    fun `long game - a first 6-6 takes two off the head and stops at their head`() {
        val s = thrown(Variant.LONG, Rules.start(Variant.LONG), 6, 6, firstTurn = true)
        val after = s.play(0 to 6, 0 to 6)
        assertEquals(Phase.CONFIRM, after.phase)
        assertEquals(2, after.board.count(Player.RED, 6))

        // Not on a later turn.
        val later = thrown(Variant.LONG, Rules.start(Variant.LONG), 6, 6)
        assertEquals(Phase.CONFIRM, later.play(0 to 6).phase)
    }

    @Test
    fun `long game - a first 4-4 plays all four with two off the head`() {
        val s = thrown(Variant.LONG, Rules.start(Variant.LONG), 4, 4, firstTurn = true)
        val after = s.play(0 to 4, 4 to 8, 0 to 4, 4 to 8)
        assertEquals(Phase.CONFIRM, after.phase)
        assertEquals(2, after.board.count(Player.RED, 8))
    }

    @Test
    fun `long game - one checker holds a point and nothing is hit`() {
        val board = long().put(Player.RED, 0, 14).put(Player.RED, 3).put(Player.BLUE, 0, 14).put(Player.BLUE, 20).board()
        // Blue's pos 20 is Red's pos 8.
        val s = thrown(Variant.LONG, board, 5, 3)
        assertFalse(3 to 8 in s.moves())
        assertTrue(s.legal.none { it.hit })
    }

    @Test
    fun `long game - no wall of six with every one of theirs behind it`() {
        // Red holds pos 1..5; a checker onto pos 6 would make six in a row, pos 1..6.
        // All of Blue is on its head, Red's pos 12 -- behind? Blue's route runs from Red's 12
        // round to Red's 11, so Red's 1..6 is Blue's 13..18: ahead of Blue's head. Illegal.
        val walled = long().put(Player.RED, 0, 9).apply { for (pos in 1..5) put(Player.RED, pos) }.put(Player.BLUE, 0, 15).board()
        val s = thrown(Variant.LONG, walled, 6, 1)
        assertFalse(0 to 6 in s.moves())
        // Stepping along the wall instead of adding to it is fine.
        assertTrue(5 to 6 in s.moves())

        // Once one of Blue's is past it, the wall is fine.
        val passed = long().put(Player.RED, 0, 9).apply { for (pos in 1..5) put(Player.RED, pos) }
            .put(Player.BLUE, 0, 14).put(Player.BLUE, 20).board()
        val t = thrown(Variant.LONG, passed, 6, 1)
        assertTrue(0 to 6 in t.moves())
    }

    @Test
    fun `random games always finish, fifteen a side, never two colours on a point`() {
        val random = Random(7)
        for (variant in Variant.entries) {
            repeat(60) {
                var s = Rules.newGame(variant, vsBot = false)
                while (s.opening) s = Rules.opening(s, random.nextInt(1, 7), random.nextInt(1, 7))
                var turns = 0
                while (s.phase != Phase.OVER) {
                    if (s.phase == Phase.ROLL) s = Rules.roll(s, random.nextInt(1, 7), random.nextInt(1, 7))
                    while (s.legal.isNotEmpty()) s = Rules.apply(s, s.legal.random(random))
                    for (player in Player.entries) {
                        val total = (0 until Track.POINTS).sumOf { s.board.count(player, it) } +
                            s.board.bar(player) + s.board.off(player)
                        assertEquals(Track.CHECKERS, total)
                    }
                    s = Rules.endTurn(s)
                    assertTrue("$variant game ran away", ++turns < 2000)
                }
                assertTrue(s.outcome != null)
                if (variant == Variant.LONG) assertEquals(0, s.board.bar(Player.RED) + s.board.bar(Player.BLUE))
            }
        }
    }

    @Test
    fun `deselect keeps a checker on the bar picked up`() {
        val board = short().bar(Player.RED).put(Player.RED, 10).put(Player.BLUE, 20).board()
        val s = thrown(Variant.SHORT, board, 4, 6)
        assertEquals(Track.BAR, Rules.deselect(s).selected)
        val free = thrown(Variant.SHORT, Rules.start(Variant.SHORT), 4, 6)
        assertNull(Rules.deselect(Rules.select(free, 0)).selected)
    }
}
