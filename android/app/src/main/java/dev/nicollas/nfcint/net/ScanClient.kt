package dev.nicollas.nfcint.net

import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.nicollas.nfcint.data.ServerConfig
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

sealed interface ConnState {
    data object Offline : ConnState
    data object Connecting : ConnState
    data object Connected : ConnState
    data class Failed(val motivo: String) : ConnState
}

/**
 * WebSocket para o backend. Se a conexão cair, reconecta sozinho com backoff e
 * segura as leituras numa fila — nenhum cartão encostado se perde por causa de
 * Wi-Fi instável.
 */
class ScanClient {

    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val principal = Handler(Looper.getMainLooper())
    private val fila = ArrayDeque<String>()
    private var socket: WebSocket? = null
    private var config: ServerConfig? = null
    private var querConectar = false
    private var tentativas = 0

    private val _state = MutableStateFlow<ConnState>(ConnState.Offline)
    val state: StateFlow<ConnState> = _state

    /** Chamado quando o servidor responde uma leitura. */
    var onResult: ((JSONObject) -> Unit)? = null

    /** O painel do PC pode armar uma gravação daqui. */
    var onWriteJob: ((JSONObject) -> Unit)? = null
    var onWriteCancel: (() -> Unit)? = null

    val pendentes: Int get() = fila.size

    fun connect(cfg: ServerConfig) {
        config = cfg
        querConectar = true
        tentativas = 0
        abrir()
    }

    fun disconnect() {
        querConectar = false
        principal.removeCallbacksAndMessages(null)
        socket?.close(1000, "app pausado")
        socket = null
        _state.value = ConnState.Offline
    }

    fun send(payload: JSONObject) {
        val texto = payload.toString()
        val enviado = socket?.send(texto) ?: false
        if (!enviado) {
            if (fila.size >= 100) fila.removeFirst()
            fila.addLast(texto)
            Log.i(TAG, "sem conexão — leitura na fila (${fila.size})")
            if (querConectar) abrir()
        }
    }

    private fun abrir() {
        val cfg = config ?: return
        if (!querConectar || socket != null) return
        _state.value = ConnState.Connecting
        val req = Request.Builder().url(cfg.wsUrl).build()
        socket = http.newWebSocket(req, Listener())
    }

    private fun agendarReconexao() {
        if (!querConectar) return
        val espera = minOf(1000L * (1 shl minOf(tentativas, 4)), 15_000L)
        tentativas++
        principal.postDelayed({ abrir() }, espera)
    }

    private fun drenarFila() {
        while (fila.isNotEmpty()) {
            val texto = fila.first()
            if (socket?.send(texto) == true) fila.removeFirst() else break
        }
    }

    private inner class Listener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            tentativas = 0
            _state.value = ConnState.Connected
            Log.i(TAG, "conectado em ${config?.wsUrl}")
            drenarFila()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return
            when (json.optString("type")) {
                "hello", "pong" -> Unit
                "write_job" -> principal.post { onWriteJob?.invoke(json) }
                "write_cancel" -> principal.post { onWriteCancel?.invoke() }
                else -> principal.post { onResult?.invoke(json) }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            socket = null
            val motivo = when (val code = response?.code) {
                null -> t.message ?: t.javaClass.simpleName
                401, 403 -> "token recusado pelo servidor"
                else -> "HTTP $code"
            }
            _state.value = ConnState.Failed(motivo)
            Log.w(TAG, "falhou: $motivo")
            agendarReconexao()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            socket = null
            _state.value = if (querConectar) ConnState.Failed(reason.ifBlank { "fechado ($code)" })
            else ConnState.Offline
            if (querConectar) agendarReconexao()
        }
    }

    private companion object {
        const val TAG = "ScanClient"
    }
}
