package com.edusing.chamcongtvbox.domain.usecase

import java.text.Normalizer

/**
 * Rút gọn họ tên tiếng Việt không dấu vừa giới hạn byte của máy chấm công (mặc định 6 byte
 * đo thực tế trên EDS3000-TWM, có thể tăng lên 10 byte ở màn Cài đặt cho model hỗ trợ dài hơn).
 *
 * Ví dụ: `"Nguyễn Thị Phương"` -> `"NT.P"`; người trùng tên tiếp theo -> `"NT.P2"`, `"NT.P3"`.
 */
class AbbreviateNamesUseCase {

    fun abbreviateSingle(fullName: String, maxNameBytes: Int = MAX_NAME_BYTES): String {
        val limit = maxNameBytes.coerceIn(4, 16)
        if (fullName.isBlank()) return ""
        val parts = splitWords(fullName)
        if (parts.isEmpty()) return ""
        if (parts.size == 1) return truncateUtf8Bytes(capitalizeWord(parts[0]), limit)
        return joinParts(parts, maxInitials = 3, maxBytes = limit)
    }

    /**
     * Rút gọn hàng loạt cho cả danh sách, đảm bảo không trùng nhau và không trùng với
     * [existingNamesOnDevice] đang có sẵn trên máy.
     */
    fun abbreviateBatch(
        fullNames: List<String>,
        existingNamesOnDevice: Collection<String> = emptySet(),
        maxNameBytes: Int = MAX_NAME_BYTES,
    ): List<String> {
        val limit = maxNameBytes.coerceIn(4, 16)
        val used = HashSet<String>()
        for (existing in existingNamesOnDevice) {
            if (existing.isNotEmpty()) used.add(existing.lowercase())
        }

        val result = ArrayList<String>(fullNames.size)
        for (full in fullNames) {
            val base = abbreviateSingle(full, limit)
            if (base.isEmpty()) {
                result.add("")
                continue
            }
            if (used.add(base.lowercase())) {
                result.add(base)
                continue
            }

            val parts = splitWords(full)
            var chosen: String? = null
            for (index in 2..999) {
                val suffix = index.toString()
                var candidate: String? = null
                // Giữ tối đa số chữ cái đầu của họ+đệm mà vẫn đủ chỗ cho hậu tố số
                for (nInit in 3 downTo 1) {
                    val stem = if (parts.size == 1) capitalizeWord(parts[0]) else joinParts(parts, nInit, limit)
                    val attempt = stem + suffix
                    if (utf8ByteCount(attempt) <= limit) {
                        candidate = attempt
                        break
                    }
                }
                if (candidate == null) {
                    val maxStem = (limit - suffix.length).coerceAtLeast(1)
                    candidate = truncateUtf8Bytes(base, maxStem) + suffix
                }
                if (!used.contains(candidate.lowercase())) {
                    chosen = candidate
                    break
                }
            }
            val finalName = chosen ?: base
            used.add(finalName.lowercase())
            result.add(finalName)
        }
        return result
    }

    /** Bỏ dấu tiếng Việt (kể cả `Đ/đ`), dùng cho cả tên máy chấm và tìm kiếm không dấu. */
    fun stripVietnameseDiacritics(input: String): String {
        if (input.isEmpty()) return ""
        val replaced = input.replace('đ', 'd').replace('Đ', 'D')
        val normalized = Normalizer.normalize(replaced, Normalizer.Form.NFD)
        val sb = StringBuilder(normalized.length)
        for (ch in normalized) {
            if (Character.getType(ch) != Character.NON_SPACING_MARK.toInt()) {
                sb.append(ch)
            }
        }
        return Normalizer.normalize(sb.toString(), Normalizer.Form.NFC)
    }

    private fun splitWords(full: String): List<String> =
        stripVietnameseDiacritics(full.trim()).split(WHITESPACE_REGEX).filter { it.isNotEmpty() }

    private fun joinParts(parts: List<String>, maxInitials: Int, maxBytes: Int): String {
        val sb = StringBuilder()
        val lastIdx = parts.size - 1
        var i = 0
        while (i < lastIdx && sb.length < maxInitials) {
            sb.append(parts[i][0].uppercaseChar())
            i++
        }
        sb.append('.')
        sb.append(parts[lastIdx][0].uppercaseChar())
        return truncateUtf8Bytes(sb.toString(), maxBytes)
    }

    private fun capitalizeWord(word: String): String {
        if (word.isEmpty()) return ""
        if (word.length == 1) return word.uppercase()
        return word[0].uppercaseChar() + word.substring(1).lowercase()
    }

    private fun utf8ByteCount(s: String): Int = s.toByteArray(Charsets.UTF_8).size

    private fun truncateUtf8Bytes(s: String, maxBytes: Int): String {
        if (s.isEmpty() || maxBytes <= 0) return ""
        if (utf8ByteCount(s) <= maxBytes) return s
        val sb = StringBuilder()
        var used = 0
        for (ch in s) {
            val bytes = ch.toString().toByteArray(Charsets.UTF_8).size
            if (used + bytes > maxBytes) break
            sb.append(ch)
            used += bytes
        }
        return sb.toString()
    }

    companion object {
        const val MAX_NAME_BYTES = 6
        private val WHITESPACE_REGEX = Regex("[\\s]+")
    }
}
