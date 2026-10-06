package com.gridbackgammon.game

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import com.gridbackgammon.BuildConfig
import com.gridbackgammon.online.ClientMsg
import com.gridbackgammon.online.Color
import com.gridbackgammon.online.ErrorCode
import com.gridbackgammon.online.Link
import com.gridbackgammon.online.OnlineClient
import com.gridbackgammon.online.Result
import com.gridbackgammon.online.ServerMsg
import com.gridbackgammon.online.SideChoice
import com.gridbackgammon.online.Status
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
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

/** What the home screen chooses. Remembered between launches. */
data class Setup(
    val variant: Variant = Variant.SHORT,
    val vsBot: Boolean = true,
    val haptics: Boolean = true,
    /** Against a friend on another phone; [vsBot] is then ignored. */
    val online: Boolean = false,
)

/** What the server last said about the room, and the state of the line to it. */
data class OnlineInfo(
    val code: String = "",
    val seated: Boolean = false,
    val status: Status = Status.WAITING,
    val link: Link = Link.CONNECTING,
    val opponentOnline: Boolean = false,
    val rematch: Set<Player> = emptySet(),
    /** Why the game ended, in the server's words: a win's name, or "Opponent left". */
    val reason: String? = null,
    val points: Int? = null,
    /** Set when the game cannot go on -- a bad key, a wrong code -- and the only way is back. */
    val error: String? = null,
)

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
            online = prefs.getBoolean(KEY_ONLINE, false),
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

    /** The Gumroad key that opens rooms. Kept on the phone; only the server can tell if it is good. */
    private val _license = MutableStateFlow(prefs.getString(KEY_LICENSE, "").orEmpty())
    val license: StateFlow<String> = _license.asStateFlow()

    private val _online = MutableStateFlow<OnlineInfo?>(null)
    val online: StateFlow<OnlineInfo?> = _online.asStateFlow()

    val onlineAvailable: Boolean get() = BuildConfig.ONLINE_URL.isNotEmpty()

    private var client: OnlineClient? = null

    /**
     * The game's log as this phone has played it: the server's, plus the steps
     * of a turn not sent yet. The two are compared entry by entry.
     */
    private val log = mutableListOf<String>()

    /** The next state from the server is the truth, whatever this phone thinks: a turn was refused. */
    private var resync = false

    fun setVariant(variant: Variant) = change { it.copy(variant = variant) }
    fun setVsBot(vsBot: Boolean) = change { it.copy(vsBot = vsBot, online = false) }
    fun setOnline() = change { it.copy(online = true) }

    fun setLicense(key: String) {
        _license.value = key.trim()
        prefs.edit().putString(KEY_LICENSE, key.trim()).apply()
    }
    fun setHaptics(on: Boolean) = change { it.copy(haptics = on) }

    private fun change(edit: (Setup) -> Setup) {
        val next = edit(_setup.value)
        _setup.value = next
        prefs.edit()
            .putInt(KEY_VARIANT, next.variant.ordinal)
            .putBoolean(KEY_BOT, next.vsBot)
            .putBoolean(KEY_HAPTICS, next.haptics)
            .putBoolean(KEY_ONLINE, next.online)
            .apply()
    }

    /** [fresh] starts the tally again: a new sitting, not another game of the same one. */
    fun newGame(fresh: Boolean) {
        match?.cancel()
        client?.close(farewell = ClientMsg.Leave)
        client = null
        _online.value = null
        if (fresh) _score.value = Player.entries.associateWith { 0 }
        // A fresh channel, so a tap meant for the old match cannot leak into this one.
        val taps = Channel<Input>(Channel.UNLIMITED)
        input = taps
        _state.value = Rules.newGame(_setup.value.variant, _setup.value.vsBot)
        match = viewModelScope.launch { run(taps) }
    }

    /** Back to the menu. Without this the bot would play on behind it. Online, it gives the game up. */
    fun leave() {
        match?.cancel()
        match = null
        client?.close(farewell = ClientMsg.Leave)
        client = null
        _online.value = null
    }

    /** Opens a room in the chosen game; the friend who joins plays Blue. */
    fun host() = startOnline(
        _setup.value.variant,
        ClientMsg.Create(
            game = "backgammon",
            license = _license.value,
            side = SideChoice.WHITE,
            variant = _setup.value.variant.name.lowercase(),
        ),
    )

    /** The room decides the game; until it says, the board shows the short one. */
    fun join(code: String) = startOnline(Variant.SHORT, ClientMsg.Join(code.trim().uppercase()))

    fun rematch() {
        if (_online.value?.status == Status.OVER) client?.send(ClientMsg.Rematch)
    }

    private fun startOnline(variant: Variant, opening: ClientMsg) {
        leave()
        _score.value = Player.entries.associateWith { 0 }
        log.clear()
        resync = false
        val taps = Channel<Input>(Channel.UNLIMITED)
        input = taps
        val states = Channel<ServerMsg.State>(Channel.UNLIMITED)
        // Until the server seats us, nothing is anyone's to play.
        _state.value = Rules.newGame(variant, vsBot = false).copy(remote = Player.BLUE)
        _online.value = OnlineInfo()
        val line = OnlineClient(BuildConfig.ONLINE_URL, viewModelScope, opening)
        client = line
        match = viewModelScope.launch {
            launch { line.link.collect { link -> _online.value = _online.value?.copy(link = link) } }
            launch {
                line.messages.collect { msg ->
                    when (msg) {
                        is ServerMsg.State -> states.send(msg)
                        is ServerMsg.Seat -> _online.value = _online.value?.copy(code = msg.code, seated = true)
                        is ServerMsg.Error -> refused(msg)
                    }
                }
            }
            runOnline(taps, states, line)
        }
        line.start()
    }

    private fun refused(msg: ServerMsg.Error) {
        // A refused throw or turn: the state right behind it is the one to believe.
        if (msg.code == ErrorCode.ILLEGAL) {
            resync = true
            return
        }
        val info = _online.value ?: return
        if (info.seated && msg.code != ErrorCode.NO_ROOM) return
        client?.close()
        client = null
        _online.value = info.copy(error = msg.message)
    }

    /**
     * An online match: the player's own taps on one side, the server's states
     * on the other, handled one at a time so a move being shown is never
     * overtaken by the next.
     */
    private suspend fun runOnline(taps: ReceiveChannel<Input>, states: ReceiveChannel<ServerMsg.State>, line: OnlineClient) {
        while (true) {
            select<Unit> {
                taps.onReceive { tap ->
                    when (tap) {
                        Input.Roll -> line.send(ClientMsg.Roll(log.size))
                        is Input.Move -> {
                            play(tap.path, gap = 0)
                            tap.path.forEach { log += "s ${it.from} ${it.to} ${it.die}" }
                            // Bearing off the last checker ends the game there and then.
                            if (_state.value.phase == Phase.OVER) send(line)
                        }
                        Input.Done -> {
                            log += "e"
                            send(line)
                        }
                    }
                }
                states.onReceive { sync(it) }
            }
        }
    }

    /** Sends the turn just played: every step since the throw. */
    private fun send(line: OnlineClient) {
        val thrown = log.indexOfLast { it[0] == 'r' || it[0] == 'o' }
        val steps = log.drop(thrown + 1).filter { it[0] == 's' }.map { it.drop(2) }
        line.send(ClientMsg.Turn(thrown + 1, steps))
    }

    private suspend fun sync(msg: ServerMsg.State) {
        val variant = when (msg.variant) {
            "long" -> Variant.LONG
            else -> Variant.SHORT
        }
        val mine = if (msg.color == Color.WHITE) Player.RED else Player.BLUE
        val server = msg.moves
        when {
            resync || variant != _state.value.variant || mine.other != _state.value.remote -> rebuild(variant, mine.other, server)
            server == log -> {
                // A throw asked for and refused leaves the die pressed; let it up.
                if (_state.value.phase == Phase.ROLLING) _state.value = _state.value.copy(phase = Phase.ROLL)
            }
            server.size > log.size && server.subList(0, log.size) == log -> {
                for (entry in server.drop(log.size)) {
                    if (!show(entry)) {
                        rebuild(variant, mine.other, server)
                        break
                    }
                    log += entry
                }
            }
            // This phone is ahead: a turn on its way to the server. Its echo will match.
            log.size > server.size && log.subList(0, server.size) == server -> Unit
            else -> rebuild(variant, mine.other, server)
        }
        resync = false

        // Resigned or walked out: the board does not say so, the server does.
        val s = _state.value
        if (msg.status == Status.OVER && s.phase != Phase.OVER) {
            val winner = if (msg.result == Result.WHITE) Player.RED else Player.BLUE
            _state.value = s.copy(phase = Phase.OVER, legal = emptyList(), outcome = Outcome(winner, Win.SINGLE))
            buzz(Haptic.WIN)
        }
        _score.value = mapOf(Player.RED to msg.score.getOrElse(0) { 0 }, Player.BLUE to msg.score.getOrElse(1) { 0 })
        _online.value = (_online.value ?: OnlineInfo()).copy(
            code = msg.code,
            seated = true,
            status = msg.status,
            opponentOnline = msg.opponentOnline,
            rematch = msg.rematch.mapTo(HashSet()) { if (it == Color.WHITE) Player.RED else Player.BLUE },
            reason = msg.reason,
            points = msg.points,
        )
    }

    /** One entry of the log, played on the board at the pace a bot's would be. False if it does not fit. */
    private suspend fun show(entry: String): Boolean {
        val n = entry.split(' ').drop(1).map { it.toIntOrNull() ?: return false }
        val s = _state.value
        when (entry[0]) {
            'o' -> {
                _state.value = s.copy(phase = Phase.ROLLING)
                tumble(mapOf(Player.RED to listOf(n[0]), Player.BLUE to listOf(n[1])))
                _state.value = Rules.opening(_state.value, n[0], n[1])
                buzz(Haptic.MEDIUM)
                delay(Timing.OPENING_MS)
            }
            'r' -> {
                _state.value = s.copy(phase = Phase.ROLLING)
                tumble(mapOf(s.turn to listOf(n[0], n[1])))
                _state.value = Rules.roll(_state.value, n[0], n[1])
                buzz(if (n[0] == n[1]) Haptic.HEAVY else Haptic.MEDIUM)
            }
            's' -> {
                val step = s.legal.firstOrNull { it.from == n[0] && it.to == n[1] && it.die == n[2] } ?: return false
                if (s.history.isNotEmpty()) delay(Timing.BOT_GAP_MS)
                play(listOf(step), gap = 0)
            }
            'e' -> {
                delay(if (s.history.isEmpty()) Timing.NO_MOVE_MS else Timing.BOT_DONE_MS)
                _state.value = Rules.endTurn(_state.value)
            }
            else -> return false
        }
        return true
    }

    /** The server's game, put on the board at once: after a reconnect, a rematch, or a refused turn. */
    private fun rebuild(variant: Variant, remote: Player, server: List<String>) {
        var s = Rules.newGame(variant, vsBot = false).copy(remote = remote)
        for (entry in server) {
            val n = entry.split(' ').drop(1).map { it.toInt() }
            s = when (entry[0]) {
                'o' -> Rules.opening(s, n[0], n[1])
                'r' -> Rules.roll(s, n[0], n[1])
                's' -> Rules.apply(s, s.legal.first { it.from == n[0] && it.to == n[1] && it.die == n[2] })
                else -> Rules.endTurn(s)
            }
        }
        log.clear()
        log += server
        _state.value = s
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
        if (s.online && log.lastOrNull()?.startsWith("s") == true) log.removeAt(log.lastIndex)
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
            // Online the server keeps the score, and says so in the state that follows.
            if (!s.online) _score.value = _score.value + (outcome.winner to _score.value.getValue(outcome.winner) + outcome.win.points)
            buzz(Haptic.WIN)
        }
    }

    private suspend fun ReceiveChannel<Input>.await(wanted: (Input) -> Boolean): Input {
        while (true) {
            val tap = receive()
            if (wanted(tap)) return tap
        }
    }

    override fun onCleared() {
        super.onCleared()
        client?.close(farewell = ClientMsg.Leave)
    }

    private fun buzz(haptic: Haptic) {
        if (_setup.value.haptics) _haptics.tryEmit(haptic)
    }

    private companion object {
        const val KEY_VARIANT = "variant"
        const val KEY_BOT = "bot"
        const val KEY_HAPTICS = "haptics"
        const val KEY_ONLINE = "online"
        const val KEY_LICENSE = "license"
    }
}
