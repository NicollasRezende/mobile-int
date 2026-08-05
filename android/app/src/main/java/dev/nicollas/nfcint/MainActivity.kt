package dev.nicollas.nfcint

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.nicollas.nfcint.nfc.TagDumper
import dev.nicollas.nfcint.nfc.TagWriter
import dev.nicollas.nfcint.ui.NfcIntTheme
import dev.nicollas.nfcint.ui.ScanScreen
import dev.nicollas.nfcint.ui.ScanViewModel

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {

    private val vm: ScanViewModel by viewModels()
    private var adapter: NfcAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adapter = NfcAdapter.getDefaultAdapter(this)

        setContent {
            NfcIntTheme {
                val leituras by vm.leituras.collectAsState()
                val conexao by vm.conexao.collectAsState()
                val config by vm.config.collectAsState()
                val gravacao by vm.gravacao.collectAsState()

                ScanScreen(
                    leituras = leituras,
                    conexao = conexao,
                    config = config,
                    nfcDisponivel = adapter?.isEnabled == true,
                    gravacao = gravacao,
                    onConfig = vm::aplicarConfig,
                    onLimpar = vm::limpar,
                    onArmar = vm::armarGravacao,
                    onDesarmar = vm::desarmarGravacao,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.iniciar()

        val nfc = adapter
        if (nfc == null) {
            Toast.makeText(this, "Este aparelho não tem NFC", Toast.LENGTH_LONG).show()
            return
        }
        if (!nfc.isEnabled) {
            Toast.makeText(this, "Ligue o NFC nas configurações", Toast.LENGTH_LONG).show()
            return
        }

        // Reader mode pega TODAS as tecnologias. Sem SKIP_NDEF_CHECK, porque o
        // check é justamente o que nos entrega a mensagem NDEF já em cache.
        val flags = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V or
            NfcAdapter.FLAG_READER_NFC_BARCODE

        val extras = Bundle().apply {
            // O presence check conversa com a tag por fora do nosso dump e quebra a
            // sessão autenticada do MIFARE Classic no meio da leitura. Empurramos
            // bem para longe: o dump tem teto próprio de 9 s.
            putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 30000)
        }
        nfc.enableReaderMode(this, this, flags, extras)
    }

    override fun onPause() {
        super.onPause()
        adapter?.disableReaderMode(this)
        vm.parar()
    }

    /** Roda numa thread de binder, fora da main — pode fazer I/O à vontade. */
    override fun onTagDiscovered(tag: Tag) {
        vibrar()

        // Gravação só acontece quando foi armada na tela. Fora isso, a tag é
        // apenas lida — encostar um cartão nunca altera nada por acidente.
        val escrita = vm.gravacaoArmada()?.let { job ->
            TagWriter.write(tag, job).also {
                Log.i("MainActivity", "gravação: ok=${it.ok} ${it.mensagem}")
            }
        }

        val dump = try {
            TagDumper.dump(tag)
        } catch (t: Throwable) {
            Log.e("MainActivity", "dump falhou", t)
            runOnUiThread {
                val extra = escrita?.mensagem?.let { " (gravação: $it)" } ?: ""
                Toast.makeText(this, "Falha ao ler: ${t.message}$extra", Toast.LENGTH_LONG).show()
            }
            return
        }
        Log.i("MainActivity", "tag ${dump.uid} (${dump.guess}) em ${dump.readMs} ms")
        runOnUiThread { vm.registrar(dump, escrita) }
    }

    @Suppress("DEPRECATION")
    private fun vibrar() {
        val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VibratorManager::class.java))?.defaultVibrator
        } else {
            getSystemService(Vibrator::class.java)
        } ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vib.vibrate(40)
        }
    }
}
