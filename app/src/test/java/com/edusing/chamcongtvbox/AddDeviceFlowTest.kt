package com.edusing.chamcongtvbox

import com.edusing.chamcongtvbox.domain.model.AppConfig
import com.edusing.chamcongtvbox.domain.model.DeviceProtocol
import com.edusing.chamcongtvbox.domain.model.LanHostInfo
import com.edusing.chamcongtvbox.presentation.adddevice.AddDeviceFormValidator
import com.edusing.chamcongtvbox.presentation.adddevice.AddDeviceUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddDeviceFlowTest {

    @Test
    fun `parsePastedUrl extracts IP, port, and HTTPS cleanly from Hikvision portal link`() {
        val https = AddDeviceFormValidator.parsePastedUrl("https://192.168.1.8/doc/index.html#/portal/login")
        assertNotNull(https)
        assertEquals("192.168.1.8", https!!.host)
        assertEquals("443", https.port)
        assertTrue(https.useHttps)

        val custom = AddDeviceFormValidator.parsePastedUrl("http://192.168.1.22:8080/isapi")
        assertNotNull(custom)
        assertEquals("192.168.1.22", custom!!.host)
        assertEquals("8080", custom.port)
        assertEquals(false, custom.useHttps)

        assertNull(AddDeviceFormValidator.parsePastedUrl("192.168.1.8"))
    }

    @Test
    fun `AddDeviceUiState separates detected attendance terminals from generic LAN hosts`() {
        val hosts = listOf(
            LanHostInfo("192.168.1.8", 80, isHikvisionTerminal = true, modelHint = "DS-K1T320MX"),
            LanHostInfo("192.168.1.1", 5005, reachableByPing = true),
            LanHostInfo("192.168.1.22", 5005, isFkTerminal = true),
        )
        val ui = AddDeviceUiState(discoveredHosts = hosts)
        assertEquals(2, ui.detectedTerminals.size)
        assertEquals(1, ui.otherLanHosts.size)
        assertEquals("192.168.1.1", ui.otherLanHosts[0].ip)
    }

    @Test
    fun `buildFromHost builds ready-to-connect DeviceConfig for both Hikvision and FK`() {
        val cfg = AppConfig(apiDomain = "api.paluzz.com", defaultHikUsername = "admin", defaultHikPassword = "SavedPassword")
        val hikHost = LanHostInfo("192.168.1.8", 80, isHikvisionTerminal = true, modelHint = "DS-K1T320MX", serialHint = "SN123")
        val hikDev = AddDeviceFormValidator.buildFromHost(hikHost, cfg, "admin", "SavedPassword")
        assertEquals(DeviceProtocol.HIKVISION_ISAPI, hikDev.protocol)
        assertEquals("DS-K1T320MX", hikDev.name)
        assertEquals("SN123", hikDev.serialNumber)
        assertEquals("SavedPassword", hikDev.accessPassword)

        val fkHost = LanHostInfo("192.168.1.50", 5005, isFkTerminal = true)
        val fkDev = AddDeviceFormValidator.buildFromHost(fkHost, cfg, "", "")
        assertEquals(DeviceProtocol.FK, fkDev.protocol)
        assertEquals(5005, fkDev.port)
    }
}
