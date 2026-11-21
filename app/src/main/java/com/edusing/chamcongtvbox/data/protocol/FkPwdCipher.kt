package com.edusing.chamcongtvbox.data.protocol

/**
 * Thuật toán mã hoá/giải mã mật khẩu và số thẻ `FKHS3760` viết thuần Kotlin.
 * Dịch ngược khớp từng lệnh x86 từ `FKPwdEncDec.dll` (`0x10001030` và `0x10001060`).
 */
object FkPwdCipher {

    private const val INITIAL_STATE = 0x307F
    private const val MULTIPLIER = 0x70E8
    private const val INCREMENT = 0x8BAC

    fun encryptHs3760(input: ByteArray): ByteArray {
        val out = ByteArray(input.size)
        var state = INITIAL_STATE
        for (i in input.indices) {
            val plain = input[i].toInt() and 0xFF
            val cipher = ((state ushr 8) xor plain) and 0xFF
            out[i] = cipher.toByte()
            state = (cipher + state) * MULTIPLIER + INCREMENT
        }
        return out
    }

    fun decryptHs3760(input: ByteArray): ByteArray {
        val out = ByteArray(input.size)
        var state = INITIAL_STATE
        for (i in input.indices) {
            val cipher = input[i].toInt() and 0xFF
            val plain = ((state ushr 8) xor cipher) and 0xFF
            out[i] = plain.toByte()
            state = (cipher + state) * MULTIPLIER + INCREMENT
        }
        return out
    }

    /** Mã hoá chuỗi số thẻ ASCII vào bộ đệm cố định (32B cho CIF11..15_1, 24B cho CIF10). */
    fun encryptCardStringFixed(cardText: String, bufferSize: Int): ByteArray {
        val plain = ByteArray(bufferSize)
        val ascii = cardText.toByteArray(Charsets.US_ASCII)
        System.arraycopy(ascii, 0, plain, 0, ascii.size.coerceAtMost(bufferSize - 1))
        return encryptHs3760(plain)
    }
}
