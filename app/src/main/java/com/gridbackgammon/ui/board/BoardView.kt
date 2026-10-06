package com.gridbackgammon.ui.board

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.gridbackgammon.game.Board
import com.gridbackgammon.game.GameState
import com.gridbackgammon.game.Note
import com.gridbackgammon.game.Phase
import com.gridbackgammon.game.Player
import com.gridbackgammon.game.Timing
import com.gridbackgammon.game.Track
import com.gridbackgammon.game.Variant
import com.gridbackgammon.ui.theme.Grid
import com.gridbackgammon.ui.theme.GridTokens
import com.gridbackgammon.ui.theme.Motion
import com.gridbackgammon.ui.theme.color
import com.gridbackgammon.ui.theme.rememberBreath
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The place in [Board]'s layout that the mover's [pos] stands for. */
fun place(variant: Variant, player: Player, pos: Int): Int = when (pos) {
    Track.BAR -> Board.BAR + player.ordinal
    Track.OFF -> Board.OFF + player.ordinal
    else -> Track.point(variant, player, pos)
}

/** The inverse of [place]; null for the other side's bar or tray. */
fun pos(variant: Variant, player: Player, place: Int): Int? = when (place) {
    in 0 until Track.POINTS -> Track.pos(variant, player, place)
    Board.BAR + player.ordinal -> Track.BAR
    Board.OFF + player.ordinal -> Track.OFF
    else -> null
}

/**
 * Everything the board needs to paint one frame, and nothing else. It is a
 * value type on purpose: dice running through their faces change the game
 * state ten times in half a second, none of it is in here, and so the board
 * is skipped rather than redrawn for each face.
 */
@Immutable
data class BoardRender(
    val variant: Variant,
    val board: Board,
    val turn: Player,
    /** Places a human may pick a checker up from, in [Board]'s layout. */
    val sources: Set<Int> = emptySet(),
    val selected: Int? = null,
    /** Where the picked-up checker can be put down. */
    val targets: Set<Int> = emptySet(),
    /** Whether a tap on the board throws the dice. */
    val rollable: Boolean = false,
    /** Upside down, so Blue's checkers are the near ones: for the phone that plays Blue online. */
    val flipped: Boolean = false,
) {
    constructor(state: GameState) : this(
        variant = state.variant,
        board = state.board,
        turn = state.turn,
        sources = if (state.live) state.sources.mapTo(HashSet()) { place(state.variant, state.turn, it) } else emptySet(),
        selected = if (state.live) state.selected?.let { place(state.variant, state.turn, it) } else null,
        targets = if (state.live) state.paths.keys.mapTo(HashSet()) { place(state.variant, state.turn, it) } else emptySet(),
        rollable = state.phase == Phase.ROLL && if (state.opening) state.note != Note.TIE else state.humanTurn,
        flipped = state.bottom == Player.BLUE,
    )
}

private val GameState.live: Boolean get() = phase == Phase.MOVE && humanTurn

/**
 * Where one checker is drawn right now, as opposed to where the rules say it
 * is. The rules jump; this catches up. A checker that changes place is lifted,
 * carried in one eased arc and put down; one that only shifts within its stack
 * settles on a spring.
 */
private class CheckerMotion(start: Spot, var place: Int) {
    private val rest = Animatable(Offset(start.x, start.y), Offset.VectorConverter)
    private val size = Animatable(start.scale)

    /** Where a flight started, while one is under way. */
    private var origin by mutableStateOf<Spot?>(null)
    private val flight = Animatable(1f)

    /** 0..1, how far the checker stands up on its shadow as one the player may pick up. */
    val offer = Animatable(0f)

    /** 0..1, how far it has turned yellow as the one picked up. */
    val picked = Animatable(0f)

    val flying: Boolean get() = origin != null

    /** 0 on the board, 1 at the top of its arc. */
    val lift: Float get() = if (origin != null) sin(PI * flight.value).toFloat() else 0f

    fun spot(): Spot {
        val at = Spot(rest.value.x, rest.value.y, size.value)
        val from = origin ?: return at
        val t = Motion.EaseInOut.transform(flight.value)
        return Spot(from.x + (at.x - from.x) * t, from.y + (at.y - from.y) * t, from.scale + (at.scale - from.scale) * t)
    }

    suspend fun moveTo(place: Int, target: Spot, wait: Long, ms: Int) {
        if (place == this.place && origin == null) {
            coroutineScope {
                launch { rest.animateTo(Offset(target.x, target.y), Motion.settle()) }
                launch { size.animateTo(target.scale, Motion.settle()) }
            }
            return
        }
        this.place = place
        if (wait > 0) delay(wait)
        // From wherever it is drawn now, which mid-flight is somewhere in the air.
        origin = spot()
        flight.snapTo(0f)
        rest.snapTo(Offset(target.x, target.y))
        size.snapTo(target.scale)
        // Steady on purpose: spot() eases the path, and lift stays a true arc.
        flight.animateTo(1f, tween(ms, easing = LinearEasing))
        origin = null
    }
}

@Composable
fun BackgammonBoard(
    render: BoardRender,
    onTap: (Int?) -> Unit,
    onRoll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val hair = with(density) { 1.dp.toPx() }
    val rule = with(density) { 2.5.dp.toPx() }
    val frame = GridTokens.BorderThick
    val measurer = rememberTextMeasurer()

    // Gesture lambdas outlive a single composition, so they have to read the
    // current game rather than the one captured when the handler was set up.
    val current by rememberUpdatedState(render)
    val tap by rememberUpdatedState(onTap)
    val roll by rememberUpdatedState(onRoll)

    // Seats are worked out from the last seats, so a checker keeps its name.
    // More than a few changing at once is a new game or an undo of a hit,
    // and those travel a little slower.
    val last = remember { arrayOfNulls<Map<Checker, Seat>>(1) }
    val (seats, bulk) = remember(render.board) {
        val before = last[0]
        val next = Geometry.seats(before, render.board)
        last[0] = next
        next to (before?.count { (checker, seat) -> next.getValue(checker).place != seat.place } ?: 0)
    }

    val checkers = remember { Player.entries.flatMap { player -> List(Track.CHECKERS) { Checker(player, it) } } }
    val motions = HashMap<Checker, CheckerMotion>(checkers.size)
    for (checker in checkers) {
        key(checker) {
            val seat = seats.getValue(checker)
            val spot = Geometry.spot(render.variant, seat)
            val motion = remember { CheckerMotion(spot, seat.place) }
            val onTop = checker.player == render.turn && seat.index == seat.count - 1
            val offered = onTop && seat.place in render.sources
            val picked = onTop && seat.place == render.selected
            LaunchedEffect(seat, spot) {
                // A hit blot waits for the hitter to be most of the way there before it goes.
                val hit = seat.place >= Board.BAR && seat.place < Board.OFF && motion.place != seat.place
                motion.moveTo(
                    place = seat.place,
                    target = spot,
                    wait = if (hit) Timing.HIT_LAG_MS.toLong() else 0L,
                    ms = if (bulk > BULK) Timing.RETURN_MS else Timing.STEP_MS,
                )
            }
            LaunchedEffect(offered) { motion.offer.animateTo(if (offered) 1f else 0f, Motion.press()) }
            LaunchedEffect(picked) { motion.picked.animateTo(if (picked) 1f else 0f, Motion.fade()) }
            motions[checker] = motion
        }
    }

    val glow = remember { List(Board.SIZE) { Animatable(0f) } }
    LaunchedEffect(render.targets) {
        glow.forEachIndexed { place, a ->
            launch { a.animateTo(if (place in render.targets) 1f else 0f, Motion.fade()) }
        }
    }

    val pulse = rememberBreath(render.sources.isNotEmpty())

    Box(
        modifier
            .background(Grid.Ink)
            .padding(frame),
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .aspectRatio(Geometry.COLUMNS / Geometry.HEIGHT)
                .pointerInput(Unit) {
                    detectTapGestures { at ->
                        val s = current
                        if (s.rollable) {
                            roll()
                            return@detectTapGestures
                        }
                        if (s.sources.isEmpty()) return@detectTapGestures
                        val unit = size.width / Geometry.COLUMNS.toFloat()
                        val y = if (s.flipped) Geometry.HEIGHT - at.y / unit else at.y / unit
                        val place = Geometry.snap(s.variant, at.x / unit, y, s.sources + s.targets)
                        tap(place?.let { pos(s.variant, s.turn, it) })
                    }
                },
        ) {
            val unit = size.width / Geometry.COLUMNS
            // Flipped, the board itself is mirrored top to bottom, but the
            // checkers are only moved: their shadows still fall down and right.
            fun y(at: Float) = (if (render.flipped) Geometry.HEIGHT - at else at) * unit
            withTransform({ if (render.flipped) scale(1f, -1f, Offset(size.width / 2f, size.height / 2f)) }) {
                drawBoard(render.variant, unit, glow, hair, rule)
            }

            val breath = pulse.value

            // Where the picked-up checker could land: a small yellow ghost of it.
            for (place in render.targets) {
                val t = glow[place].value
                if (t <= 0f) continue
                val at = Geometry.landing(render.variant, render.board, render.turn, place)
                drawChecker(
                    player = render.turn,
                    center = Offset(at.x * unit, y(at.y)),
                    unit = unit * at.scale * GHOST * (1f + PULSE_SWELL * breath),
                    fill = Grid.Yellow.copy(alpha = t),
                    outline = Grid.Ink.copy(alpha = t),
                )
            }

            // Resting checkers stack by stack, then whatever is in the air -- a
            // moving checker never passes underneath anything.
            val order = checkers.sortedWith(
                compareBy<Checker>(
                    { if (motions.getValue(it).flying) 1 else 0 },
                    { seats.getValue(it).place },
                    { seats.getValue(it).index },
                ),
            )
            for (checker in order) {
                val motion = motions.getValue(checker)
                val seat = seats.getValue(checker)
                val at = motion.spot()
                val lift = motion.lift
                val offer = motion.offer.value
                val scale = at.scale * (1f + LIFT_SWELL * lift) * (1f + PULSE_SWELL * breath * offer)
                val center = Offset(at.x * unit, y(at.y) - LIFT * lift * unit)
                val fill = lerp(checker.player.color, Grid.Yellow, motion.picked.value)
                drawChecker(
                    player = checker.player,
                    center = center,
                    unit = unit * scale,
                    fill = fill,
                    shadow = unit * (OFFER_SHADOW * offer + LIFT_SHADOW * lift),
                )
                // A tall stack says how tall on its top checker.
                if (!motion.flying && seat.count > LABELLED && seat.index == seat.count - 1 && seat.place < Board.OFF) {
                    drawCount(measurer, seat.count, center, unit * scale, if (motion.picked.value > 0.5f) Grid.Ink else Grid.Paper)
                }
            }
        }
    }
}

/** Stacks taller than this carry their count. */
private const val LABELLED = 5

/** More checkers than this changing place at once is a reset, not a move. */
private const val BULK = 3

private const val GHOST = 0.52f
private const val LIFT = 0.28f
private const val LIFT_SWELL = 0.14f
private const val LIFT_SHADOW = 0.16f
private const val PULSE_SWELL = 0.12f
private const val OFFER_SHADOW = 0.11f

private fun DrawScope.drawCount(measurer: TextMeasurer, count: Int, center: Offset, unit: Float, color: Color) {
    val text = measurer.measure(
        count.toString(),
        TextStyle(color = color, fontSize = (unit * 0.44f).toSp(), fontWeight = FontWeight.Black),
    )
    drawText(text, topLeft = center - Offset(text.size.width / 2f, text.size.height / 2f))
}

private fun DrawScope.drawBoard(variant: Variant, unit: Float, glow: List<Animatable<Float, AnimationVector1D>>, hair: Float, rule: Float) {
    val width = Geometry.COLUMNS * unit
    val height = Geometry.HEIGHT * unit
    val tray = Geometry.TRAY * unit

    drawRect(Grid.Paper)

    // Trays. The one a checker can be borne off into lights up like a point would.
    for (player in Player.entries) {
        val y = Geometry.trayY(player) * unit
        val top = y - tray / 2f
        drawRect(Grid.Yellow.copy(alpha = glow[Board.OFF + player.ordinal].value), Offset(0f, top), Size(width, tray))
        val slot = unit * TRAY_SLOT
        for (k in 0 until Track.CHECKERS) {
            val topLeft = Offset(Geometry.traySlot(k) * unit - slot / 2f, y - slot / 2f)
            drawRect(Grid.PaperDeep, topLeft, Size(slot, slot))
            drawRect(Grid.Ink, topLeft, Size(slot, slot), style = Stroke(hair))
        }
    }

    // Points: long triangles, ink and sand in turn along each row, and opposite across.
    val reach = Geometry.POINT * Geometry.TIP * unit
    for (point in 0 until Track.POINTS) {
        val column = Geometry.column(point)
        val top = Geometry.top(variant, point)
        val slot = if (column > Geometry.BAR_COLUMN) column - 1 else column
        val dark = (slot + if (top) 1 else 0) % 2 == 0
        val base = if (top) tray else height - tray
        val tip = if (top) base + reach else base - reach
        val path = Path().apply {
            moveTo(column * unit, base)
            lineTo((column + 1) * unit, base)
            lineTo((column + 0.5f) * unit, tip)
            close()
        }
        drawPath(path, if (dark) Grid.Ink else Grid.SquareDark)
        val lit = glow[point].value
        if (lit > 0f) drawPath(path, Grid.Yellow.copy(alpha = lit))
        drawPath(path, Grid.Ink, style = Stroke(hair))
    }

    // The bar, and the rules that close off the trays.
    val bar = Geometry.BAR_COLUMN * unit
    drawRect(Grid.PaperDeep, Offset(bar, tray), Size(unit, height - 2 * tray))
    drawLine(Grid.Ink, Offset(bar, tray), Offset(bar, height - tray), strokeWidth = rule)
    drawLine(Grid.Ink, Offset(bar + unit, tray), Offset(bar + unit, height - tray), strokeWidth = rule)
    drawLine(Grid.Ink, Offset(0f, tray), Offset(width, tray), strokeWidth = rule)
    drawLine(Grid.Ink, Offset(0f, height - tray), Offset(width, height - tray), strokeWidth = rule)
}

private const val TRAY_SLOT = 0.62f
