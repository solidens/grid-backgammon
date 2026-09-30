package com.gridbackgammon.game

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the home screen chooses. Remembered between launches. */
data class Setup(val variant: Variant = Variant.SHORT, val vsBot: Boolean = true, val haptics: Boolean = true)

enum class Haptic { TICK, LIGHT, MEDIUM, HEAVY, WIN }

private sealed interface Input {
    data object Roll : Input
    data class Move(val path: List<Step>) : Input
    data object Done : Input
}

class GameViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("backgammon", Context.MODE_PRIVATE)
    private val random = Random.Default

    private val _setup = MutableStateFlow(
        Setup(
            variant = Variant.entries.getOrElse(prefs.getInt(KEY_VARIANT, 0)) { Variant.SHORT },
            vsBot = prefs.getBoolean(KEY_BOT, true),
            haptics = prefs.getBoolean(KEY_HAPTICS, true),
        ),
    )
    val setup: StateFlow<Setup> = _setup.asStateFlow()

    private val _state = MutableStateFlow(Rules.newGame(_setup.value.variant, _setup.value.vsBot))
    val state: StateFlow<GameState> = _state.asStateFlow()

    /** Points won since the last start from the menu. "Again" plays on and adds to it. */
    private val _score = MutableStateFlow(Player.entries.associateWith { 0 })
    val score: StateFlow<Map<Player, Int>> = _score.asStateFlow()

    private val _haptics = MutableSharedFlow<Haptic>(extraBufferCapacity = 8)
    val haptics: SharedFlow<Haptic> = _haptics.asSharedFlow()

    private var match: Job? = null
    private var input = Channel<Input>(Channel.UNLIMITED)

    fun setVariant(variant: Variant) = change { it.copy(variant = variant) }
    fun setVsBot(vsBot: Boolean) = change { it.copy(vsBot = vsBot) }
    fun setHaptics(on: Boolean) = change { it.copy(haptics = on) }

    private fun change(edit: (Setup) -> Setup) {
        val next = edit(_setup.value)
        _setup.value = next
        prefs.edit()
            .putInt(KEY_VARIANT, next.variant.ordinal)
            .putBoolean(KEY_BOT, next.vsBot)
            .putBoolean(KEY_HAPTICS, next.haptics)
            .apply()
    }

    /** [fresh] starts the tally again: a new sitting, not another game of the same one. */
    fun newGame(fresh: Boolean) {
        match?.cancel()
        if (fresh) _score.value = Player.entries.associateWith { 0 }
        // A fresh channel, so a tap meant for the old match cannot leak into this one.
        val taps = Channel<Input>(Channel.UNLIMITED)
        input = taps
        _state.value = Rules.newGame(_setup.value.variant, _setup.value.vsBot)
        match = viewModelScope.launch { run(taps) }
    }

    /** Back to the menu. Without this the bot would play on behind it. */
    fun leave() {
        match?.cancel()
        match = null
    }

    // Every tap commits to the state before it is queued. The match coroutine
    // only picks it up on a later dispatch, and a double tap arriving in that
    // gap would otherwise sit in the channel and answer the *next* prompt.

    fun roll() {
        val s = _state.value
        val allowed = s.phase == Phase.ROLL && if (s.opening) s.note != Note.TIE else s.humanTurn
        if (!allowed) return
        _state.value = s.copy(phase = Phase.ROLLING, note = Note.NONE)
        input.trySend(Input.Roll)
    }

    /**
     * A tap on the board, as the mover's pos -- [Track.BAR] and [Track.OFF]
     * included -- or null for somewhere nothing can happen.
     *
     * A checker is picked up and then put down. One that can go to only one
     * place goes there straight away; undo is a tap away.
     */
    fun tap(pos: Int?) {
        val s = _state.value
        if (s.phase != Phase.MOVE || !s.humanTurn) return
        val path = if (pos != null && s.selected != null) s.paths[pos] else null
        when {
            path != null -> commit(s, path)
            pos != null && pos != s.selected && pos in s.sources -> {
                val picked = Rules.select(s, pos)
                if (picked.paths.size == 1) commit(picked, picked.paths.values.single()) else _state.value = picked
            }
            else -> _state.value = Rules.deselect(s)
        }
    }

    private fun commit(s: GameState, path: List<Step>) {
        _state.value = s.copy(phase = Phase.MOVING, selected = null, paths = emptyMap())
        input.trySend(Input.Move(path))
    }

    fun undo() {
        val s = _state.value
        if ((s.phase != Phase.MOVE && s.phase != Phase.CONFIRM) || !s.humanTurn || s.history.isEmpty()) return
        _state.value = Rules.undo(s)
        buzz(Haptic.LIGHT)
    }

    fun done() {
        val s = _state.value
        if (s.phase != Phase.CONFIRM || !s.humanTurn) return
        _state.value = Rules.endTurn(s)
        input.trySend(Input.Done)
    }

    /** One match, start to finish, written as the sequence it is. */
    private suspend fun run(taps: ReceiveChannel<Input>) {
        taps.await { it == Input.Roll }
        while (true) {
            val red = random.nextInt(1, 7)
            val blue = random.nextInt(1, 7)
            tumble(mapOf(Player.RED to listOf(red), Player.BLUE to listOf(blue)))
            _state.value = Rules.opening(_state.value, red, blue)
            buzz(Haptic.MEDIUM)
            delay(Timing.OPENING_MS)
            if (_state.value.note != Note.TIE) break
            // A tie is thrown again without asking; the note stays up until it is settled.
            _state.value = _state.value.copy(phase = Phase.ROLLING)
        }

        while (_state.value.phase != Phase.OVER) {
            val bot = _state.value.turn in _state.value.bots

            // The short game's opening throw is already the first player's dice.
            if (_state.value.phase == Phase.ROLL || _state.value.phase == Phase.ROLLING) {
                if (bot) {
                    delay(Timing.BOT_ROLL_MS)
                    _state.value = _state.value.copy(phase = Phase.ROLLING)
                } else {
                    taps.await { it == Input.Roll }
                }
                val a = random.nextInt(1, 7)
                val b = random.nextInt(1, 7)
                tumble(mapOf(_state.value.turn to listOf(a, b)))
                _state.value = Rules.roll(_state.value, a, b)
                buzz(if (a == b) Haptic.HEAVY else Haptic.MEDIUM)
            }

            val thrown = _state.value
            if (thrown.legal.isEmpty()) {
                delay(Timing.NO_MOVE_MS)
                _state.value = Rules.endTurn(_state.value)
                continue
            }

            if (bot) {
                val started = System.nanoTime()
                val plan = withContext(Dispatchers.Default) { Bot.choose(thrown) }
                val spent = (System.nanoTime() - started) / 1_000_000
                delay((Timing.BOT_THINK_MS - spent).coerceAtLeast(0))
                play(plan, gap = Timing.BOT_GAP_MS)
                if (_state.value.phase != Phase.OVER) {
                    delay(Timing.BOT_DONE_MS)
                    _state.value = Rules.endTurn(_state.value)
                }
            } else {
                // Moves and undos until the turn is handed over, or the game won.
                while (_state.value.phase != Phase.OVER) {
                    when (val tap = taps.receive()) {
                        is Input.Move -> play(tap.path, gap = 0)
                        Input.Done -> break
                        Input.Roll -> Unit
                    }
                }
            }
        }
    }

    /** The faces run for the length of a throw and land on [result]. */
    private suspend fun tumble(result: Map<Player, List<Int>>) {
        // The result is drawn first and the blur in front of it second, so
        // that no two faces in a row match and the dice are seen to land.
        for (k in 1 until Timing.TUMBLES) {
            delay(Timing.tumbleGap(k))
            val s = _state.value
            val last = k == Timing.TUMBLES - 1
            val blur = result.mapValues { (player, dice) ->
                val shown = s.faces.getValue(player)
                dice.mapIndexed { i, value ->
                    (1..6).filter { it != shown.getOrNull(i) && !(last && it == value) }.random(random)
                }
            }
            _state.value = s.copy(faces = s.faces + blur)
            buzz(Haptic.TICK)
        }
        delay(Timing.tumbleGap(Timing.TUMBLES))
    }

    /** Steps one after another, each given the time the board takes to show it. */
    private suspend fun play(path: List<Step>, gap: Long) {
        var s = _state.value
        path.forEachIndexed { i, step ->
            if (i > 0) delay(gap)
            s = Rules.apply(s, step)
            _state.value = s.copy(phase = Phase.MOVING)
            delay(Timing.STEP_MS.toLong())
            buzz(if (step.hit) Haptic.HEAVY else Haptic.LIGHT)
            if (step.hit) delay(Timing.HIT_LAG_MS.toLong())
        }
        _state.value = s
        val outcome = s.outcome
        if (s.phase == Phase.OVER && outcome != null) {
            _score.value = _score.value + (outcome.winner to _score.value.getValue(outcome.winner) + outcome.win.points)
            buzz(Haptic.WIN)
        }
    }

    private suspend fun ReceiveChannel<Input>.await(wanted: (Input) -> Boolean): Input {
        while (true) {
            val tap = receive()
            if (wanted(tap)) return tap
        }
    }

    private fun buzz(haptic: Haptic) {
        if (_setup.value.haptics) _haptics.tryEmit(haptic)
    }

    private companion object {
        const val KEY_VARIANT = "variant"
        const val KEY_BOT = "bot"
        const val KEY_HAPTICS = "haptics"
    }
}
