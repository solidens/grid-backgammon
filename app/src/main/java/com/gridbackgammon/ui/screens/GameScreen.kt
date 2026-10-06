package com.gridbackgammon.ui.screens

import android.content.Intent
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gridbackgammon.game.GameState
import com.gridbackgammon.game.GameViewModel
import com.gridbackgammon.game.Haptic
import com.gridbackgammon.game.Note
import com.gridbackgammon.game.OnlineInfo
import com.gridbackgammon.game.Phase
import com.gridbackgammon.game.Player
import com.gridbackgammon.game.Win
import com.gridbackgammon.online.Link
import com.gridbackgammon.online.Status
import com.gridbackgammon.ui.board.BoardRender
import com.gridbackgammon.ui.board.DIE
import com.gridbackgammon.ui.board.Die
import com.gridbackgammon.ui.board.Geometry
import com.gridbackgammon.ui.board.BackgammonBoard
import com.gridbackgammon.ui.board.PlayerGlyph
import com.gridbackgammon.ui.theme.BrutalButton
import com.gridbackgammon.ui.theme.BrutalCard
import com.gridbackgammon.ui.theme.BrutalRule
import com.gridbackgammon.ui.theme.BrutalSlab
import com.gridbackgammon.ui.theme.Caption
import com.gridbackgammon.ui.theme.Grid
import com.gridbackgammon.ui.theme.GridTokens
import com.gridbackgammon.ui.theme.Motion
import com.gridbackgammon.ui.theme.color
import com.gridbackgammon.ui.theme.onAccent
import com.gridbackgammon.ui.theme.rememberBreath

@Composable
fun GameScreen(viewModel: GameViewModel, onExit: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val score by viewModel.score.collectAsStateWithLifecycle()
    val online by viewModel.online.collectAsStateWithLifecycle()
    var leaving by remember { mutableStateOf(false) }
    val over = state.phase == Phase.OVER

    // "Again" resets the game while the result card is still fading out. The
    // card keeps showing the game that ended, not the one that just began.
    val ended = remember { arrayOfNulls<GameState>(1) }
    if (over) ended[0] = state

    // Routed through the View so the platform constants carry the meaning and
    // the user's system haptics setting is respected for free.
    val view = LocalView.current
    LaunchedEffect(viewModel, view) {
        viewModel.haptics.collect { haptic ->
            view.performHapticFeedback(
                when (haptic) {
                    Haptic.TICK -> HapticFeedbackConstants.CLOCK_TICK
                    Haptic.LIGHT -> HapticFeedbackConstants.KEYBOARD_TAP
                    Haptic.MEDIUM -> HapticFeedbackConstants.VIRTUAL_KEY
                    Haptic.HEAVY -> HapticFeedbackConstants.LONG_PRESS
                    Haptic.WIN -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        HapticFeedbackConstants.CONFIRM
                    } else {
                        HapticFeedbackConstants.LONG_PRESS
                    }
                },
            )
        }
    }

    // Back asks first: there is no saved game to come back to.
    BackHandler { if (over || online?.error != null) onExit() else leaving = !leaving }

    Box(
        Modifier
            .fillMaxSize()
            .background(Grid.Paper),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = GridTokens.GapWide)
                .padding(top = GridTokens.GapWide, bottom = GridTokens.Gap),
        ) {
            Header(state, online, onExit = { if (online?.error != null) onExit() else leaving = true })
            Spacer(Modifier.height(GridTokens.Gap))
            StatusBar(state, online, onUndo = viewModel::undo, onDone = viewModel::done)
            Spacer(Modifier.height(GridTokens.Gap))

            // The board is the screen. It takes the full width unless the height
            // left over is shorter, and the two plates stay pinned to its edges.
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                val plates = (PLATE + PLATE_GAP) * 2
                val frame = GridTokens.BorderThick * 2
                val fitted = (maxHeight - plates - frame) * (Geometry.COLUMNS / Geometry.HEIGHT) + frame
                Column(Modifier.width(min(maxWidth, fitted))) {
                    Plate(state, state.bottom.other, viewModel::roll)
                    Spacer(Modifier.height(PLATE_GAP))
                    BackgammonBoard(
                        render = BoardRender(state),
                        onTap = viewModel::tap,
                        onRoll = viewModel::roll,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(PLATE_GAP))
                    Plate(state, state.bottom, viewModel::roll)
                }
            }
        }

        val info = online
        AnimatedVisibility(
            visible = info != null && info.error == null && info.status == Status.WAITING && !leaving,
            enter = fadeIn(Motion.enter()),
            exit = fadeOut(Motion.exit()),
        ) {
            WaitingOverlay(code = info?.code.orEmpty(), onCancel = onExit)
        }

        AnimatedVisibility(visible = leaving && !over, enter = fadeIn(Motion.enter()), exit = fadeOut(Motion.exit())) {
            LeaveOverlay(
                detail = when {
                    info == null -> "This game will not be kept"
                    info.status == Status.PLAYING -> "Leaving now resigns the game"
                    else -> "The room closes"
                },
                onStay = { leaving = false },
                onLeave = onExit,
            )
        }

        AnimatedVisibility(visible = over && info?.error == null, enter = fadeIn(Motion.enter()), exit = fadeOut(Motion.exit())) {
            OutcomeOverlay(
                state = ended[0] ?: state,
                score = score,
                online = info,
                onRematch = { if (info != null) viewModel.rematch() else viewModel.newGame(fresh = false) },
                onExit = onExit,
            )
        }

        AnimatedVisibility(visible = info?.error != null, enter = fadeIn(Motion.enter()), exit = fadeOut(Motion.exit())) {
            ErrorOverlay(message = info?.error.orEmpty(), onExit = onExit)
        }
    }
}

private val ColorFade = Motion.fade<Color>()
private val PLATE = DIE + GridTokens.ShadowSmall
private val PLATE_GAP = 10.dp
private val DIE_GAP = 6.dp

@Composable
private fun Header(state: GameState, online: OnlineInfo?, onExit: () -> Unit) {
    // The exit square wears the colour of whoever is on turn, so the one accent
    // in the header is also the fastest answer to "whose go is it".
    val accent by animateColorAsState(state.turn.color, ColorFade, label = "turn")
    val mark by animateColorAsState(onAccent(state.turn.color), ColorFade, label = "mark")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                state.variant.title.uppercase(),
                style = MaterialTheme.typography.displayMedium,
                color = Grid.Ink,
            )
            Caption(
                "${state.variant.detail} · " + when {
                    online != null && online.code.isNotEmpty() -> "room ${online.code}"
                    online != null -> "opening a room"
                    state.vsBot -> "bot"
                    else -> "pass the phone"
                },
            )
        }
        Box(
            Modifier
                .size(44.dp)
                .background(accent)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onExit,
                ),
        ) {
            Text(
                "×",
                style = MaterialTheme.typography.displayMedium,
                color = mark,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
    Spacer(Modifier.height(GridTokens.Gap))
    BrutalRule()
}

@Composable
private fun StatusBar(state: GameState, online: OnlineInfo?, onUndo: () -> Unit, onDone: () -> Unit) {
    val you = (state.vsBot || state.online) && state.humanTurn
    val name = if (state.online) "Friend" else state.turn.title
    val label = when {
        state.phase == Phase.OVER -> "Game over"
        online != null && online.link == Link.CONNECTING -> "Connecting"
        online != null && online.link == Link.OFFLINE -> "Reconnecting"
        online?.status == Status.WAITING -> "Waiting for friend"
        online != null && !online.opponentOnline -> "Friend away"
        state.opening -> "Who starts"
        state.phase == Phase.ROLL || state.phase == Phase.ROLLING -> if (you) "Your roll" else "$name rolls"
        else -> if (you) "Your move" else "$name moves"
    }
    val note = when (state.note) {
        Note.NONE -> ""
        Note.TIE -> "Tie · again"
        Note.NO_MOVE -> "No move"
    }
    // Undo and Done belong to a human's turn once there is something to play.
    val keys = state.humanTurn && !state.opening &&
        (state.phase == Phase.MOVING || state.phase == Phase.CONFIRM || (state.phase == Phase.MOVE && state.legal.isNotEmpty()))

    // A slow pulse is the only "waiting" affordance in the app -- no spinners.
    val waiting = state.phase != Phase.OVER && !state.humanTurn
    val pulse = rememberBreath(waiting)
    val chip by animateColorAsState(state.turn.color, ColorFade, label = "chip")

    Row(
        Modifier
            .fillMaxWidth()
            .height(KEY + GridTokens.ShadowSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(14.dp)
                .graphicsLayer { alpha = 1f - 0.75f * pulse.value }
                .background(chip),
        )
        Spacer(Modifier.width(GridTokens.Gap))
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.titleLarge,
            color = Grid.Ink,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Actions(state, keys, note, onUndo, onDone)
    }
}

/** The right end of the status line: the note, or on a human's turn, Undo and Done in its place. */
@Composable
private fun Actions(state: GameState, keys: Boolean, note: String, onUndo: () -> Unit, onDone: () -> Unit) {
    Box(contentAlignment = Alignment.CenterEnd) {
        AnimatedVisibility(visible = !keys, enter = fadeIn(Motion.enter()), exit = fadeOut(Motion.exit())) {
            Caption(note)
        }
        AnimatedVisibility(visible = keys, enter = fadeIn(Motion.enter()), exit = fadeOut(Motion.exit())) {
            Row(horizontalArrangement = Arrangement.spacedBy(GridTokens.Gap)) {
                Key("Undo", enabled = state.history.isNotEmpty() && state.phase != Phase.MOVING, onClick = onUndo)
                Key("Done", enabled = state.phase == Phase.CONFIRM, fill = Grid.Yellow, onClick = onDone)
            }
        }
    }
}

private val KEY = 36.dp

/**
 * A small action slab. Disabled, it lies flat in grey; enabled, it stands on
 * its shadow, and if it is the thing the turn is waiting on, it breathes.
 */
@Composable
private fun Key(label: String, enabled: Boolean, onClick: () -> Unit, fill: Color = Grid.Paper) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shadow by animateDpAsState(if (enabled) GridTokens.ShadowSmall else 0.dp, Motion.settle(), label = "stand")
    val depth by animateDpAsState(if (pressed && enabled) GridTokens.ShadowSmall else 0.dp, Motion.press(), label = "press")
    val body by animateColorAsState(if (enabled) fill else Grid.PaperDeep, ColorFade, label = "body")
    val ink by animateColorAsState(if (enabled) Grid.Ink else Grid.InkSoft, ColorFade, label = "ink")
    val pulse = rememberBreath(enabled && fill != Grid.Paper)

    Box(Modifier.size(KEY_WIDTH + GridTokens.ShadowSmall, KEY + GridTokens.ShadowSmall)) {
        BrutalSlab(
            modifier = Modifier
                .size(KEY_WIDTH, KEY)
                .graphicsLayer {
                    val s = 1f + 0.05f * pulse.value
                    scaleX = s
                    scaleY = s
                }
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                ),
            fill = body,
            border = ink,
            shadow = shadow,
            pressedDepth = depth.coerceAtMost(shadow),
        ) {
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = ink,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

private val KEY_WIDTH = 76.dp

@Composable
private fun Plate(state: GameState, player: Player, onRoll: () -> Unit) {
    val over = state.phase == Phase.OVER
    val mine = state.turn == player && !state.opening
    val onTurn = !over && (state.opening || mine)
    val rollable = onTurn && player !in state.bots && player != state.remote && state.phase == Phase.ROLL &&
        !(state.opening && state.note == Note.TIE)
    val rolling = onTurn && state.phase == Phase.ROLLING

    // The throw being played, each die spent or not; otherwise whatever was thrown last.
    val dice: List<Pair<Int, Boolean>> = when {
        mine && state.roll.size == 4 -> {
            val spent = state.roll.size - state.left.size
            state.roll.mapIndexed { i, face -> face to (i < spent) }
        }
        mine && state.roll.isNotEmpty() -> {
            val left = state.left.toMutableList()
            state.roll.map { face -> face to !left.remove(face) }
        }
        else -> state.faces.getValue(player).map { it to false }
    }
    // A die on its way out still needs a face to show while it fades.
    val shown = remember { IntArray(4) { 1 } }
    dice.forEachIndexed { i, (face, _) -> shown[i] = face }

    val title = name(state, player)

    Row(
        Modifier
            .fillMaxWidth()
            .height(PLATE),
        verticalAlignment = Alignment.Top,
    ) {
        for (i in 0 until 4) {
            AnimatedVisibility(
                visible = i < dice.size,
                enter = fadeIn(Motion.enter()) + scaleIn(Motion.settle(), initialScale = 0.7f),
                exit = fadeOut(Motion.exit()) + scaleOut(Motion.exit(), targetScale = 0.7f),
            ) {
                val spent = dice.getOrNull(i)?.second ?: false
                Row {
                    if (i > 0) Spacer(Modifier.width(DIE_GAP))
                    Die(
                        face = shown[i],
                        fill = if (onTurn) player.color else Grid.Paper,
                        pip = if (onTurn) onAccent(player.color) else Grid.Ink,
                        raised = onTurn,
                        spent = spent,
                        rollable = rollable,
                        rolling = rolling,
                        onRoll = onRoll,
                    )
                }
            }
        }
        Spacer(Modifier.width(GridTokens.Gap))
        Column(
            Modifier
                .weight(1f)
                .height(DIE),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PlayerGlyph(player, size = 13.dp)
                Text(
                    title.uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    color = Grid.Ink,
                    maxLines = 1,
                )
            }
            Caption("${state.pips(player)} pips")
        }
    }
}

/**
 * The dim fades; the card on it also comes up a little into place, quick to
 * start and slow to settle, and drops away the other way round.
 */
@Composable
private fun AnimatedVisibilityScope.Scrim(onDismiss: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Grid.Ink.copy(alpha = 0.82f))
            // Swallows taps either way, so the board underneath is never live.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onDismiss?.invoke() },
            )
            .padding(GridTokens.Page),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.animateEnterExit(
                enter = slideInVertically(Motion.enter(Motion.RISE_MS)) { it / 8 },
                exit = slideOutVertically(Motion.exit()) { it / 8 },
            ),
        ) {
            content()
        }
    }
}

@Composable
private fun AnimatedVisibilityScope.LeaveOverlay(detail: String, onStay: () -> Unit, onLeave: () -> Unit) {
    Scrim(onDismiss = onStay) {
        BrutalCard(Modifier.fillMaxWidth()) {
            Text(
                "LEAVE?",
                style = MaterialTheme.typography.displayMedium,
                color = Grid.Ink,
            )
            Spacer(Modifier.height(GridTokens.Gap))
            Caption(detail)
            Spacer(Modifier.height(GridTokens.GapSection))
            Row(horizontalArrangement = Arrangement.spacedBy(GridTokens.GapWide)) {
                BrutalButton(
                    label = "Stay",
                    onClick = onStay,
                    height = 52.dp,
                    modifier = Modifier.weight(1f),
                )
                BrutalButton(
                    label = "Leave",
                    onClick = onLeave,
                    fill = Grid.Red,
                    contentColor = Grid.Paper,
                    height = 52.dp,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun AnimatedVisibilityScope.OutcomeOverlay(
    state: GameState,
    score: Map<Player, Int>,
    online: OnlineInfo?,
    onRematch: () -> Unit,
    onExit: () -> Unit,
) {
    val outcome = state.outcome
    val winner = outcome?.winner ?: state.turn
    val accent = winner.color
    val ink = onAccent(accent)
    val you = (state.vsBot || state.online) && winner !in state.bots && winner != state.remote
    val win = outcome?.win ?: Win.SINGLE
    // Online the server names the ending: it also knows about resignations and walk-outs.
    val worth = online?.points ?: win.points
    val points = if (worth == 1) "1 point" else "$worth points"
    val (again, againable) = when {
        online == null -> "Again" to true
        !online.opponentOnline -> "Friend left" to false
        state.bottom in online.rematch -> "Asked" to false
        state.bottom.other in online.rematch -> "Accept rematch" to true
        else -> "Again" to true
    }

    Scrim {
        BrutalCard(Modifier.fillMaxWidth(), fill = accent) {
            Text(
                when {
                    you -> "YOU\nWIN"
                    state.vsBot -> "BOT\nWINS"
                    state.online -> "FRIEND\nWINS"
                    else -> "${winner.title.uppercase()}\nWINS"
                },
                style = MaterialTheme.typography.displayLarge,
                color = ink,
            )
            Spacer(Modifier.height(GridTokens.Gap))
            Caption("${online?.reason ?: win.title} · $points", color = ink)
            Spacer(Modifier.height(GridTokens.GapWide))

            Tally(state, score)

            Spacer(Modifier.height(GridTokens.GapWide))
            BrutalButton(
                label = again,
                onClick = onRematch,
                enabled = againable,
                modifier = Modifier.fillMaxWidth(),
                height = 52.dp,
            )
            Spacer(Modifier.height(GridTokens.Gap))
            BrutalButton(
                label = "Menu",
                onClick = onExit,
                fill = Grid.Ink,
                contentColor = Grid.Paper,
                modifier = Modifier.fillMaxWidth(),
                height = 52.dp,
            )
        }
    }
}

/** Points across the games of this sitting, on paper whatever the winner's colour, so it always reads. */
@Composable
private fun Tally(state: GameState, score: Map<Player, Int>) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Grid.Ink)
            .padding(GridTokens.Border),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        for (player in Player.entries.sortedByDescending { score.getValue(it) }) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Grid.Paper)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayerGlyph(player, size = 16.dp)
                Spacer(Modifier.width(GridTokens.Gap))
                Text(
                    name(state, player).uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    color = Grid.Ink,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${score.getValue(player)}",
                    style = MaterialTheme.typography.titleLarge,
                    color = Grid.Ink,
                )
            }
        }
    }
}

/** What a side is called on this phone: its colour on a shared one, otherwise who plays it. */
private fun name(state: GameState, player: Player): String = when {
    player == state.remote -> "Friend"
    state.online -> "You"
    !state.vsBot -> player.title
    player in state.bots -> "Bot"
    else -> "You"
}

/** The room code, big enough to read across a table, until the friend is in. */
@Composable
private fun AnimatedVisibilityScope.WaitingOverlay(code: String, onCancel: () -> Unit) {
    val context = LocalContext.current
    Scrim {
        BrutalCard(Modifier.fillMaxWidth(), fill = Grid.Yellow) {
            Caption(if (code.isEmpty()) "Opening a room" else "Room code", color = Grid.Ink)
            Text(
                code.ifEmpty { "····" },
                style = MaterialTheme.typography.displayLarge.copy(fontFamily = FontFamily.Monospace),
                color = Grid.Ink,
            )
            Caption("Your friend enters it under Online · Join", color = Grid.Ink)
            Spacer(Modifier.height(GridTokens.GapSection))
            BrutalButton(
                label = "Share code",
                onClick = {
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, "Play me in Grid Backgammon. Room code: $code")
                    context.startActivity(Intent.createChooser(send, null))
                },
                enabled = code.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                height = 52.dp,
            )
            Spacer(Modifier.height(GridTokens.Gap))
            BrutalButton(
                label = "Cancel",
                onClick = onCancel,
                fill = Grid.Ink,
                contentColor = Grid.Paper,
                modifier = Modifier.fillMaxWidth(),
                height = 52.dp,
            )
        }
    }
}

@Composable
private fun AnimatedVisibilityScope.ErrorOverlay(message: String, onExit: () -> Unit) {
    Scrim {
        BrutalCard(Modifier.fillMaxWidth(), fill = Grid.Red) {
            Text("NO GAME", style = MaterialTheme.typography.displayMedium, color = Grid.Paper)
            Spacer(Modifier.height(GridTokens.Gap))
            Caption(message, color = Grid.Paper)
            Spacer(Modifier.height(GridTokens.GapSection))
            BrutalButton(label = "Menu", onClick = onExit, modifier = Modifier.fillMaxWidth(), height = 52.dp)
        }
    }
}
