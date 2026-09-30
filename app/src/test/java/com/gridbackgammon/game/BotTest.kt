package com.gridbackgammon.game

import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Test

class BotTest {

    /** Plays one game out, Blue choosing with [blue] and Red with [red]; returns the winner. */
    private fun game(
        variant: Variant,
        random: Random,
        red: (GameState) -> List<Step>,
        blue: (GameState) -> List<Step>,
        slowest: LongArray,
    ): Player {
        var s = Rules.newGame(variant, vsBot = true)
        while (s.opening) s = Rules.opening(s, random.nextInt(1, 7), random.nextInt(1, 7))
        while (s.phase != Phase.OVER) {
            if (s.phase == Phase.ROLL) s = Rules.roll(s, random.nextInt(1, 7), random.nextInt(1, 7))
            if (s.legal.isNotEmpty()) {
                val started = System.nanoTime()
                val plan = if (s.turn == Player.RED) red(s) else blue(s)
                val ms = (System.nanoTime() - started) / 1_000_000
                if (s.turn == Player.BLUE) slowest[0] = maxOf(slowest[0], ms)
                for (step in plan) s = Rules.apply(s, step)
            }
            s = Rules.endTurn(s)
        }
        return s.outcome!!.winner
    }

    private fun randomly(random: Random): (GameState) -> List<Step> = { state ->
        Rules.lines(state).random(random).steps
    }

    @Test
    fun `the bot beats a random mover almost every time, in both games`() {
        for (variant in Variant.entries) {
            val random = Random(11)
            val slowest = LongArray(1)
            val games = 40
            val won = (1..games).count { game(variant, random, randomly(random), Bot::choose, slowest) == Player.BLUE }
            println("$variant: bot won $won of $games, slowest decision ${slowest[0]} ms")
            assertTrue("$variant: $won of $games", won >= games * 0.9)
        }
    }

    @Test
    fun `a single way to play is taken without thinking`() {
        val board = Rules.start(Variant.SHORT)
        val s = Rules.roll(
            Rules.newGame(Variant.SHORT, vsBot = true).copy(board = board, opening = false),
            6,
            5,
        )
        val plan = Bot.choose(s)
        var after = s
        for (step in plan) after = Rules.apply(after, step)
        assertTrue(after.legal.isEmpty())
    }
}
