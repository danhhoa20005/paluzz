package com.edusing.chamcongtvbox.domain

import com.edusing.chamcongtvbox.domain.usecase.AbbreviateNamesUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AbbreviateNamesUseCaseTest {

    private val useCase = AbbreviateNamesUseCase()

    @Test
    fun stripVietnameseDiacritics_handlesAllAccentsAndSpecialD() {
        assertEquals("Dang Thi Phuong Anh", useCase.stripVietnameseDiacritics("Đặng Thị Phương Ánh"))
        assertEquals("duong duc do", useCase.stripVietnameseDiacritics("đường đức độ"))
    }

    @Test
    fun abbreviateSingle_producesDotNotationWithin6Bytes() {
        assertEquals("NT.P", useCase.abbreviateSingle("Nguyễn Thị Phương"))
        assertEquals("DTA.K", useCase.abbreviateSingle("Đặng Trần Anh Khoa"))
        // 5-word name: caps leading initials at 3 -> "NTH.L" (5 bytes)
        assertEquals("NTH.L", useCase.abbreviateSingle("Nguyễn Thị Hoàng Khánh Linh"))
        // Single-word name truncated to 6 bytes
        assertEquals("Phuong", useCase.abbreviateSingle("Phương"))
        assertEquals("Nguyet", useCase.abbreviateSingle("NguyệtÁnh"))
    }

    @Test
    fun abbreviateBatch_resolvesCollisionsWithExistingNamesAndWithin6Bytes() {
        val names = listOf(
            "Nguyễn Thị Phương",
            "Ngô Thanh Phúc",
            "Nguyễn Tuấn Phát",
            "Nguyễn Thị Hoàng Khánh Linh",
            "Nguyễn Trần Hùng Kim Long",
        )
        val out = useCase.abbreviateBatch(names, existingNamesOnDevice = listOf("NT.P"))

        // NT.P already exists on device -> first becomes NT.P2, second NT.P3, third NT.P4
        assertEquals("NT.P2", out[0])
        assertEquals("NT.P3", out[1])
        assertEquals("NT.P4", out[2])

        // 5-word duplicate: first gets NTH.L (5B), second gets NTH.L2 (6B)
        assertEquals("NTH.L", out[3])
        assertEquals("NTH.L2", out[4])

        // Every abbreviated name MUST be <= 6 UTF-8 bytes and unique
        assertTrue(out.all { it.toByteArray(Charsets.UTF_8).size <= AbbreviateNamesUseCase.MAX_NAME_BYTES })
        assertEquals(out.size, out.map { it.lowercase() }.toSet().size)
    }
}
