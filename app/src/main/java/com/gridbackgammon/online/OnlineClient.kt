package com.gridbackgammon.online

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

enum class Link { CONNECTING, ONLINE, OFFLINE }

/**
 * One seat at one online game, kept alive across a flaky connection: when the
 * socket drops it comes back on its own and asks for the seat by its token.
 *
 * [opening] is what is sent the first time -- a create or a join. Once the
 * server has seated us, every later connection resumes instead.
 */
class OnlineClient(
    private val url: String,
    private val scope: CoroutineScope,
    private val opening: ClientMsg,
) {
    private val http = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private val _link = MutableStateFlow(Link.CONNECTING)
    val link: StateFlow<Link> = _link.asStateFlow()

    private val _messages = MutableSharedFlow<ServerMsg>(extraBufferCapacity = 64)
    val messages: SharedFlow<ServerMsg> = _messages.asSharedFlow()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var token: String? = null
    @Volatile private var closed = false
    private var retry: Job? = null
    private var attempts = 0

    fun start() = connect()

    /** Dropped silently when offline: the state sent on reconnect shows what the server actually has. */
    fun send(msg: ClientMsg) {
        socket?.send(Wire.encodeToString(ClientMsg.serializer(), msg))
    }

    /** For good: no more reconnects. [farewell] goes out first if the socket is up. */
    fun close(farewell: ClientMsg? = null) {
        closed = true
        retry?.cancel()
        farewell?.let(::send)
        socket?.close(1000, null)
        socket = null
        http.dispatcher.executorService.shutdown()
    }

    private fun connect() {
        if (closed) return
        _link.value = Link.CONNECTING
        socket = http.newWebSocket(Request.Builder().url(url).build(), Listener())
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket !== socket) return
            attempts = 0
            _link.value = Link.ONLINE
            val hello = token?.let { ClientMsg.Resume(it) } ?: opening
            webSocket.send(Wire.encodeToString(ClientMsg.serializer(), hello))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== socket) return
            val msg = try {
                Wire.decodeFromString(ServerMsg.serializer(), text)
            } catch (e: Exception) {
                return
            }
            if (msg is ServerMsg.Seat) token = msg.token
            _messages.tryEmit(msg)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped(webSocket)

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(webSocket)
    }

    private fun dropped(webSocket: WebSocket) {
        if (webSocket !== socket || closed) return
        socket = null
        _link.value = Link.OFFLINE
        // 1, 2, 4, 8 seconds, then every 8: the server holds the seat for two minutes.
        val wait = 1_000L shl attempts.coerceAtMost(3)
        attempts++
        retry = scope.launch {
            delay(wait)
            connect()
        }
    }
}
