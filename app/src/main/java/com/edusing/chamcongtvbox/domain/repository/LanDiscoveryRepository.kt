package com.edusing.chamcongtvbox.domain.repository

import com.edusing.chamcongtvbox.domain.model.IpSuggestion
import com.edusing.chamcongtvbox.domain.model.LanHostInfo
import com.edusing.chamcongtvbox.domain.model.SubnetInfo

/** Giao diện dò mạng LAN nội bộ (đa dải mạng, Ping ICMP an toàn) và gợi ý IP tĩnh trống. */
interface LanDiscoveryRepository {

    /** Lấy danh sách toàn bộ card mạng LAN/Wi-Fi vật lý đang bật (ưu tiên giống `NetScan.cs`). */
    suspend fun detectActiveSubnets(preferredIps: Set<String> = emptySet()): List<SubnetInfo>

    /** Lấy card mạng LAN/Wi-Fi ưu tiên cao nhất. */
    suspend fun detectActiveSubnet(): SubnetInfo?

    /**
     * Tìm các dải `/24` khác có thể đi tới được (ví dụ máy đang ở Wi-Fi phụ `192.168.2.x`
     * nhưng thông sang mạng LAN chính `192.168.1.x` qua router, hoặc từ IP máy đã lưu).
     */
    suspend fun detectRoutableExtraPrefixes(
        localSubnets: List<SubnetInfo>,
        knownIps: Set<String> = emptySet(),
    ): List<String>

    /** Quét Ping ICMP an toàn trên đúng 1 dải `/24` (ví dụ `prefix = "192.168.1"`). */
    suspend fun scanSubnetByPing(
        prefix: String,
        localIp: String? = null,
        configuredIps: Set<String> = emptySet(),
        port: Int = 5005,
        onProgress: (scanned: Int, total: Int) -> Unit = { _, _ -> },
    ): List<LanHostInfo>

    /** Quét thiết bị đang bật trong mạng LAN (hoặc trên `customPrefix` nếu người dùng chỉ định). */
    suspend fun scanLanHosts(
        configuredIps: Set<String>,
        port: Int = 5005,
        customPrefix: String? = null,
        onProgress: (scanned: Int, total: Int) -> Unit = { _, _ -> },
    ): List<LanHostInfo>

    suspend fun suggestFreeIp(
        port: Int = 5005,
        excludeIps: Set<String> = emptySet(),
    ): IpSuggestion?
}
