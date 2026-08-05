package dev.nicollas.nfcint.nfc

private const val HEX = "0123456789ABCDEF"

fun ByteArray.toHex(separator: String = ""): String {
    val sb = StringBuilder(size * (2 + separator.length))
    for ((i, b) in withIndex()) {
        if (i > 0 && separator.isNotEmpty()) sb.append(separator)
        val v = b.toInt() and 0xFF
        sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
    }
    return sb.toString()
}

fun ByteArray?.toHexOrNull(separator: String = ""): String? =
    if (this == null || isEmpty()) null else toHex(separator)

fun String.hexToBytes(): ByteArray {
    val clean = filter { !it.isWhitespace() && it != ':' }
    require(clean.length % 2 == 0) { "hex com tamanho ímpar: $this" }
    return ByteArray(clean.length / 2) {
        clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }
}

/** Texto legível de um bloco de bytes: imprimíveis viram char, o resto vira ponto. */
fun ByteArray.toAscii(): String = map { b ->
    val v = b.toInt() and 0xFF
    if (v in 0x20..0x7E) v.toChar() else '.'
}.joinToString("")
