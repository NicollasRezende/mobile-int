package dev.nicollas.nfcint.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import dev.nicollas.nfcint.data.AppSettings
import dev.nicollas.nfcint.data.ServerConfig
import dev.nicollas.nfcint.net.ConnState
import dev.nicollas.nfcint.net.ScanClient
import dev.nicollas.nfcint.nfc.TagDump
import dev.nicollas.nfcint.nfc.WriteJob
import dev.nicollas.nfcint.nfc.WriteResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

data class Leitura(
    val id: String,
    val dump: TagDump,
    val hora: String,
    val resposta: JSONObject? = null,
    val escrita: WriteResult? = null,
) {
    val servidorRespondeu: Boolean get() = resposta != null
    val autorizado: Boolean get() = resposta?.optBoolean("authorized") == true
    val rotulo: String? get() = resposta?.optString("label")?.takeIf { it.isNotBlank() && it != "null" }
    val mensagem: String? get() = resposta?.optString("message")?.takeIf { it.isNotBlank() }
}

class ScanViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = AppSettings(app)
    private val client = ScanClient()

    private val _config = MutableStateFlow(settings.load())
    val config: StateFlow<ServerConfig> = _config

    private val _leituras = MutableStateFlow<List<Leitura>>(emptyList())
    val leituras: StateFlow<List<Leitura>> = _leituras

    val conexao: StateFlow<ConnState> = client.state

    /** Gravação preparada, esperando a próxima tag. Null = só leitura. */
    private val _gravacao = MutableStateFlow<WriteJob?>(null)
    val gravacao: StateFlow<WriteJob?> = _gravacao

    fun armarGravacao(job: WriteJob) { _gravacao.value = job }

    fun desarmarGravacao() { _gravacao.value = null }

    fun gravacaoArmada(): WriteJob? = _gravacao.value

    private val isoUtc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val horaLocal = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    init {
        client.onResult = { resposta ->
            val id = resposta.optString("id")
            _leituras.value = _leituras.value.map {
                if (it.id == id) it.copy(resposta = resposta) else it
            }
        }
        client.onWriteJob = { json -> jobDoServidor(json)?.let { _gravacao.value = it } }
        client.onWriteCancel = { _gravacao.value = null }
    }

    /** Traduz o comando que veio do painel do PC. */
    private fun jobDoServidor(json: JSONObject): WriteJob? = when (json.optString("mode")) {
        "ndef_text" -> WriteJob.NdefText(json.optString("conteudo"), json.optBoolean("como_uri"))
        "classic_text" -> WriteJob.ClassicText(json.optInt("setor", 1), json.optString("conteudo"))
        "classic_block" -> WriteJob.ClassicBlock(json.optInt("bloco", 4), json.optString("hex"))
        else -> null
    }

    fun iniciar() {
        if (_config.value.enviarParaServidor) client.connect(_config.value)
    }

    fun parar() = client.disconnect()

    fun aplicarConfig(novo: ServerConfig) {
        settings.save(novo)
        _config.value = novo
        client.disconnect()
        if (novo.enviarParaServidor) client.connect(novo)
    }

    /** Chamado quando o TagDumper termina de ler uma tag. */
    fun registrar(dump: TagDump, escrita: WriteResult? = null) {
        // Cada tag grava uma vez: desarma para não regravar sem querer.
        if (escrita != null) _gravacao.value = null

        val agora = Date()
        val leitura = Leitura(
            id = UUID.randomUUID().toString(),
            dump = dump,
            hora = horaLocal.format(agora),
            escrita = escrita,
        )
        _leituras.value = listOf(leitura) + _leituras.value.take(199)

        // O resultado da gravação viaja junto com o dump para aparecer no painel.
        if (escrita != null) {
            dump.json.put(
                "write_result",
                JSONObject()
                    .put("ok", escrita.ok)
                    .put("mensagem", escrita.mensagem)
                    .put("detalhes", JSONArray(escrita.detalhes)),
            )
        }

        if (_config.value.enviarParaServidor) {
            client.send(
                JSONObject()
                    .put("type", "scan")
                    .put("id", leitura.id)
                    .put("device", _config.value.device)
                    .put("scanned_at", isoUtc.format(agora))
                    .put("dump", dump.json),
            )
        }
    }

    fun limpar() {
        _leituras.value = emptyList()
    }

    override fun onCleared() {
        client.disconnect()
        super.onCleared()
    }
}
