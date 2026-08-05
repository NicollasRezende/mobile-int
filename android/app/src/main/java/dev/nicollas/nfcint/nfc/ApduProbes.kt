package dev.nicollas.nfcint.nfc

/**
 * Sondas ISO 7816 para cartões IsoDep. São comandos de *identificação*: perguntam
 * ao cartão quais aplicações ele expõe e qual o número de série. Nenhuma delas lê
 * dados de conta (não há GPO / READ RECORD aqui) — o objetivo é descobrir com o
 * que estamos falando.
 */
data class ApduProbe(val name: String, val command: ByteArray) {
    override fun equals(other: Any?) = other is ApduProbe && name == other.name
    override fun hashCode() = name.hashCode()
}

object ApduProbes {

    val ALL: List<ApduProbe> = listOf(
        ApduProbe(
            "SELECT PPSE (2PAY.SYS.DDF01)",
            "00A404000E325041592E5359532E444446303100".hexToBytes(),
        ),
        ApduProbe(
            "SELECT PSE (1PAY.SYS.DDF01)",
            "00A404000E315041592E5359532E444446303100".hexToBytes(),
        ),
        ApduProbe(
            "SELECT NDEF Application (Type 4)",
            "00A4040007D276000085010100".hexToBytes(),
        ),
        ApduProbe("GET UID (PC/SC)", "FFCA000000".hexToBytes()),
        ApduProbe("DESFire GetVersion", "9060000000".hexToBytes()),
        ApduProbe("SELECT MF (3F00)", "00A4000C023F00".hexToBytes()),
    )

    /** Tradução dos status words mais comuns. */
    fun explainSw(sw: String): String = when {
        sw == "9000" -> "OK"
        sw == "9100" -> "OK (DESFire)"
        sw.startsWith("61") -> "OK, ${sw.substring(2).toInt(16)} bytes disponíveis"
        sw.startsWith("6C") -> "tamanho errado, use Le=${sw.substring(2)}"
        sw == "6A82" -> "aplicação não encontrada"
        sw == "6A81" -> "função não suportada"
        sw == "6A86" -> "parâmetros P1/P2 incorretos"
        sw == "6D00" -> "instrução não suportada"
        sw == "6E00" -> "classe não suportada"
        sw == "6982" -> "condição de segurança não satisfeita"
        sw == "6985" -> "condição de uso não satisfeita"
        sw.isEmpty() -> "sem resposta"
        else -> "status $sw"
    }

    /**
     * Parse mínimo de TLV para achar rótulo (tag 50) e AIDs (tag 4F) na resposta
     * do PPSE — é o que diz "esse cartão tem uma aplicação Visa/Mastercard/Elo".
     */
    fun parseAids(response: ByteArray): List<String> {
        val found = mutableListOf<String>()
        var i = 0
        while (i < response.size - 2) {
            val tag = response[i].toInt() and 0xFF
            if (tag == 0x4F || tag == 0x50) {
                val len = response[i + 1].toInt() and 0xFF
                if (len in 1..32 && i + 2 + len <= response.size) {
                    val value = response.copyOfRange(i + 2, i + 2 + len)
                    found += if (tag == 0x4F) "AID ${value.toHex()}" else "label ${value.toAscii()}"
                    i += 2 + len
                    continue
                }
            }
            i++
        }
        return found
    }
}
