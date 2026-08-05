package dev.nicollas.nfcint.nfc

/**
 * Chaves de fábrica de MIFARE Classic. São as chaves públicas que vêm nos
 * cartões em branco e nos kits de desenvolvimento — servem para ler cartão
 * virgem/de teste. Cartão com chave própria simplesmente não autentica, e o
 * dump registra o setor como "não lido".
 */
object MifareKeys {

    val DEFAULT: List<Pair<String, ByteArray>> = listOf(
        "FFFFFFFFFFFF" to "FFFFFFFFFFFF",   // fábrica / cartão virgem
        "A0A1A2A3A4A5" to "A0A1A2A3A4A5",   // MAD (setor 0) padrão NXP
        "D3F7D3F7D3F7" to "D3F7D3F7D3F7",   // NDEF público
        "000000000000" to "000000000000",
        "B0B1B2B3B4B5" to "B0B1B2B3B4B5",
        "4D3A99C351DD" to "4D3A99C351DD",
        "1A982C7E459A" to "1A982C7E459A",
        "AABBCCDDEEFF" to "AABBCCDDEEFF",
        "714C5C886E97" to "714C5C886E97",
        "587EE5F9350F" to "587EE5F9350F",
        "A0478CC39091" to "A0478CC39091",
        "533CB6C723F6" to "533CB6C723F6",
        "8FD0A4F256E9" to "8FD0A4F256E9",
    ).map { (label, hex) -> label to hex.hexToBytes() }
}
