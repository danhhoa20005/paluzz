package com.edusing.chamcongtvbox.network

import com.edusing.chamcongtvbox.data.network.IcmpPinger
import com.edusing.chamcongtvbox.data.network.LanScannerImpl
import com.edusing.chamcongtvbox.domain.model.SubnetInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kiểm thử đơn vị cho [IcmpPinger] và [LanScannerImpl]:
 * - Gói ICMP Echo Request & checksum RFC 1071.
 * - Phát hiện lỗi giả exitCode=0 khi ColorOS/SELinux chặn ping (`Operation not permitted`).
 * - Chuẩn hoá dải `/24` và tự phát hiện dải LAN thượng nguồn (`192.168.2.x` -> `192.168.1.x`).
 */
class LanDiscoveryAndPingerTest {

    @Test
    fun icmpEchoRequest_hasValidTypeAndChecksum() {
        val pkt = IcmpPinger.buildIcmpEchoRequest(seq = 42)
        assertEquals(16, pkt.size)
        assertEquals(8, pkt[0].toInt() and 0xFF)
        assertEquals(0, pkt[1].toInt() and 0xFF)
        // Kiểm tra tính chất chuẩn RFC 1071: tính lại checksum trên gói đã gán checksum phải ra 0
        assertEquals(0, IcmpPinger.internetChecksum(pkt))
    }

    @Test
    fun icmpEchoReply_acceptsStrippedAndIpv4WrappedReplies() {
        val strippedReply = byteArrayOf(0, 0, 0x12, 0x34, 0, 1, 0, 1)
        assertTrue(IcmpPinger.isValidEchoReply(strippedReply, strippedReply.size))

        val echoReqNotReply = byteArrayOf(8, 0, 0x12, 0x34, 0, 1, 0, 1)
        assertFalse(IcmpPinger.isValidEchoReply(echoReqNotReply, echoReqNotReply.size))

        val ipv4Wrapped = ByteArray(28).apply {
            this[0] = 0x45.toByte() // IPv4, IHL = 20B
            this[20] = 0 // ICMP Echo Reply
        }
        assertTrue(IcmpPinger.isValidEchoReply(ipv4Wrapped, ipv4Wrapped.size))
    }

    @Test
    fun systemPingOutput_rejectsColorOsOperationNotPermittedEvenWithExitZero() {
        val colorOsBlocked = """
            ping: sendmsg: Operation not permitted
            PING 192.168.2.253 (192.168.2.253) 56(84) bytes of data.
            --- 192.168.2.253 ping statistics ---
            1 packets transmitted, 0 received, 100% packet loss, time 0ms
        """.trimIndent()
        assertFalse(IcmpPinger.isSuccessfulPingOutput(exitCode = 0, output = colorOsBlocked))

        val validReply = """
            PING 192.168.1.201 (192.168.1.201) 56(84) bytes of data.
            64 bytes from 192.168.1.201: icmp_seq=1 ttl=64 time=3.2 ms
            1 packets transmitted, 1 received, 0% packet loss, time 0ms
        """.trimIndent()
        assertTrue(IcmpPinger.isSuccessfulPingOutput(exitCode = 0, output = validReply))
        assertFalse(IcmpPinger.isSuccessfulPingOutput(exitCode = 1, output = validReply))
    }

    @Test
    fun normalizePrefix_parsesThreeOrFourOctets() {
        assertEquals("192.168.1", LanScannerImpl.normalizePrefix("192.168.1"))
        assertEquals("192.168.1", LanScannerImpl.normalizePrefix(" 192.168.1. "))
        assertEquals("192.168.1", LanScannerImpl.normalizePrefix("192.168.1.201"))
        assertNull(LanScannerImpl.normalizePrefix("192.168"))
        assertNull(LanScannerImpl.normalizePrefix("999.168.1"))
    }

    @Test
    fun detectRoutableExtraPrefixes_discoversUpstreamOfficeLanFromSecondaryWifi() = runTest {
        val scanner = LanScannerImpl(
            pinger = { _, _ -> false },
            gatewayChecker = { ip, _ -> ip == "192.168.1.1" },
        )
        val localSubnets = listOf(
            SubnetInfo("192.168.2.53", "192.168.2", "255.255.255.0", "192.168.2.1", "wlan0")
        )
        val extras = scanner.detectRoutableExtraPrefixes(localSubnets, knownIps = emptySet())
        assertEquals(listOf("192.168.1"), extras)
    }

    @Test
    fun scanSubnetByPing_returnsOnlyAliveHostsSortedByLastOctet() = runTest {
        val aliveSet = setOf("192.168.1.201", "192.168.1.1", "192.168.1.50")
        val scanner = LanScannerImpl(pinger = { ip, _ -> ip in aliveSet })
        val hosts = scanner.scanSubnetByPing(
            prefix = "192.168.1",
            localIp = "192.168.1.50",
            configuredIps = setOf("192.168.1.201"),
            port = 5005,
        )
        assertEquals(listOf("192.168.1.1", "192.168.1.201"), hosts.map { it.ip })
        assertTrue(hosts.last().alreadyConfigured)
    }
}
