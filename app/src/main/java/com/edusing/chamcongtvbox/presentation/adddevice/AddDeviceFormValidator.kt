package com.edusing.chamcongtvbox.presentation.adddevice

import com.edusing.chamcongtvbox.domain.model.AppConfig
import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.DeviceProtocol
import com.edusing.chamcongtvbox.domain.model.LanHostInfo

/** Bộ kiểm tra & tạo nhanh [DeviceConfig] từ biểu mẫu nhập tay hoặc thẻ thiết bị vừa quét được. */
internal object AddDeviceFormValidator {

    data class ParsedUrlInput(val host: String, val port: String, val useHttps: Boolean)

    fun parsePastedUrl(raw: String): ParsedUrlInput? {
        val clean = raw.trim()
        if (!clean.contains("://") && !clean.contains('/')) return null
        val isHttps = clean.startsWith("https://", ignoreCase = true)
        val authority = clean.substringAfter("://", clean).substringBefore('/').substringBefore('#').substringBefore('?')
        val host = authority.substringBefore(':').trim()
        val port = authority.substringAfter(':', "").filter(Char::isDigit).ifBlank { if (isHttps) "443" else "80" }
        return ParsedUrlInput(host, port, isHttps || port == "443")
    }

    fun validateForm(s: AddDeviceUiState, cfg: AppConfig): Pair<DeviceConfig?, String?> {
        val ip = s.ip.trim()
        val parts = ip.split('.')
        if (parts.size != 4 || !parts.all { it.toIntOrNull() in 0..255 }) {
            return null to "Địa chỉ IP không hợp lệ (ví dụ: 192.168.1.8)."
        }
        val portNum = s.port.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: return null to "Cổng không hợp lệ (Hikvision: 80/443, FK: 5005)."
        if (s.protocol == DeviceProtocol.HIKVISION_ISAPI && s.hikPassword.isBlank()) {
            return null to "Vui lòng nhập mật khẩu thiết bị Hikvision (chỉ nhập 1 lần)."
        }
        return DeviceConfig(
            name = s.name.trim(),
            ip = ip,
            port = portNum,
            machineNo = s.machineNo.toIntOrNull()?.takeIf { it >= 1 } ?: cfg.defaultMachineNo,
            password = s.commPassword.toIntOrNull()?.takeIf { it >= 0 } ?: cfg.defaultPassword,
            license = cfg.defaultLicense,
            useUdp = s.useUdp,
            protocol = s.protocol,
            username = s.hikUsername.trim().ifBlank { DeviceConfig.DEFAULT_HIK_USERNAME },
            accessPassword = s.hikPassword,
            useHttps = s.useHttps,
        ) to null
    }

    fun buildFromHost(
        host: LanHostInfo,
        cfg: AppConfig,
        hikUsername: String,
        hikPassword: String,
    ): DeviceConfig = if (host.isHikvisionTerminal) {
        val port = host.port.takeIf { it in 1..65535 } ?: DeviceConfig.DEFAULT_HIK_PORT
        DeviceConfig(
            name = host.modelHint.ifBlank { "Hikvision" },
            ip = host.ip,
            port = port,
            machineNo = cfg.defaultMachineNo,
            protocol = DeviceProtocol.HIKVISION_ISAPI,
            username = hikUsername.trim().ifBlank { cfg.resolvedHikUsername },
            accessPassword = hikPassword,
            useHttps = port == DeviceConfig.DEFAULT_HIK_HTTPS_PORT,
            serialNumber = host.serialHint,
        )
    } else {
        DeviceConfig(
            name = "",
            ip = host.ip,
            port = host.port.takeIf { it in 1..65535 } ?: cfg.defaultPort,
            machineNo = cfg.defaultMachineNo,
            password = cfg.defaultPassword,
            license = cfg.defaultLicense,
            useUdp = cfg.udpMode,
            protocol = DeviceProtocol.FK,
        )
    }
}
