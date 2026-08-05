package dev.nicollas.nfcint.nfc

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.util.Log

/** O que gravar. Escolhido na tela antes de encostar a tag. */
sealed interface WriteJob {
    /** Texto ou URL como mensagem NDEF — lida por qualquer celular, sem app. */
    data class NdefText(val conteudo: String, val comoUri: Boolean) : WriteJob

    /** Texto cru nos blocos de dados de um setor do MIFARE Classic. */
    data class ClassicText(val setor: Int, val conteudo: String) : WriteJob

    /** 16 bytes exatos num bloco específico. Para quem sabe o que está fazendo. */
    data class ClassicBlock(val bloco: Int, val hex: String) : WriteJob
}

data class WriteResult(
    val ok: Boolean,
    val mensagem: String,
    val detalhes: List<String> = emptyList(),
)

/**
 * Gravação em tag.
 *
 * Duas regras que o código não deixa quebrar, porque o estrago é permanente:
 * o **bloco 0** (onde vive o UID) nunca é escrito, e o **trailer** de cada setor
 * também não — é ele que guarda as chaves e os bits de permissão, e um valor
 * errado ali transforma o setor em pedra, sem volta.
 */
object TagWriter {

    private const val TAG = "TagWriter"

    fun write(tag: Tag, job: WriteJob): WriteResult = try {
        when (job) {
            is WriteJob.NdefText -> escreverNdef(tag, job)
            is WriteJob.ClassicText -> escreverTextoClassic(tag, job)
            is WriteJob.ClassicBlock -> escreverBlocoClassic(tag, job)
        }
    } catch (t: Throwable) {
        Log.e(TAG, "falha ao gravar", t)
        WriteResult(false, "falhou: ${t.javaClass.simpleName}: ${t.message ?: "sem detalhe"}")
    }

    // -------------------------------------------------------------------- NDEF

    private fun escreverNdef(tag: Tag, job: WriteJob.NdefText): WriteResult {
        val record = if (job.comoUri) {
            NdefRecord.createUri(job.conteudo)
        } else {
            NdefRecord.createTextRecord("pt", job.conteudo)
        }
        val msg = NdefMessage(arrayOf(record))
        val tamanho = msg.byteArrayLength

        Ndef.get(tag)?.let { ndef ->
            ndef.connect()
            try {
                if (!ndef.isWritable) {
                    return WriteResult(false, "esta tag é somente leitura")
                }
                if (tamanho > ndef.maxSize) {
                    return WriteResult(
                        false,
                        "não cabe: a mensagem tem $tamanho bytes e a tag aceita ${ndef.maxSize}",
                    )
                }
                ndef.writeNdefMessage(msg)
                return WriteResult(
                    true,
                    "gravado: $tamanho bytes de ${ndef.maxSize} disponíveis",
                    listOf("tipo NDEF: ${ndef.type}", "conteúdo: ${job.conteudo}"),
                )
            } finally {
                runCatching { ndef.close() }
            }
        }

        NdefFormatable.get(tag)?.let { fmt ->
            fmt.connect()
            try {
                // Em MIFARE Classic isso reescreve o setor 0 com o MAD e troca as
                // chaves dos setores de dados para a chave pública de NDEF.
                fmt.format(msg)
                return WriteResult(
                    true,
                    "tag formatada como NDEF e gravada ($tamanho bytes)",
                    listOf(
                        "as chaves dos setores viraram as públicas de NDEF (D3F7D3F7D3F7)",
                        "conteúdo: ${job.conteudo}",
                    ),
                )
            } finally {
                runCatching { fmt.close() }
            }
        }

        return WriteResult(false, "esta tag não aceita NDEF")
    }

    // ---------------------------------------------------------- MIFARE Classic

    private fun escreverTextoClassic(tag: Tag, job: WriteJob.ClassicText): WriteResult {
        val mc = MifareClassic.get(tag)
            ?: return WriteResult(false, "esta tag não é MIFARE Classic")
        val bytes = job.conteudo.toByteArray(Charsets.UTF_8)

        mc.connect()
        mc.timeout = 700
        try {
            if (job.setor !in 0 until mc.sectorCount) {
                return WriteResult(false, "setor ${job.setor} não existe (0..${mc.sectorCount - 1})")
            }
            val alvos = blocosGravaveis(mc, job.setor)
            if (alvos.isEmpty()) {
                return WriteResult(false, "o setor ${job.setor} não tem bloco gravável")
            }
            val capacidade = alvos.size * MifareClassic.BLOCK_SIZE
            if (bytes.size > capacidade) {
                return WriteResult(
                    false,
                    "não cabe: o texto tem ${bytes.size} bytes e o setor ${job.setor} " +
                        "guarda $capacidade",
                )
            }

            val chave = autenticar(mc, job.setor)
                ?: return WriteResult(
                    false,
                    "nenhuma chave de fábrica abriu o setor ${job.setor} — sem a chave certa " +
                        "não dá para gravar",
                )

            val detalhes = mutableListOf<String>()
            alvos.forEachIndexed { i, bloco ->
                val pedaco = ByteArray(MifareClassic.BLOCK_SIZE)
                val inicio = i * MifareClassic.BLOCK_SIZE
                if (inicio < bytes.size) {
                    bytes.copyInto(
                        pedaco,
                        0,
                        inicio,
                        minOf(inicio + MifareClassic.BLOCK_SIZE, bytes.size),
                    )
                }
                mc.writeBlock(bloco, pedaco)
                detalhes += "bloco $bloco ← ${pedaco.toHex()}"
            }

            val conferencia = conferir(mc, job.setor, chave, alvos, bytes)
            return if (conferencia == null) {
                WriteResult(
                    true,
                    "gravado no setor ${job.setor}: ${bytes.size} de $capacidade bytes " +
                        "(chave ${chave.second} ${chave.first})",
                    detalhes,
                )
            } else {
                WriteResult(false, "gravou mas a releitura não bateu: $conferencia", detalhes)
            }
        } finally {
            runCatching { mc.close() }
        }
    }

    private fun escreverBlocoClassic(tag: Tag, job: WriteJob.ClassicBlock): WriteResult {
        val mc = MifareClassic.get(tag)
            ?: return WriteResult(false, "esta tag não é MIFARE Classic")

        val dados = try {
            job.hex.hexToBytes()
        } catch (e: IllegalArgumentException) {
            return WriteResult(false, "hex inválido: ${e.message}")
        }
        if (dados.size != MifareClassic.BLOCK_SIZE) {
            return WriteResult(
                false,
                "um bloco tem exatamente 16 bytes (32 dígitos hex); você passou ${dados.size}",
            )
        }

        mc.connect()
        mc.timeout = 700
        try {
            if (job.bloco !in 0 until mc.blockCount) {
                return WriteResult(false, "bloco ${job.bloco} não existe (0..${mc.blockCount - 1})")
            }
            if (job.bloco == 0) {
                return WriteResult(false, "o bloco 0 guarda o UID e não é gravável")
            }
            val setor = mc.blockToSector(job.bloco)
            if (job.bloco !in blocosGravaveis(mc, setor)) {
                return WriteResult(
                    false,
                    "o bloco ${job.bloco} é o trailer do setor $setor — gravar ali pode " +
                        "inutilizar o setor para sempre, então está bloqueado",
                )
            }

            val chave = autenticar(mc, setor)
                ?: return WriteResult(false, "nenhuma chave de fábrica abriu o setor $setor")

            mc.writeBlock(job.bloco, dados)
            val lido = mc.readBlock(job.bloco)
            return if (lido.contentEquals(dados)) {
                WriteResult(
                    true,
                    "bloco ${job.bloco} gravado e conferido (setor $setor, chave ${chave.second})",
                    listOf("conteúdo: ${dados.toHex()}", "ascii: ${dados.toAscii()}"),
                )
            } else {
                WriteResult(
                    false,
                    "gravou mas a releitura veio diferente: ${lido.toHex()}",
                )
            }
        } finally {
            runCatching { mc.close() }
        }
    }

    /** Blocos de dados do setor: sem o trailer e sem o bloco 0. */
    private fun blocosGravaveis(mc: MifareClassic, setor: Int): List<Int> {
        val primeiro = mc.sectorToBlock(setor)
        val quantos = mc.getBlockCountInSector(setor)
        return (0 until quantos - 1)
            .map { primeiro + it }
            .filter { it != 0 }
    }

    private fun autenticar(mc: MifareClassic, setor: Int): Pair<String, String>? {
        for ((label, key) in MifareKeys.DEFAULT) {
            for (tipo in listOf("A", "B")) {
                val ok = try {
                    if (tipo == "A") mc.authenticateSectorWithKeyA(setor, key)
                    else mc.authenticateSectorWithKeyB(setor, key)
                } catch (e: Exception) {
                    runCatching { mc.close() }
                    runCatching { mc.connect() }
                    false
                }
                if (ok) return label to tipo
            }
        }
        return null
    }

    /** Relê o que foi gravado. Devolve null quando está tudo certo. */
    private fun conferir(
        mc: MifareClassic,
        setor: Int,
        chave: Pair<String, String>,
        alvos: List<Int>,
        esperado: ByteArray,
    ): String? {
        val lido = ByteArray(alvos.size * MifareClassic.BLOCK_SIZE)
        alvos.forEachIndexed { i, bloco ->
            val dados = try {
                mc.readBlock(bloco)
            } catch (e: Exception) {
                if (!autenticarComChaveConhecida(mc, setor, chave)) return "não deu para reler o bloco $bloco"
                runCatching { mc.readBlock(bloco) }.getOrNull()
                    ?: return "não deu para reler o bloco $bloco"
            }
            dados.copyInto(lido, i * MifareClassic.BLOCK_SIZE)
        }
        val recorte = lido.copyOfRange(0, esperado.size)
        return if (recorte.contentEquals(esperado)) null else "leu ${recorte.toHex()}"
    }

    private fun autenticarComChaveConhecida(
        mc: MifareClassic,
        setor: Int,
        chave: Pair<String, String>,
    ): Boolean {
        val bytes = chave.first.hexToBytes()
        return try {
            if (chave.second == "A") mc.authenticateSectorWithKeyA(setor, bytes)
            else mc.authenticateSectorWithKeyB(setor, bytes)
        } catch (e: Exception) {
            false
        }
    }

    /** Quanto cabe, por tipo de tag — usado para avisar antes de tentar gravar. */
    fun capacidade(tag: Tag): String? {
        MifareClassic.get(tag)?.let { mc ->
            val setores = (0 until mc.sectorCount).sumOf { blocosGravaveis(mc, it).size }
            return "${setores * MifareClassic.BLOCK_SIZE} bytes graváveis " +
                "(${mc.sectorCount} setores, fora bloco 0 e trailers)"
        }
        Ndef.get(tag)?.let { return "${it.maxSize} bytes de mensagem NDEF" }
        MifareUltralight.get(tag)?.let { return "páginas de 4 bytes a partir da página 4" }
        return null
    }
}
