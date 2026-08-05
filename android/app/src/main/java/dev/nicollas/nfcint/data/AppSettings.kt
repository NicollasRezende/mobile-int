package dev.nicollas.nfcint.data

import android.content.Context
import android.os.Build

data class ServerConfig(
    val host: String = "192.168.0.106",
    val port: Int = 8000,
    val token: String = "troque-este-token",
    val device: String = "${Build.MANUFACTURER} ${Build.MODEL}",
    val enviarParaServidor: Boolean = true,
) {
    val wsUrl: String
        get() = "ws://$host:$port/ws/device?token=${enc(token)}&device=${enc(device)}"

    val httpUrl: String
        get() = "http://$host:$port/api/scans?token=${enc(token)}"

    private fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8")
}

class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("nfcint", Context.MODE_PRIVATE)

    fun load(): ServerConfig {
        val padrao = ServerConfig()
        return ServerConfig(
            host = prefs.getString("host", padrao.host) ?: padrao.host,
            port = prefs.getInt("port", padrao.port),
            token = prefs.getString("token", padrao.token) ?: padrao.token,
            device = prefs.getString("device", padrao.device) ?: padrao.device,
            enviarParaServidor = prefs.getBoolean("enviar", true),
        )
    }

    fun save(cfg: ServerConfig) {
        prefs.edit()
            .putString("host", cfg.host)
            .putInt("port", cfg.port)
            .putString("token", cfg.token)
            .putString("device", cfg.device)
            .putBoolean("enviar", cfg.enviarParaServidor)
            .apply()
    }
}
