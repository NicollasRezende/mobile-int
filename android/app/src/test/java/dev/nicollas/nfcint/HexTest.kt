package dev.nicollas.nfcint

import dev.nicollas.nfcint.nfc.ApduProbes
import dev.nicollas.nfcint.nfc.hexToBytes
import dev.nicollas.nfcint.nfc.toAscii
import dev.nicollas.nfcint.nfc.toHex
import org.junit.Assert.assertEquals
import org.junit.Test

class HexTest {

    @Test
    fun `hex ida e volta`() {
        val bytes = byteArrayOf(0x04, 0xA9.toByte(), 0x1B, 0x22, 0xC7.toByte())
        assertEquals("04A91B22C7", bytes.toHex())
        assertEquals("04:A9:1B:22:C7", bytes.toHex(":"))
        assertEquals(bytes.toList(), "04A91B22C7".hexToBytes().toList())
        assertEquals(bytes.toList(), "04:A9:1B:22:C7".hexToBytes().toList())
    }

    @Test
    fun `ascii esconde bytes nao imprimiveis`() {
        assertEquals("VISA", "56495341".hexToBytes().toAscii())
        assertEquals("..A.", byteArrayOf(0x00, 0x1F, 0x41, 0x7F).toAscii())
    }

    @Test
    fun `parseAids acha AID e label numa resposta de PPSE`() {
        val resposta = "6F124F07A00000000310105004564953 41".hexToBytes()
        assertEquals(listOf("AID A0000000031010", "label VISA"), ApduProbes.parseAids(resposta))
    }

    @Test
    fun `explainSw traduz os status mais comuns`() {
        assertEquals("OK", ApduProbes.explainSw("9000"))
        assertEquals("aplicação não encontrada", ApduProbes.explainSw("6A82"))
        assertEquals("OK, 28 bytes disponíveis", ApduProbes.explainSw("611C"))
        assertEquals("sem resposta", ApduProbes.explainSw(""))
    }
}
