package com.edusing.chamcongtvbox.service.web

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

/**
 * Máy chủ HTTP nội bộ siêu nhẹ (thuần Java `ServerSocket`, không cần thư viện ngoài) chạy trên cổng `8080`:
 * - Phục vụ trang quản trị di động/PC từ `assets/web_console.html` giúp điều khiển TV Box không màn hình.
 * - Giới hạn kích thước gói tin (32 KB) và timeout 8 giây để chống treo socket trên mạng LAN.
 */
internal class LocalWebConsoleServer(
    private val context: Context,
    private val apiHandler: WebConsoleApiHandler,
    private val hostProvider: () -> String = { MdnsHostnameResponder.DEFAULT_HOSTNAME },
    private val onHikvisionWebhook: (remoteIp: String, body: String) -> Boolean = { _, _ -> false },
    private val onPortBound: (Int) -> Unit = {},
    private val onListeningUrlChanged: (String) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mdnsResponder = MdnsHostnameResponder(context, hostProvider = hostProvider)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    @Volatile
    var boundPort: Int = 0
        private set

    private val dashboardHtml: String by lazy {
        runCatching {
            context.assets.open("web_console.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrDefault("<html><body><h3>Chấm công TV Box</h3></body></html>")
    }

    fun start() {
        if (acceptJob?.isActive == true) return
        acceptJob = scope.launch {
            val socket = bindFirstAvailablePort() ?: return@launch
            serverSocket = socket
            boundPort = socket.localPort
            onPortBound(boundPort)
            mdnsResponder.start(boundPort)
            refreshPublishedUrl()

            while (isActive && !socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    if (!isActive || socket.isClosed) break
                    continue
                }
                scope.launch { handleClient(client) }
            }
        }
    }

    fun refreshPublishedUrl() {
        val port = boundPort
        if (port <= 0) return
        val ip = detectLanIpv4()
        val host = hostProvider().trim().ifBlank { MdnsHostnameResponder.DEFAULT_HOSTNAME }
        val url = if (ip.isNotBlank()) "http://$host:$port" else ""
        onListeningUrlChanged(url)
        if (ip.isNotBlank()) mdnsResponder.refreshNetwork()
    }

    fun stop() {
        mdnsResponder.stop()
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        boundPort = 0
        onListeningUrlChanged("")
        scope.cancel()
    }

    private fun bindFirstAvailablePort(): ServerSocket? {
        for (port in CANDIDATE_PORTS) {
            val bound = runCatching {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(java.net.InetSocketAddress(port), 24)
                }
            }.getOrNull()
            if (bound != null) return bound
        }
        Log.w(TAG, "Không mở được cổng Web quản trị LAN")
        return null
    }

    private suspend fun handleClient(client: Socket) {
        try {
            client.soTimeout = CLIENT_TIMEOUT_MS
            val input = BufferedInputStream(client.getInputStream())
            val req = parseRequest(input) ?: return
            val response = when {
                req.path == "/" || req.path == "/index.html" -> WebHttpResponse(200, HTML_TYPE, dashboardHtml)
                req.path == "/hikvision/event" && req.method == "POST" -> {
                    val remoteIp = client.inetAddress?.hostAddress.orEmpty()
                    onHikvisionWebhook(remoteIp, req.body)
                    WebHttpResponse(200, "application/json; charset=utf-8", """{"status":"ok"}""")
                }
                req.path.startsWith("/api/") -> apiHandler.handle(req.method, req.path, req.token, req.body)
                else -> WebHttpResponse(404, "text/plain; charset=utf-8", "Not Found")
            }
            writeResponse(client, response)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            // Đóng kết nối nhẹ nhàng khi trình duyệt ngắt sớm
        } finally {
            runCatching { client.close() }
        }
    }

    private data class ParsedHttpRequest(val method: String, val path: String, val token: String?, val body: String)

    private fun parseRequest(input: BufferedInputStream): ParsedHttpRequest? {
        val headerBuf = ByteArrayOutputStream()
        var matched = 0
        while (headerBuf.size() < MAX_HEADER_BYTES) {
            val b = input.read()
            if (b == -1) return null
            headerBuf.write(b)
            matched = when {
                matched == 0 && b == '\r'.code -> 1
                matched == 1 && b == '\n'.code -> 2
                matched == 2 && b == '\r'.code -> 3
                matched == 3 && b == '\n'.code -> 4
                b == '\r'.code -> 1
                else -> 0
            }
            if (matched == 4) break
        }
        val lines = headerBuf.toString(Charsets.UTF_8.name()).split("\r\n")
        val firstParts = lines.firstOrNull()?.split(' ') ?: return null
        if (firstParts.size < 2) return null
        val method = firstParts[0].uppercase()
        val path = firstParts[1].substringBefore('?')

        var contentLength = 0
        var token: String? = null
        val bodyLimit = if (path == "/hikvision/event") MAX_WEBHOOK_BODY_BYTES else MAX_BODY_BYTES
        for (line in lines.drop(1)) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val k = line.substring(0, idx).trim().lowercase()
            val v = line.substring(idx + 1).trim()
            when (k) {
                "content-length" -> contentLength = v.toIntOrNull()?.coerceIn(0, bodyLimit) ?: 0
                "x-console-token" -> token = v
            }
        }
        val bodyBytes = ByteArray(contentLength)
        var readTotal = 0
        while (readTotal < contentLength) {
            val n = input.read(bodyBytes, readTotal, contentLength - readTotal)
            if (n <= 0) break
            readTotal += n
        }
        return ParsedHttpRequest(method, path, token, String(bodyBytes, 0, readTotal, Charsets.UTF_8))
    }

    private fun writeResponse(client: Socket, res: WebHttpResponse) {
        val bodyBytes = res.body.toByteArray(Charsets.UTF_8)
        val statusText = when (res.statusCode) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            else -> "Error"
        }
        val header = buildString {
            append("HTTP/1.1 ${res.statusCode} $statusText\r\n")
            append("Content-Type: ${res.contentType}\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        val out = client.getOutputStream()
        out.write(header.toByteArray(Charsets.US_ASCII))
        out.write(bodyBytes)
        out.flush()
    }

    companion object {
        private const val TAG = "LocalWebConsole"
        private const val CLIENT_TIMEOUT_MS = 8_000
        private const val MAX_HEADER_BYTES = 8_192
        private const val MAX_BODY_BYTES = 32_768
        private const val MAX_WEBHOOK_BODY_BYTES = 65_536
        private const val HTML_TYPE = "text/html; charset=utf-8"
        private val CANDIDATE_PORTS = listOf(8080, 8081, 8082, 8088)

        fun detectLanIpv4(): String = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .asSequence()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .filterNot { n ->
                    val name = n.name.lowercase()
                    name.startsWith("tun") || name.startsWith("wg") || name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("p2p")
                }
                .flatMap { it.interfaceAddresses.asSequence() }
                .mapNotNull { it.address as? Inet4Address }
                .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                ?.hostAddress.orEmpty()
        }.getOrDefault("")
    }
}
