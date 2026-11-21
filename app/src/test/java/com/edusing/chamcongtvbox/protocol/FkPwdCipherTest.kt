package com.edusing.chamcongtvbox.protocol

import com.edusing.chamcongtvbox.data.protocol.FkPwdCipher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FkPwdCipherTest {

    @Test
    fun encryptAndDecrypt_roundTripsAllBytesIncludingFixed32CardBuffer() {
        val original = ByteArray(32)
        val ascii = "1234567890".toByteArray(Charsets.US_ASCII)
        System.arraycopy(ascii, 0, original, 0, ascii.size)

        val encrypted = FkPwdCipher.encryptHs3760(original)
        val decrypted = FkPwdCipher.decryptHs3760(encrypted)

        assertArrayEquals(original, decrypted)
    }

    @Test
    fun encryptHs3760_matchesExactFormulaFromFkPwdEncDecDll() {
        // Verified directly against FKPwdEncDec.dll @ 0x10001030 (32-bit state):
        // Byte 0: state = 0x307F -> (state >>> 8) & 0xFF = 0x30.
        //   Plain '1' (0x31) -> cipher = 0x30 xor 0x31 = 0x01.
        //   Next state = (0x01 + 0x307F) * 0x70E8 + 0x8BAC = 0x3080 * 0x70E8 + 0x8BAC = 0x15647FAC.
        // Byte 1: (0x15647FAC >>> 8) & 0xFF = 0x7F.
        //   Plain '2' (0x32) -> cipher = 0x7F xor 0x32 = 0x4D.
        val enc = FkPwdCipher.encryptHs3760("12".toByteArray(Charsets.US_ASCII))
        assertEquals(0x01.toByte(), enc[0])
        assertEquals(0x4D.toByte(), enc[1])
    }

    @Test
    fun encryptCardStringFixed_padsWithNullsAndRoundTripsCleanly() {
        val enc = FkPwdCipher.encryptCardStringFixed("987654", 32)
        assertEquals(32, enc.size)
        val dec = FkPwdCipher.decryptHs3760(enc)
        val text = dec.takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.US_ASCII)
        assertEquals("987654", text)
    }
}
