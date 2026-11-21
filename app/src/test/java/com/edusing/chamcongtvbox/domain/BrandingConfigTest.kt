package com.edusing.chamcongtvbox.domain

import com.edusing.chamcongtvbox.domain.model.AppMode
import com.edusing.chamcongtvbox.domain.model.BrandingConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandingConfigTest {

    private val schoolBrand = BrandingConfig.forMode(AppMode.DIEM_DANH)
    private val officeBrand = BrandingConfig.forMode(AppMode.PALUZZ)
    private val donTraBrand = BrandingConfig.forMode(AppMode.DON_TRA)

    @Test
    fun forMode_matchesAppMayTinhBrandingCsExactly() {
        assertEquals("DIEMDANH", schoolBrand.brandHeader)
        assertEquals("https://api.diemdanhhs.com", schoolBrand.defaultApiDomain)
        assertFalse(schoolBrand.isOffice)
        assertFalse(schoolBrand.isDonTra)

        assertEquals("PALUZZ", officeBrand.brandHeader)
        assertEquals("https://api.paluzz.com", officeBrand.defaultApiDomain)
        assertTrue(officeBrand.isOffice)
        assertFalse(officeBrand.isDonTra)

        assertEquals("DONTRA", donTraBrand.brandHeader)
        assertEquals("https://api.dontrahs.com", donTraBrand.defaultApiDomain)
        assertFalse(donTraBrand.isOffice)
        assertTrue(donTraBrand.isDonTra)

        assertEquals(AppMode.DIEM_DANH, AppMode.fromCode("DIEMDANH"))
        assertEquals(AppMode.PALUZZ, AppMode.fromCode("paluzz"))
        assertEquals(AppMode.DON_TRA, AppMode.fromCode("DONTRA"))
        assertEquals(AppMode.PALUZZ, AppMode.fromCode("unknown"))
    }

    @Test
    fun vocabularySwitchesBetweenSchoolAndOffice() {
        assertEquals("Trường", schoolBrand.org)
        assertEquals("Lớp", schoolBrand.group)
        assertEquals("Học sinh", schoolBrand.person)

        assertEquals("Đơn vị", officeBrand.org)
        assertEquals("Phòng ban", officeBrand.group)
        assertEquals("Nhân viên", officeBrand.person)

        assertEquals("Trường", donTraBrand.org)
        assertEquals("Lớp", donTraBrand.group)
        assertEquals("Học sinh", donTraBrand.person)
    }

    @Test
    fun normalizeApiDomain_blocksCrossBrandDomainsAndAllowsOwnOrCustom() {
        assertEquals("https://api.diemdanhhs.com", schoolBrand.normalizeApiDomain(""))
        assertEquals("https://api.diemdanhhs.com", schoolBrand.normalizeApiDomain("https://api.paluzz.com"))
        assertEquals("https://api.diemdanhhs.com", schoolBrand.normalizeApiDomain("https://api.dontrahs.com/"))
        assertEquals("https://api.diemdanhhs.com", schoolBrand.normalizeApiDomain("https://api.diemdanhhs.com/"))
        assertEquals("https://api.diemdanhhs.com", schoolBrand.normalizeApiDomain("api.diemdanhhs.com"))
        assertEquals("https://staging.example.com", schoolBrand.normalizeApiDomain("https://staging.example.com/"))
        assertEquals("https://staging.example.com", schoolBrand.normalizeApiDomain("staging.example.com/"))

        assertEquals("https://api.paluzz.com", officeBrand.normalizeApiDomain("https://api.diemdanhhs.com"))
        assertEquals("https://api.paluzz.com", officeBrand.normalizeApiDomain("https://api.paluzz.com"))

        assertEquals("https://api.dontrahs.com", donTraBrand.normalizeApiDomain("https://api.paluzz.com"))
        assertEquals("https://api.dontrahs.com", donTraBrand.normalizeApiDomain("https://api.dontrahs.com"))
    }
}
