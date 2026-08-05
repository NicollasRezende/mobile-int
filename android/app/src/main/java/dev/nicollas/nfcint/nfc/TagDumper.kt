package dev.nicollas.nfcint.nfc

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.nfc.tech.NfcA
import android.nfc.tech.NfcB
import android.nfc.tech.NfcBarcode
import android.nfc.tech.NfcF
import android.nfc.tech.NfcV
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

data class TagDump(
    val uid: String,
    val guess: String,
    val techs: List<String>,
    val readMs: Long,
    val errors: List<String>,
    val json: JSONObject,
) {
    val pretty: String get() = json.toString(2)
}

/**
 * Lê TUDO que dá para ler de uma tag, sem saber de antemão o que ela é.
 *
 * A ordem importa: primeiro o que não precisa de conexão, depois as leituras
 * leves (NDEF, Ultralight), e por último MIFARE Classic — cuja autenticação
 * derruba a sessão quando a chave está errada e obriga a reconectar.
 *
 * Nada aqui é fatal: cada etapa que falhar vira uma linha em `errors` e o
 * dump segue em frente.
 */
object TagDumper {

    private const val TAG = "TagDumper"

    /** Teto de tempo. Com a tag encostada, o usuário não segura para sempre. */
    private const val BUDGET_MS = 9_000L

    fun dump(tag: Tag): TagDump {
        val start = SystemClock.elapsedRealtime()
        val deadline = start + BUDGET_MS
        val errors = mutableListOf<String>()
        val json = JSONObject()
        val techs = tag.techList?.toList() ?: emptyList()
        val id = tag.id ?: ByteArray(0)

        json.put("uid", id.toHex())
        json.put("uid_bytes", id.toHex(":"))
        json.put("uid_reversed", id.reversedArray().toHex())
        json.put("uid_len", id.size)
        json.put("uid_kind", uidKind(id))
        json.put("manufacturer", manufacturer(id))
        json.put("tech_list", JSONArray(techs))
        json.put("tag_string", tag.toString())

        fun step(label: String, block: () -> Unit) {
            if (SystemClock.elapsedRealtime() > deadline) {
                errors += "$label: pulado (estourou o tempo de leitura)"
                return
            }
            try {
                block()
            } catch (t: Throwable) {
                errors += "$label: ${t.javaClass.simpleName}: ${t.message ?: "sem detalhe"}"
                Log.w(TAG, "falha em $label", t)
            }
        }

        step("NfcA") { readNfcA(tag, json) }
        step("NfcB") { readNfcB(tag, json) }
        step("NfcF") { readNfcF(tag, json) }
        step("NfcBarcode") { readBarcode(tag, json) }
        step("NDEF") { readNdef(tag, json) }
        step("MifareUltralight") { readUltralight(tag, json, errors) }
        step("NfcV") { readNfcV(tag, json) }
        step("IsoDep") { readIsoDep(tag, json, deadline) }
        step("MifareClassic") { readMifareClassic(tag, json, errors, deadline) }

        json.put("ndef_formatable", NdefFormatable.get(tag) != null)

        val readMs = SystemClock.elapsedRealtime() - start
        val guess = guessType(techs, json)
        json.put("guess", guess)
        json.put("read_ms", readMs.toInt())
        json.put("errors", JSONArray(errors))

        return TagDump(
            uid = id.toHex(),
            guess = guess,
            techs = techs.map { it.substringAfterLast('.') },
            readMs = readMs,
            errors = errors,
            json = json,
        )
    }

    // ---------------------------------------------------------------- básicos

    private fun readNfcA(tag: Tag, json: JSONObject) {
        val nfca = NfcA.get(tag) ?: return
        json.put(
            "nfca",
            JSONObject()
                .put("atqa", nfca.atqa.toHex())
                .put("sak", String.format("%02X", nfca.sak))
                .put("max_transceive", nfca.maxTransceiveLength)
                .put("timeout", nfca.timeout),
        )
    }

    private fun readNfcB(tag: Tag, json: JSONObject) {
        val nfcb = NfcB.get(tag) ?: return
        json.put(
            "nfcb",
            JSONObject()
                .put("application_data", nfcb.applicationData.toHexOrNull())
                .put("protocol_info", nfcb.protocolInfo.toHexOrNull())
                .put("max_transceive", nfcb.maxTransceiveLength),
        )
    }

    private fun readNfcF(tag: Tag, json: JSONObject) {
        val nfcf = NfcF.get(tag) ?: return
        json.put(
            "nfcf",
            JSONObject()
                .put("manufacturer", nfcf.manufacturer.toHexOrNull())
                .put("system_code", nfcf.systemCode.toHexOrNull())
                .put("max_transceive", nfcf.maxTransceiveLength),
        )
    }

    private fun readBarcode(tag: Tag, json: JSONObject) {
        val nb = NfcBarcode.get(tag) ?: return
        json.put(
            "nfc_barcode",
            JSONObject()
                .put("type", if (nb.type == NfcBarcode.TYPE_KOVIO) "Kovio" else "tipo ${nb.type}")
                .put("barcode", nb.barcode.toHexOrNull()),
        )
    }

    // ------------------------------------------------------------------- NDEF

    private fun readNdef(tag: Tag, json: JSONObject) {
        val ndef = Ndef.get(tag) ?: return
        val o = JSONObject()
            .put("type", ndef.type)
            .put("max_size", ndef.maxSize)
            .put("writable", ndef.isWritable)
            .put("can_make_read_only", ndef.canMakeReadOnly())

        var msg: NdefMessage? = ndef.cachedNdefMessage
        if (msg == null) {
            try {
                ndef.connect()
                msg = ndef.ndefMessage
            } catch (e: Exception) {
                o.put("read_error", e.message ?: e.javaClass.simpleName)
            } finally {
                runCatching { ndef.close() }
            }
        }
        if (msg != null) {
            o.put("message_size", msg.byteArrayLength)
            o.put("records", recordsToJson(msg))
        } else {
            o.put("records", JSONArray())
            o.put("note", "tag suporta NDEF mas está vazia")
        }
        json.put("ndef", o)
    }

    private fun recordsToJson(msg: NdefMessage): JSONArray {
        val arr = JSONArray()
        msg.records.forEachIndexed { i, r ->
            arr.put(
                JSONObject()
                    .put("index", i)
                    .put("tnf", r.tnf.toInt())
                    .put("tnf_name", tnfName(r.tnf))
                    .put("type", r.type.toHexOrNull())
                    .put("type_text", r.type.toAscii())
                    .put("id", r.id.toHexOrNull())
                    .put("payload_len", r.payload.size)
                    .put("payload_hex", r.payload.toHexOrNull())
                    .put("decoded", decodeRecord(r)),
            )
        }
        return arr
    }

    private fun tnfName(tnf: Short): String = when (tnf.toInt()) {
        NdefRecord.TNF_EMPTY.toInt() -> "EMPTY"
        NdefRecord.TNF_WELL_KNOWN.toInt() -> "WELL_KNOWN"
        NdefRecord.TNF_MIME_MEDIA.toInt() -> "MIME_MEDIA"
        NdefRecord.TNF_ABSOLUTE_URI.toInt() -> "ABSOLUTE_URI"
        NdefRecord.TNF_EXTERNAL_TYPE.toInt() -> "EXTERNAL_TYPE"
        NdefRecord.TNF_UNKNOWN.toInt() -> "UNKNOWN"
        NdefRecord.TNF_UNCHANGED.toInt() -> "UNCHANGED"
        else -> "TNF $tnf"
    }

    /** Prefixos de URI da spec NFC Forum (RTD-URI). */
    private val URI_PREFIXES = arrayOf(
        "", "http://www.", "https://www.", "http://", "https://", "tel:", "mailto:",
        "ftp://anonymous:anonymous@", "ftp://ftp.", "ftps://", "sftp://", "smb://",
        "nfs://", "ftp://", "dav://", "news:", "telnet://", "imap:", "rtsp://",
        "urn:", "pop:", "sip:", "sips:", "tftp:", "btspp://", "btl2cap://",
        "btgoep://", "tcpobex://", "irdaobex://", "file://", "urn:epc:id:",
        "urn:epc:tag:", "urn:epc:pat:", "urn:epc:raw:", "urn:epc:", "urn:nfc:",
    )

    private fun decodeRecord(r: NdefRecord): String? = try {
        val p = r.payload
        when {
            p.isEmpty() -> null
            r.tnf == NdefRecord.TNF_WELL_KNOWN && r.type.contentEquals(NdefRecord.RTD_TEXT) -> {
                val status = p[0].toInt()
                val langLen = status and 0x3F
                val charset = if (status and 0x80 == 0) Charsets.UTF_8 else Charsets.UTF_16
                val lang = String(p, 1, langLen, Charsets.US_ASCII)
                "[$lang] " + String(p, 1 + langLen, p.size - 1 - langLen, charset)
            }
            r.tnf == NdefRecord.TNF_WELL_KNOWN && r.type.contentEquals(NdefRecord.RTD_URI) -> {
                val idx = p[0].toInt() and 0xFF
                URI_PREFIXES.getOrElse(idx) { "" } + String(p, 1, p.size - 1, Charsets.UTF_8)
            }
            r.tnf == NdefRecord.TNF_ABSOLUTE_URI -> String(r.type, Charsets.UTF_8)
            r.tnf == NdefRecord.TNF_MIME_MEDIA -> String(p, Charsets.UTF_8)
            r.tnf == NdefRecord.TNF_EXTERNAL_TYPE ->
                String(r.type, Charsets.UTF_8) + " → " + String(p, Charsets.UTF_8)
            else -> p.toAscii()
        }
    } catch (e: Exception) {
        null
    }

    // --------------------------------------------------- MIFARE Ultralight / NTAG

    private fun readUltralight(tag: Tag, json: JSONObject, errors: MutableList<String>) {
        val mu = MifareUltralight.get(tag) ?: return
        val o = JSONObject().put(
            "type",
            when (mu.type) {
                MifareUltralight.TYPE_ULTRALIGHT -> "ULTRALIGHT"
                MifareUltralight.TYPE_ULTRALIGHT_C -> "ULTRALIGHT_C"
                else -> "UNKNOWN"
            },
        )

        mu.connect()
        mu.timeout = 800
        try {
            // GET_VERSION / READ_SIG / READ_CNT não existem no Ultralight antigo:
            // quando não existem a tag corta a sessão, então reconectamos.
            var version: ByteArray? = null
            probe(mu, byteArrayOf(0x60))?.let {
                if (it.size >= 8) {
                    version = it
                    o.put("version", it.toHex())
                    o.put("version_decoded", describeVersion(it))
                }
            }
            probe(mu, byteArrayOf(0x3C, 0x00))?.let {
                if (it.size >= 16) o.put("signature", it.toHex())
            }
            probe(mu, byteArrayOf(0x39, 0x00))?.let {
                if (it.isNotEmpty()) o.put("counter", it.toHex())
            }

            val maxPages = pagesFromVersion(version) ?: when (mu.type) {
                MifareUltralight.TYPE_ULTRALIGHT_C -> 48
                MifareUltralight.TYPE_ULTRALIGHT -> 16
                else -> 20
            }

            val pages = JSONArray()
            var p = 0
            loop@ while (p < maxPages) {
                val chunk = try {
                    mu.readPages(p)
                } catch (e: IOException) {
                    errors += "MifareUltralight: parou na página $p (${e.javaClass.simpleName})"
                    break@loop
                }
                for (k in 0 until 4) {
                    val idx = p + k
                    if (idx >= maxPages) break@loop
                    pages.put(
                        JSONObject()
                            .put("index", idx)
                            .put("data", chunk.copyOfRange(k * 4, k * 4 + 4).toHex()),
                    )
                }
                p += 4
            }
            o.put("pages", pages)
            o.put("pages_read", pages.length())
            o.put("pages_expected", maxPages)
        } finally {
            runCatching { mu.close() }
        }
        json.put("mifare_ultralight", o)
    }

    /** Manda um comando cru; se a tag cortar a sessão, reconecta e devolve null. */
    private fun probe(mu: MifareUltralight, cmd: ByteArray): ByteArray? = try {
        mu.transceive(cmd)
    } catch (e: IOException) {
        runCatching {
            mu.close()
            mu.connect()
        }
        null
    }

    private fun pagesFromVersion(v: ByteArray?): Int? {
        if (v == null || v.size < 8) return null
        val product = v[2].toInt() and 0xFF
        val storage = v[6].toInt() and 0xFF
        return when {
            product == 0x04 && storage == 0x0F -> 45   // NTAG213
            product == 0x04 && storage == 0x11 -> 135  // NTAG215
            product == 0x04 && storage == 0x13 -> 231  // NTAG216
            product == 0x03 && storage == 0x0B -> 20   // Ultralight EV1 MF0UL11
            product == 0x03 && storage == 0x0E -> 41   // Ultralight EV1 MF0UL21
            else -> null
        }
    }

    private fun describeVersion(v: ByteArray?): String? {
        if (v == null || v.size < 8) return null
        val product = v[2].toInt() and 0xFF
        val storage = v[6].toInt() and 0xFF
        return when {
            product == 0x04 && storage == 0x0F -> "NTAG213 (144 bytes)"
            product == 0x04 && storage == 0x11 -> "NTAG215 (504 bytes)"
            product == 0x04 && storage == 0x13 -> "NTAG216 (888 bytes)"
            product == 0x03 && storage == 0x0B -> "MIFARE Ultralight EV1 MF0UL11 (48 bytes)"
            product == 0x03 && storage == 0x0E -> "MIFARE Ultralight EV1 MF0UL21 (128 bytes)"
            else -> null
        }
    }

    // ------------------------------------------------------------------- NfcV

    private fun readNfcV(tag: Tag, json: JSONObject) {
        val nfcv = NfcV.get(tag) ?: return
        val o = JSONObject()
            .put("dsf_id", String.format("%02X", nfcv.dsfId))
            .put("response_flags", String.format("%02X", nfcv.responseFlags))
            .put("max_transceive", nfcv.maxTransceiveLength)

        nfcv.connect()
        try {
            val blocks = JSONArray()
            val uid = tag.id ?: ByteArray(0)
            for (b in 0 until 64) {
                // 0x20 = flag "endereçado" + 0x20 = READ SINGLE BLOCK
                val addressed = byteArrayOf(0x20, 0x20) + uid + b.toByte()
                var resp = runCatching { nfcv.transceive(addressed) }.getOrNull()
                if (resp == null) {
                    runCatching {
                        nfcv.close()
                        nfcv.connect()
                    }
                    // segunda tentativa sem endereçar (tag única no campo)
                    resp = runCatching {
                        nfcv.transceive(byteArrayOf(0x02, 0x20, b.toByte()))
                    }.getOrNull()
                }
                if (resp == null || resp.isEmpty() || resp[0].toInt() != 0) break
                blocks.put(
                    JSONObject()
                        .put("index", b)
                        .put("data", resp.copyOfRange(1, resp.size).toHex()),
                )
            }
            o.put("blocks", blocks)
            o.put("blocks_read", blocks.length())
        } finally {
            runCatching { nfcv.close() }
        }
        json.put("nfcv", o)
    }

    // ----------------------------------------------------------------- IsoDep

    private fun readIsoDep(tag: Tag, json: JSONObject, deadline: Long) {
        val iso = IsoDep.get(tag) ?: return
        val o = JSONObject()
            .put("historical_bytes", iso.historicalBytes.toHexOrNull())
            .put("hi_layer_response", iso.hiLayerResponse.toHexOrNull())
            .put("max_transceive", iso.maxTransceiveLength)
            .put("extended_length_apdu_supported", iso.isExtendedLengthApduSupported)

        iso.connect()
        iso.timeout = 1500
        try {
            val probes = JSONArray()
            for (p in ApduProbes.ALL) {
                if (SystemClock.elapsedRealtime() > deadline) break
                val po = JSONObject()
                    .put("name", p.name)
                    .put("command", p.command.toHex())
                try {
                    val resp = iso.transceive(p.command)
                    val sw = if (resp.size >= 2) resp.copyOfRange(resp.size - 2, resp.size).toHex() else ""
                    val body = if (resp.size > 2) resp.copyOfRange(0, resp.size - 2) else ByteArray(0)
                    po.put("response", body.toHexOrNull())
                        .put("sw", sw)
                        .put("note", ApduProbes.explainSw(sw))
                    if (body.isNotEmpty()) {
                        po.put("response_ascii", body.toAscii())
                        val aids = ApduProbes.parseAids(body)
                        if (aids.isNotEmpty()) po.put("identificadores", JSONArray(aids))
                    }
                } catch (e: IOException) {
                    po.put("error", e.message ?: e.javaClass.simpleName)
                    runCatching {
                        iso.close()
                        iso.connect()
                    }
                }
                probes.put(po)
            }
            o.put("apdu_probes", probes)
        } finally {
            runCatching { iso.close() }
        }
        json.put("isodep", o)
    }

    // --------------------------------------------------------- MIFARE Classic

    private fun readMifareClassic(
        tag: Tag,
        json: JSONObject,
        errors: MutableList<String>,
        deadline: Long,
    ) {
        val mc = MifareClassic.get(tag) ?: return
        val o = JSONObject()
            .put(
                "type",
                when (mc.type) {
                    MifareClassic.TYPE_CLASSIC -> "CLASSIC"
                    MifareClassic.TYPE_PLUS -> "PLUS"
                    MifareClassic.TYPE_PRO -> "PRO"
                    else -> "UNKNOWN"
                },
            )
            .put("size", mc.size)
            .put("sector_count", mc.sectorCount)
            .put("block_count", mc.blockCount)

        mc.connect()
        mc.timeout = 700
        try {
            val sectors = JSONArray()
            var read = 0
            // Cartão real usa a mesma chave em todos os setores. Assim que uma
            // funciona, ela vira a primeira da fila e economizamos centenas de
            // autenticações falhas — cada falha custa uma reconexão.
            val chaves = MifareKeys.DEFAULT.toMutableList()

            for (s in 0 until mc.sectorCount) {
                if (SystemClock.elapsedRealtime() > deadline) {
                    errors += "MifareClassic: parou no setor $s (estourou o tempo)"
                    break
                }
                if (!garantirConexao(mc)) {
                    errors += "MifareClassic: perdeu a tag no setor $s"
                    break
                }
                val so = JSONObject().put("index", s)
                var keyLabel: String? = null
                var keyType: String? = null
                var keyBytes: ByteArray? = null

                for (par in chaves) {
                    val (label, key) = par
                    if (tryAuth(mc, s, key, true)) {
                        keyLabel = label; keyType = "A"; keyBytes = key
                    } else if (tryAuth(mc, s, key, false)) {
                        keyLabel = label; keyType = "B"; keyBytes = key
                    }
                    if (keyType != null) {
                        chaves.remove(par)
                        chaves.add(0, par)
                        break
                    }
                }

                if (keyType != null && keyBytes != null) {
                    val blocks = JSONArray()
                    val first = mc.sectorToBlock(s)
                    var invalidos = 0
                    for (b in 0 until mc.getBlockCountInSector(s)) {
                        var data = runCatching { mc.readBlock(first + b) }.getOrNull()
                        // Resposta fora dos 16 bytes = sessão perdida, não dado do
                        // cartão. Reautentica o setor e tenta esse bloco de novo.
                        if (data?.size != MifareClassic.BLOCK_SIZE) {
                            if (tryAuth(mc, s, keyBytes, keyType == "A")) {
                                data = runCatching { mc.readBlock(first + b) }.getOrNull()
                            }
                        }
                        if (data?.size == MifareClassic.BLOCK_SIZE) {
                            blocks.put(data.toHex())
                        } else {
                            invalidos++
                            blocks.put("")
                            if (data != null) {
                                so.put("resposta_crua_bloco_$b", data.toHex())
                            }
                        }
                    }
                    so.put("authenticated", true)
                        .put("key_type", keyType)
                        .put("key_used", keyLabel)
                        .put("first_block", first)
                        .put("blocks", blocks)
                    if (invalidos > 0) {
                        so.put("blocos_invalidos", invalidos)
                        errors += "MifareClassic: setor $s autenticou mas $invalidos bloco(s) " +
                            "vieram fora dos 16 bytes — sessão perdida no meio da leitura"
                    } else {
                        read++
                    }
                } else {
                    so.put("authenticated", false)
                        .put("error", "nenhuma das ${MifareKeys.DEFAULT.size} chaves de fábrica autenticou")
                }
                sectors.put(so)
            }
            o.put("sectors", sectors)
            o.put("sectors_read", read)
        } finally {
            runCatching { mc.close() }
        }
        json.put("mifare_classic", o)
    }

    /** MIFARE Classic vive caindo durante o dump; devolve false se a tag sumiu. */
    private fun garantirConexao(mc: MifareClassic): Boolean =
        if (mc.isConnected) true else runCatching { mc.connect() }.isSuccess

    private fun tryAuth(mc: MifareClassic, sector: Int, key: ByteArray, useKeyA: Boolean): Boolean {
        if (!garantirConexao(mc)) return false
        return try {
            if (useKeyA) mc.authenticateSectorWithKeyA(sector, key)
            else mc.authenticateSectorWithKeyB(sector, key)
        } catch (e: Exception) {
            // Chave errada derruba a sessão. Reconecta para a próxima tentativa —
            // e captura Exception, não só IOException: com a sessão morta o
            // Android lança IllegalStateException("Call connect() first!").
            runCatching { mc.close() }
            garantirConexao(mc)
            false
        }
    }

    // ------------------------------------------------------------ identificação

    private fun uidKind(id: ByteArray): String? = when (id.size) {
        4 -> "NUID de 4 bytes (sorteado na fabricação, não identifica fabricante)"
        7 -> "UID único de 7 bytes"
        10 -> "UID único de 10 bytes"
        else -> null
    }

    /**
     * Primeiro byte do UID = código do fabricante (ISO/IEC 7816-6) — mas isso só
     * vale para UID de 7 ou 10 bytes. Em UID de 4 bytes o valor é sorteado, e
     * ler fabricante ali é inventar informação.
     */
    private fun manufacturer(id: ByteArray): String? {
        if (id.size < 7) return null
        return when (id[0].toInt() and 0xFF) {
            0x02 -> "STMicroelectronics"
            0x04 -> "NXP Semiconductors"
            0x05 -> "Infineon"
            0x07 -> "Texas Instruments"
            0x08 -> "Fujitsu"
            0x0F -> "EM Microelectronic"
            0x16 -> "EM Microelectronic-Marin"
            0x1D -> "Kovio"
            0x21 -> "Sony"
            0x28 -> "Shanghai Fudan"
            0x2B -> "Shanghai Fudan"
            0x34 -> "GD Silicon"
            0x57 -> "Cardlogix"
            0x88 -> "Sony (FeliCa)"
            else -> null
        }
    }

    private fun guessType(techs: List<String>, json: JSONObject): String {
        val short = techs.map { it.substringAfterLast('.') }.toSet()

        json.optJSONObject("mifare_classic")?.let { mc ->
            val label = when (mc.optInt("size")) {
                320 -> "Mini"
                1024 -> "1K"
                2048 -> "2K"
                4096 -> "4K"
                else -> "${mc.optInt("size")} bytes"
            }
            return "MIFARE Classic $label"
        }

        json.optJSONObject("mifare_ultralight")?.let { mu ->
            mu.optString("version_decoded").takeIf { it.isNotBlank() }?.let { return it }
            return when (mu.optString("type")) {
                "ULTRALIGHT_C" -> "MIFARE Ultralight C"
                "ULTRALIGHT" -> "MIFARE Ultralight"
                else -> "NTAG / Ultralight (variante não identificada)"
            }
        }

        json.optJSONObject("isodep")?.let { iso ->
            val hist = iso.optString("historical_bytes").uppercase()
            val probes = iso.optJSONArray("apdu_probes")
            var ppseOk = false
            var ndefApp = false
            if (probes != null) {
                for (i in 0 until probes.length()) {
                    val p = probes.optJSONObject(i) ?: continue
                    if (p.optString("sw") != "9000") continue
                    if (p.optString("name").contains("PPSE")) ppseOk = true
                    if (p.optString("name").contains("NDEF")) ndefApp = true
                }
            }
            if (ppseOk) return "Cartão de pagamento sem contato (EMV)"
            if (hist.startsWith("7577810280")) return "MIFARE DESFire"
            if (ndefApp) return "Tag NFC Forum Type 4"
            if (short.contains("NfcB")) return "ISO 14443-4 Type B (smartcard)"
            return "ISO 14443-4 Type A (smartcard)"
        }

        if (json.has("nfcv")) return "ISO 15693 / NfcV"
        if (json.has("nfcf")) return "FeliCa (NfcF)"
        if (json.has("nfc_barcode")) return "NFC Barcode (Kovio)"
        if (json.optJSONObject("ndef") != null) return "Tag NDEF (${json.optJSONObject("ndef")?.optString("type")})"
        if (short.contains("NfcA")) return "ISO 14443-3 Type A"
        if (short.contains("NfcB")) return "ISO 14443-3 Type B"
        return "desconhecida"
    }
}
