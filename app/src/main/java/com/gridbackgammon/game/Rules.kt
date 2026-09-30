package com.gridbackgammon.game

/**
 * The rules, as pure functions over [GameState]. No clocks, no randomness: the
 * view model supplies the dice and the pauses, which leaves this testable.
 *
 * Both games share the board, the dice and the duty to play as much of a throw
 * as possible -- all of it if it can be, otherwise the larger die. They differ in:
 *
 * Short (backgammon). The two sides run opposite ways. Two or more checkers make
 * a point the other side cannot land on; a single one is a blot, and landing on
 * it sends it to the bar, from where it must re-enter before anything else moves.
 *
 * Long (narde). Both sides run the same way, each from its own head, a stack of
 * all fifteen. One checker on a point already owns it, and nothing is hit. Only
 * one checker leaves the head per turn -- two on a first throw of 6-6, 4-4 or
 * 3-3, which cannot otherwise be played. A wall of six points is not allowed
 * unless at least one of the other side's checkers is already past it.
 *
 * No doubling cube, no match: a game is worth 1, 2 or 3 and the view model keeps the tally.
 */
object Rules {

    fun start(variant: Variant): Board {
        val cells = IntArray(Board.SIZE)
        val layout = when (variant) {
            // The 24-, 13-, 8- and 6-points, counted the mover's way.
            Variant.SHORT -> listOf(0 to 2, 11 to 5, 16 to 3, 18 to 5)
            Variant.LONG -> listOf(Track.HEAD to Track.CHECKERS)
        }
        for (player in Player.entries) {
            for ((pos, n) in layout) cells[Track.point(variant, player, pos)] = n * player.sign
        }
        return Board(cells)
    }

    fun newGame(variant: Variant, vsBot: Boolean): GameState = GameState(
        variant = variant,
        bots = if (vsBot) setOf(Player.BLUE) else emptySet(),
        board = start(variant),
        turn = Player.RED,
        faces = Player.entries.associateWith { listOf(1) },
    )

    /**
     * Each side has thrown one die. A tie throws again. Otherwise the higher
     * goes first -- in the short game with those same two dice as its throw, in
     * the long game with a throw of its own.
     */
    fun opening(state: GameState, red: Int, blue: Int): GameState {
        val faces = mapOf(Player.RED to listOf(red), Player.BLUE to listOf(blue))
        if (red == blue) return state.copy(faces = faces, phase = Phase.ROLL, note = Note.TIE)

        val first = if (red > blue) Player.RED else Player.BLUE
        val started = state.copy(turn = first, faces = faces, opening = false, phase = Phase.ROLL, note = Note.NONE)
        return when (state.variant) {
            Variant.SHORT -> if (first == Player.RED) roll(started, red, blue) else roll(started, blue, red)
            Variant.LONG -> started
        }
    }

    /** The dice have landed. An empty [GameState.legal] means the throw cannot be played at all. */
    fun roll(state: GameState, a: Int, b: Int): GameState {
        val dice = if (a == b) List(4) { a } else listOf(a, b)
        val thrown = state.copy(
            faces = state.faces + (state.turn to listOf(a, b)),
            roll = dice,
            left = dice,
            history = emptyList(),
            headMoves = 0,
            note = Note.NONE,
        )
        val ready = ready(thrown)
        return if (ready.legal.isEmpty()) ready.copy(note = Note.NO_MOVE) else ready
    }

    /** One step of the throw. The turn stays open -- it can still be undone -- until [endTurn]. */
    fun apply(state: GameState, step: Step): GameState {
        val next = advance(state, step)
        val board = next.board
        if (board.off(state.turn) == Track.CHECKERS) {
            return next.copy(
                phase = Phase.OVER,
                legal = emptyList(),
                outcome = Outcome(state.turn, win(state.variant, board, state.turn)),
            )
        }
        return ready(next)
    }

    fun undo(state: GameState): GameState {
        val last = state.history.lastOrNull() ?: return state
        return ready(
            state.copy(
                board = last.board,
                left = last.left,
                headMoves = last.headMoves,
                history = state.history.dropLast(1),
                note = Note.NONE,
            ),
        )
    }

    fun endTurn(state: GameState): GameState {
        if (state.phase == Phase.OVER) return state
        return state.copy(
            turn = state.turn.other,
            phase = Phase.ROLL,
            roll = emptyList(),
            left = emptyList(),
            legal = emptyList(),
            history = emptyList(),
            headMoves = 0,
            turns = state.turns + (state.turn to state.turns.getValue(state.turn) + 1),
            selected = null,
            paths = emptyMap(),
            note = Note.NONE,
        )
    }

    /** A checker is picked up. [GameState.paths] says where it can be put down. */
    fun select(state: GameState, from: Int): GameState = state.copy(selected = from, paths = paths(state, from))

    /** A checker on the bar cannot be put back: it is the only one allowed to move. */
    fun deselect(state: GameState): GameState =
        if (state.board.bar(state.turn) > 0) state else state.copy(selected = null, paths = emptyMap())

    /**
     * Every place one checker can reach from [from] this turn, using one die or
     * several in a row, with the way to get there.
     *
     * Each intermediate landing has to be legal in its own right, which is what
     * the rules ask of a checker that moves twice. Where the dice can be taken in
     * either order, a way that hits on the way is preferred, then the larger die
     * first; to choose otherwise, move one die at a time.
     */
    fun paths(state: GameState, from: Int): Map<Int, List<Step>> {
        val best = HashMap<Int, List<Step>>()
        fun walk(s: GameState, at: Int, path: List<Step>) {
            for (step in s.legal) {
                if (step.from != at) continue
                val next = path + step
                val known = best[step.to]
                if (known == null || prefer(next, known)) best[step.to] = next
                if (step.to != Track.OFF) walk(advance(s, step), step.to, next)
            }
        }
        walk(state, from, emptyList())
        return best
    }

    private fun prefer(a: List<Step>, b: List<Step>): Boolean = when {
        a.size != b.size -> a.size < b.size
        a.count { it.hit } != b.count { it.hit } -> a.count { it.hit } > b.count { it.hit }
        // Bearing off, the smaller die is the one to spend; elsewhere lead with the larger.
        a.last().to == Track.OFF -> a.first().die < b.first().die
        else -> a.first().die > b.first().die
    }

    /** One way of playing a whole throw, and where it leaves the board. */
    class Line(val steps: List<Step>, val board: Board)

    /** Every distinct position [state]'s throw can end in, with one way of reaching each. */
    fun lines(state: GameState): List<Line> =
        Engine(state.variant, state.turn).lines(state.board.cells(), state.left, headLeft(state))

    /** Same, for a throw [player] has not made yet. The bot's look ahead. */
    internal fun lines(variant: Variant, board: Board, player: Player, dice: List<Int>, firstTurn: Boolean): List<Line> =
        Engine(variant, player).lines(board.cells(), dice, headLimit(variant, firstTurn, dice))

    fun win(variant: Variant, board: Board, winner: Player): Win {
        val loser = winner.other
        if (board.off(loser) > 0) return if (variant == Variant.SHORT) Win.SINGLE else Win.OIN
        if (variant == Variant.LONG) return Win.MARS
        val stranded = board.bar(loser) > 0 ||
            (Track.HOME until Track.POINTS).any { board.count(loser, Track.point(variant, winner, it)) > 0 }
        return if (stranded) Win.BACKGAMMON else Win.GAMMON
    }

    private fun headLimit(variant: Variant, firstTurn: Boolean, dice: List<Int>): Int = when {
        variant != Variant.LONG -> 0
        firstTurn && dice.size == 4 && dice[0] in HeadDoubles -> 2
        else -> 1
    }

    private val HeadDoubles = setOf(3, 4, 6)

    private fun headLeft(state: GameState): Int =
        headLimit(state.variant, state.turns.getValue(state.turn) == 0, state.roll) - state.headMoves

    /** A step taken, nothing else decided: no win check, no checker picked up. */
    private fun advance(state: GameState, step: Step): GameState {
        require(step in state.legal) { "$step is not legal here" }
        val cells = state.board.cells()
        Engine(state.variant, state.turn).play(cells, step)
        val moved = state.copy(
            board = Board(cells),
            left = state.left.minusOne(step.die),
            history = state.history + Snapshot(state.board, state.left, state.headMoves),
            headMoves = state.headMoves + if (state.variant == Variant.LONG && step.from == Track.HEAD) 1 else 0,
            note = Note.NONE,
            selected = null,
            paths = emptyMap(),
        )
        return moved.copy(legal = legalFor(moved)).let { it.copy(phase = phaseFor(it)) }
    }

    /** Legal steps and phase worked out again, and a checker on the bar picked up for the player. */
    private fun ready(state: GameState): GameState {
        val legal = legalFor(state)
        val s = state.copy(legal = legal, selected = null, paths = emptyMap())
            .let { it.copy(phase = phaseFor(it)) }
        return if (legal.isNotEmpty() && s.board.bar(s.turn) > 0) select(s, Track.BAR) else s
    }

    private fun legalFor(state: GameState): List<Step> =
        if (state.left.isEmpty()) {
            emptyList()
        } else {
            Engine(state.variant, state.turn).legal(state.board.cells(), state.left, headLeft(state))
        }

    private fun phaseFor(state: GameState): Phase =
        if (state.legal.isEmpty() && state.history.isNotEmpty()) Phase.CONFIRM else Phase.MOVE
}

internal fun List<Int>.minusOne(value: Int): List<Int> {
    val i = indexOf(value)
    return if (i < 0) this else take(i) + drop(i + 1)
}

/**
 * The move generator, over raw arrays in [Board]'s layout. One instance per
 * question: it memoises how many dice each position can still play, which is
 * what makes "play as much of the throw as you can" affordable to enforce.
 *
 * Arrays handed to it are never written after: every step is played on a copy.
 */
internal class Engine(private val variant: Variant, private val player: Player) {

    private val sign = player.sign
    private val bar = Board.BAR + player.ordinal
    private val off = Board.OFF + player.ordinal
    private val opponent = player.other
    private val theirBar = Board.BAR + opponent.ordinal
    private val theirOff = Board.OFF + opponent.ordinal
    private val points = IntArray(Track.POINTS) { Track.point(variant, player, it) }
    private val theirPoints = IntArray(Track.POINTS) { Track.point(variant, opponent, it) }

    private val memo = HashMap<Key, Int>()

    private class Key(val cells: IntArray, val dice: Int, val head: Int) {
        override fun equals(other: Any?): Boolean =
            other is Key && dice == other.dice && head == other.head && cells.contentEquals(other.cells)

        override fun hashCode(): Int = (cells.contentHashCode() * 31 + dice) * 31 + head
    }

    private fun pack(dice: List<Int>): Int = dice.sorted().fold(0) { acc, d -> acc * 7 + d }

    private fun headCost(step: Step): Int = if (variant == Variant.LONG && step.from == Track.HEAD) 1 else 0

    /** Single steps with [die], judged on their own. */
    fun steps(cells: IntArray, die: Int, headLeft: Int, into: MutableList<Step>) {
        if (cells[bar] > 0) {
            val to = die - 1
            val there = cells[points[to]] * sign
            if (there >= -1) into += Step(Track.BAR, to, die, hit = there == -1)
            return
        }

        var rear = -1
        for (pos in 0 until Track.POINTS) {
            if (cells[points[pos]] * sign > 0) {
                rear = pos
                break
            }
        }
        if (rear < 0) return
        val bearing = rear >= Track.HOME

        for (pos in rear until Track.POINTS) {
            if (cells[points[pos]] * sign <= 0) continue
            if (variant == Variant.LONG && pos == Track.HEAD && headLeft <= 0) continue
            val to = pos + die
            if (to < Track.POINTS) {
                // Own checkers count positive here, the other side's negative.
                val there = cells[points[to]] * sign
                when (variant) {
                    Variant.SHORT -> if (there >= -1) into += Step(pos, to, die, hit = there == -1)
                    Variant.LONG -> if (there > 0 || (there == 0 && !walls(cells, pos, to))) into += Step(pos, to, die)
                }
            } else if (bearing && (to == Track.OFF || pos == rear)) {
                // A larger die than needed only bears off the rearmost checker.
                into += Step(pos, Track.OFF, die)
            }
        }
    }

    fun play(cells: IntArray, step: Step) {
        if (step.from == Track.BAR) cells[bar]-- else cells[points[step.from]] -= sign
        if (step.to == Track.OFF) {
            cells[off]++
        } else {
            val point = points[step.to]
            if (step.hit) {
                cells[point] = 0
                cells[theirBar]++
            }
            cells[point] += sign
        }
    }

    /**
     * Whether moving [from] -> [to] builds six points in a row with every one of
     * the other side's checkers still behind them. Only a newly taken point can
     * complete a wall, so this is asked only then.
     */
    private fun walls(cells: IntArray, from: Int, to: Int): Boolean {
        val next = cells.clone()
        next[points[from]] -= sign
        next[points[to]] += sign
        if (next[theirOff] > 0) return false

        // Walked along their route: "past the wall" is further along it.
        var front = -1
        for (q in Track.POINTS - 1 downTo 0) {
            if (next[theirPoints[q]] * opponent.sign > 0) {
                front = q
                break
            }
        }
        var run = 0
        for (q in 0..Track.POINTS) {
            if (q < Track.POINTS && next[theirPoints[q]] * sign > 0) {
                run++
            } else {
                if (run >= WALL && front < q) return true
                run = 0
            }
        }
        return false
    }

    /** The most dice that can still be played from here. */
    fun depth(cells: IntArray, left: List<Int>, headLeft: Int): Int {
        if (left.isEmpty()) return 0
        val key = Key(cells, pack(left), headLeft)
        memo[key]?.let { return it }

        var best = 0
        val buffer = ArrayList<Step>()
        search@ for (die in left.distinct()) {
            buffer.clear()
            steps(cells, die, headLeft, buffer)
            if (buffer.isEmpty()) continue
            val rest = left.minusOne(die)
            for (step in buffer) {
                val next = cells.clone()
                play(next, step)
                best = maxOf(best, 1 + depth(next, rest, headLeft - headCost(step)))
                if (best == left.size) break@search
            }
        }
        memo[key] = best
        return best
    }

    /** Steps that keep the whole of the most playable part of the throw within reach. */
    fun legal(cells: IntArray, left: List<Int>, headLeft: Int): List<Step> {
        val best = depth(cells, left, headLeft)
        if (best == 0) return emptyList()

        val result = ArrayList<Step>()
        val buffer = ArrayList<Step>()
        for (die in left.distinct()) {
            buffer.clear()
            steps(cells, die, headLeft, buffer)
            val rest = left.minusOne(die)
            for (step in buffer) {
                val next = cells.clone()
                play(next, step)
                if (1 + depth(next, rest, headLeft - headCost(step)) == best) result += step
            }
        }
        // Either die could be played, but not both: then it has to be the larger.
        if (best == 1 && left.size == 2 && left[0] != left[1]) {
            val high = maxOf(left[0], left[1])
            if (result.any { it.die == high }) return result.filter { it.die == high }
        }
        return result
    }

    fun lines(cells: IntArray, left: List<Int>, headLeft: Int): List<Rules.Line> {
        val ends = LinkedHashMap<Board, List<Step>>()
        val seen = HashSet<Key>()

        fun walk(c: IntArray, rest: List<Int>, head: Int, path: List<Step>) {
            // Two orders of the same steps meet here; what follows is the same for both.
            if (!seen.add(Key(c, pack(rest), head))) return
            val options = legal(c, rest, head)
            if (options.isEmpty()) {
                ends.getOrPut(Board(c)) { path }
                return
            }
            for (step in options) {
                val next = c.clone()
                play(next, step)
                walk(next, rest.minusOne(step.die), head - headCost(step), path + step)
            }
        }

        walk(cells, left, headLeft, emptyList())
        return ends.map { (board, steps) -> Rules.Line(steps, board) }
    }

    private companion object {
        const val WALL = 6
    }
}
