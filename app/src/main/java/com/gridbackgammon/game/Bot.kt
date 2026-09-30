package com.gridbackgammon.game

/**
 * The opponent. It lists every distinct way to play its throw and scores the
 * position each one leaves.
 *
 * Then it looks one throw further for the best few: for each of the 21 throws
 * the other side could make, it assumes the reply that hurts it most, and
 * averages them. That is what notices a blot left within reach, or a wall about
 * to be walked round -- the static score only guesses at those. Over 200 games
 * against itself without it, the look ahead came out ahead in both games.
 */
object Bot {

    /** Candidates that get the look ahead; the rest are judged on their score alone. */
    private const val SHORTLIST = 6
    private const val WON = 10_000.0

    fun choose(state: GameState): List<Step> {
        val me = state.turn
        val lines = Rules.lines(state)
        if (lines.size == 1) return lines.single().steps

        val ranked = lines.sortedByDescending { score(state.variant, it.board, me) }
        val theirFirst = state.turns.getValue(me.other) == 0
        return ranked.take(SHORTLIST).maxBy { expect(state.variant, it.board, me, theirFirst) }.steps
    }

    /** What [board] is worth to [me] once the other side has thrown and answered as well as it can. */
    private fun expect(variant: Variant, board: Board, me: Player, theirFirst: Boolean): Double {
        if (board.off(me) == Track.CHECKERS) return WON
        var total = 0.0
        for (a in 1..6) {
            for (b in a..6) {
                val dice = if (a == b) List(4) { a } else listOf(a, b)
                val worst = Rules.lines(variant, board, me.other, dice, theirFirst)
                    .minOf { score(variant, it.board, me) }
                total += worst * if (a == b) 1 else 2
            }
        }
        return total / 36
    }

    /** How good [board] is for [me]. Symmetric: the other side's score is the negative of it. */
    fun score(variant: Variant, board: Board, me: Player): Double = when {
        board.off(me) == Track.CHECKERS -> WON
        board.off(me.other) == Track.CHECKERS -> -WON
        variant == Variant.SHORT -> ShortGame.side(board, me) - ShortGame.side(board, me.other)
        else -> LongGame.side(board, me) - LongGame.side(board, me.other)
    }

    private object ShortGame {
        /** Of the 36 throws, how many can hit a blot this many pips away, blocks aside. */
        private val Shots = intArrayOf(0, 11, 12, 14, 15, 15, 17, 6, 6, 5, 3, 2, 3, 0, 0, 1, 1, 0, 1, 0, 1, 0, 0, 0, 1)

        /** A made point's worth, by pos: anchors in their home, the midpoint, the bar point, the home board. */
        private val Made = doubleArrayOf(
            1.0, 1.5, 2.0, 3.5, 5.0, 3.5,
            1.0, 1.0, 1.0, 1.0, 1.0, 2.0,
            1.5, 1.5, 2.0, 2.5, 3.5, 5.0,
            5.0, 6.0, 5.0, 3.5, 2.5, 1.5,
        )

        /** Consecutive made points, by length. Six is a full prime. */
        private val Prime = doubleArrayOf(0.0, 0.0, 1.0, 4.0, 8.0, 13.0, 20.0)

        fun side(board: Board, me: Player): Double {
            val them = me.other
            val mine = IntArray(Track.POINTS) { board.count(me, Track.point(Variant.SHORT, me, it)) }
            val theirs = IntArray(Track.POINTS) { board.count(them, Track.point(Variant.SHORT, them, it)) }
            var v = -board.pips(Variant.SHORT, me).toDouble()

            // Still to pass each other? A checker at my pos s stands at their pos 23 - s.
            val myRear = if (board.bar(me) > 0) -1 else mine.indexOfFirst { it > 0 }
            val theirRear = if (board.bar(them) > 0) -1 else theirs.indexOfFirst { it > 0 }
            val contact = mine.any { it > 0 } && theirs.any { it > 0 } && myRear + theirRear < Track.POINTS - 1
            if (!contact && board.bar(me) == 0 && board.bar(them) == 0) return v + 2.0 * board.off(me)

            val homeMade = (Track.HOME until Track.POINTS).count { mine[it] >= 2 }
            val theirHome = (Track.HOME until Track.POINTS).count { theirs[it] >= 2 }

            for (pos in 0 until Track.POINTS) {
                val n = mine[pos]
                if (n >= 2) v += Made[pos]
                if (n > 3) v -= 0.6 * (n - 3)
                if (n == 1) v -= exposure(pos, theirs, board.bar(them)) * (pos + 5 + 2 * theirHome)
            }

            var run = 0
            for (pos in 12..Track.POINTS) {
                if (pos < Track.POINTS && mine[pos] >= 2) {
                    run++
                } else {
                    v += Prime[run.coerceAtMost(6)]
                    run = 0
                }
            }

            // Their checkers on the bar hurt them more the more of the board is closed.
            v += board.bar(them) * 2.5 * homeMade
            return v
        }

        /** Rough chance, 0..1, that a blot at my [pos] is hit on the next throw. */
        private fun exposure(pos: Int, theirs: IntArray, theirBar: Int): Double {
            val at = Track.POINTS - 1 - pos
            var shots = if (theirBar > 0) Shots[at + 1] else 0
            for (q in 0 until at) if (theirs[q] > 0) shots += Shots[at - q]
            return shots.coerceAtMost(30) / 36.0
        }
    }

    private object LongGame {
        fun side(board: Board, me: Player): Double {
            val them = me.other
            var v = -board.pips(Variant.LONG, me).toDouble()

            // Walk their route. A run of my points is worth more the longer it is
            // and the more of their checkers still have to get past it.
            var behind = 0
            var run = 0
            var runBehind = 0
            var held = 0
            for (q in 0 until Track.POINTS) {
                val point = Track.point(Variant.LONG, them, q)
                if (board.count(me, point) > 0) {
                    if (run == 0) runBehind = behind
                    run++
                    held++
                } else {
                    v += wall(run, runBehind)
                    run = 0
                    behind += board.count(them, point)
                }
            }
            v += wall(run, runBehind)

            // Spread out is flexible; a tall stack is a checker that cannot move.
            v += 0.8 * held
            for (pos in 1 until Track.POINTS) {
                val n = board.count(me, Track.point(Variant.LONG, me, pos))
                if (n > 4) v -= 0.4 * (n - 4)
            }
            return v
        }

        private fun wall(run: Int, behind: Int): Double = run * (run + 1) / 2.0 * 1.2 * behind / Track.CHECKERS
    }
}
