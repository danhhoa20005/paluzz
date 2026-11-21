package com.edusing.chamcongtvbox.remote

import com.edusing.chamcongtvbox.data.remote.AttendanceApiClient
import com.edusing.chamcongtvbox.data.remote.HttpClientFactory
import com.edusing.chamcongtvbox.domain.model.BrandingConfig
import kotlinx.coroutines.test.runTest
import okhttp3.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.EOFException
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLHandshakeException

/** Kiểm thử cấu hình HTTP/1.1 + TLS 1.2, tự thử lại khi rớt socket và gửi đúng header giống `ApiClient.cs`. */
class HttpClientFactoryTest {

    private val brand = BrandingConfig(
        isOffice = false,
        isDonTra = false,
        brandHeader = "DIEMDANH",
        defaultApiDomain = "https://api.diemdanhhs.com",
        appTitle = "Test",
        loginTitle = "Test",
    )

    @Test
    fun primaryClient_usesHttp11AndTls12Specs() {
        val client = HttpClientFactory.createPrimaryClient()
        assertEquals(listOf(Protocol.HTTP_1_1), client.protocols)
        assertTrue(client.connectionSpecs.size >= 3)
    }

    @Test
    fun formatConnectionError_explainsDnsSslTimeoutClearly() {
        val dns = HttpClientFactory.formatConnectionError("https://api.diemdanhhs.com", UnknownHostException("api.diemdanhhs.com"))
        assertTrue(dns.contains("tên miền"))

        val ssl = HttpClientFactory.formatConnectionError("https://api.diemdanhhs.com", SSLHandshakeException("Trust anchor not found"))
        assertTrue(ssl.contains("HTTPS/SSL"))

        val timeout = HttpClientFactory.formatConnectionError("https://api.diemdanhhs.com", SocketTimeoutException("timeout"))
        assertTrue(timeout.contains("Hết thời gian"))

        val eof = HttpClientFactory.formatConnectionError("https://api.diemdanhhs.com", EOFException("unexpected end"))
        assertTrue(eof.contains("ngắt giữa chừng"))
    }

    @Test
    fun login_retriesOnDroppedFirstSocketAndSendsExactJsonContentType() = runTest {
        val attempts = AtomicInteger(0)
        val capturedContentType = AtomicReference("")
        val capturedBrand = AtomicReference("")

        ServerSocket(0).use { server ->
            val thread = Thread {
                // Lần 1: đóng socket đột ngột để kiểm tra cơ chế tự thử lại qua fallbackClient
                server.accept().use { s -> s.close() }
                attempts.incrementAndGet()

                // Lần 2: đọc header và trả JSON đăng nhập thành công
                server.accept().use { s ->
                    val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                    var line = reader.readLine()
                    while (!line.isNullOrEmpty()) {
                        if (line.startsWith("Content-Type:", ignoreCase = true)) capturedContentType.set(line.substringAfter(':').trim())
                        if (line.startsWith("x-brand:", ignoreCase = true)) capturedBrand.set(line.substringAfter(':').trim())
                        line = reader.readLine()
                    }
                    attempts.incrementAndGet()
                    val body = """{"data":{"accessToken":"tok-ok","account":{"id":"42","name":"Trường Test"}}}"""
                    val resp = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"
                    s.getOutputStream().write(resp.toByteArray())
                    s.getOutputStream().flush()
                }
            }.apply { isDaemon = true; start() }

            val api = AttendanceApiClient(brand, "1.0.0")
            val result = api.login("http://127.0.0.1:${server.localPort}", "school1", "pass1")
            thread.join(3000)

            assertTrue("Đăng nhập phải thành công sau khi retry: ${result.error}", result.success)
            assertEquals("42", result.accountId)
            assertEquals("Trường Test", result.orgName)
            assertEquals("application/json", capturedContentType.get())
            assertEquals("DIEMDANH", capturedBrand.get())
            assertEquals(2, attempts.get())
        }
    }
}
