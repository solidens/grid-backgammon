package com.gridbackgammon.game

/** The two games played on the one board. */
enum class Variant(val title: String, val detail: String) {
    /** Backgammon: the two sides run opposite ways, blots are hit and re-enter from the bar. */
    SHORT("Short", "Backgammon"),

    /** Narde: both sides run the same way from a single stack, the head. Nothing is ever hit. */
    LONG("Long", "Narde"),
}

/** Red sits at the bottom of the screen and is the one who plays a bot. */
enum class Player(val title: String) {
    RED("Red"),
    BLUE("Blue");

    val other: Player get() = if (this == RED) BLUE else RED

    /** Red counts up on a point and Blue counts down, which is why the two can never share one. */
    val sign: Int get() = if (this == RED) 1 else -1
}

/**
 * A checker's journey is one number, its pos: 0 is where it sets out, 23 the
 * last point before it bears off. Both players count the same way, whichever
 * way round the board they actually run; [point] says where a pos is on it.
 *
 * Points are numbered 0..23 around the board. In the short game 0 is Red's
 * one-point, bottom right; the long game is the same board upside down.
 */
object Track {
    const val POINTS = 24
    const val CHECKERS = 15

    /** First pos of the home board, the last six points. */
    const val HOME = 18

    /** Pseudo-positions: on the bar, before pos 0, and borne off, after 23. */
    const val BAR = -1
    const val OFF = 24

    /** The long game's starting stack. */
    const val HEAD = 0

    fun point(variant: Variant, player: Player, pos: Int): Int = when (variant) {
        Variant.SHORT -> if (player == Player.RED) POINTS - 1 - pos else pos
        Variant.LONG -> if (player == Player.RED) pos else (pos + POINTS / 2) % POINTS
    }

    /** The inverse of [point]. */
    fun pos(variant: Variant, player: Player, point: Int): Int = when (variant) {
        Variant.SHORT -> if (player == Player.RED) POINTS - 1 - point else point
        Variant.LONG -> if (player == Player.RED) point else (point + POINTS / 2) % POINTS
    }
}

/**
 * Where every checker is. Immutable, and compared by content, so two ways of
 * reaching the same position are the same position.
 *
 * Laid out as 24 signed point counts, then each player's bar, then each
 * player's borne-off checkers -- one flat array the search can copy cheaply.
 */
class Board internal constructor(private val cells: IntArray) {

    /** Signed: Red's checkers count up, Blue's down. */
    operator fun get(point: Int): Int = cells[point]

    fun count(player: Player, point: Int): Int = (cells[point] * player.sign).coerceAtLeast(0)

    fun bar(player: Player): Int = cells[BAR + player.ordinal]

    fun off(player: Player): Int = cells[OFF + player.ordinal]

    /** How many of [player]'s checkers are at a place: a point, their bar, or their tray. */
    fun at(player: Player, place: Int): Int = when (place) {
        in 0 until Track.POINTS -> count(player, place)
        BAR + player.ordinal -> bar(player)
        OFF + player.ordinal -> off(player)
        else -> 0
    }

    /** What is left to travel: every checker's distance to the tray, the bar counting as a full lap. */
    fun pips(variant: Variant, player: Player): Int {
        var total = bar(player) * (Track.POINTS + 1)
        for (pos in 0 until Track.POINTS) {
            total += count(player, Track.point(variant, player, pos)) * (Track.POINTS - pos)
        }
        return total
    }

    internal fun cells(): IntArray = cells.clone()

    override fun equals(other: Any?): Boolean = other is Board && cells.contentEquals(other.cells)

    override fun hashCode(): Int = cells.contentHashCode()

    override fun toString(): String = cells.joinToString(",", "Board(", ")")

    companion object {
        /** Places past the 24 points: each player's bar, then each player's tray. */
        const val BAR = Track.POINTS
        const val OFF = Track.POINTS + 2
        const val SIZE = Track.POINTS + 4
    }
}

/** One checker moved by one die. [from] and [to] are the mover's pos, [Track.BAR] and [Track.OFF] included. */
data class Step(val from: Int, val to: Int, val die: Int, val hit: Boolean = false)

enum class Phase {
    /** Waiting for the player on turn to throw -- or, before the first turn, for anyone to. */
    ROLL,

    /** The dice are tumbling. */
    ROLLING,

    /** The dice have landed; [GameState.legal] says what may move. */
    MOVE,

    /** A checker is travelling. */
    MOVING,

    /** Nothing more can be played; the turn ends when its player says so, since until then it can be undone. */
    CONFIRM,

    OVER,
}

/** What just happened, for the one line of status the game screen has room for. */
enum class Note { NONE, TIE, NO_MOVE }

/** How a game was won, and what it is worth. */
enum class Win(val title: String, val points: Int) {
    SINGLE("Single game", 1),
    GAMMON("Gammon", 2),
    BACKGAMMON("Backgammon", 3),
    OIN("Oin", 1),
    MARS("Mars", 2),
}

data class Outcome(val winner: Player, val win: Win)

/** The turn as it was before one step, so the step can be taken back. */
data class Snapshot(val board: Board, val left: List<Int>, val headMoves: Int)

data class GameState(
    val variant: Variant,
    val bots: Set<Player>,
    val board: Board,
    val turn: Player,
    val phase: Phase = Phase.ROLL,
    /** Before the first turn both players throw one die, and the higher goes first. */
    val opening: Boolean = true,
    /** The dice each player's plate shows: one during the opening throw, two after. */
    val faces: Map<Player, List<Int>>,
    /** This turn's throw -- four entries for a double -- and what is still unplayed of it. */
    val roll: List<Int> = emptyList(),
    val left: List<Int> = emptyList(),
    /** Every step [turn] may take next, already narrowed by the rule that as much of the throw as possible is played. */
    val legal: List<Step> = emptyList(),
    val history: List<Snapshot> = emptyList(),
    /** Checkers taken off the head this turn. Long game only. */
    val headMoves: Int = 0,
    /** Turns each player has completed, which is how the long game knows a first throw. */
    val turns: Map<Player, Int> = Player.entries.associateWith { 0 },
    /** The pos a human has picked up a checker from, and the places it can go. */
    val selected: Int? = null,
    val paths: Map<Int, List<Step>> = emptyMap(),
    val note: Note = Note.NONE,
    val outcome: Outcome? = null,
    /** Online: the side the other phone plays. Null on one phone or against the bot. */
    val remote: Player? = null,
) {
    val vsBot: Boolean get() = bots.isNotEmpty()

    val online: Boolean get() = remote != null

    /** Whether the side on turn is played on this phone, by a person. */
    val humanTurn: Boolean get() = turn !in bots && turn != remote && phase != Phase.OVER

    /** The side at the bottom of the screen: Red, unless Red is the other phone's. */
    val bottom: Player get() = if (remote == Player.RED) Player.BLUE else Player.RED

    /** Positions [turn] can pick a checker up from. */
    val sources: Set<Int> get() = legal.mapTo(HashSet()) { it.from }

    fun pips(player: Player): Int = board.pips(variant, player)
}
