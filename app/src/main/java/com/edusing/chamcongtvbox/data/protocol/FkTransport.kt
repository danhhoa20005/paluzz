package com.edusing.chamcongtvbox.data.protocol

import com.edusing.chamcongtvbox.domain.protocol.FkErrorCode
import java.io.Closeable

/** Phản hồi thô từ tầng truyền tải giao thức FK (`FKViaDev` hoặc `LFWViaDev`). */
data class FkTransportResponse(
    val resultCode: Int,
    val outParam: Int = 0,
    val data: ByteArray = ByteArray(0),
) {
    val isSuccess: Boolean get() = resultCode == FkErrorCode.SUCCESS
}

/**
 * Giao diện tầng đóng gói khung tin nhị phân cho máy chấm công FK.
 * Cài đặt cụ thể gồm [FkViaDevTransport] (chuẩn 16B/10B) và [LfwViaDevTransport] (Linux 20B/16B).
 */
interface FkTransport : Closeable {
    val transportName: String
    var maxBlockSize: Int

    fun sendCommand(
        machineNo: Int,
        cmd: Int,
        param: Int = 0,
        inParam: Int = 0,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): FkTransportResponse

    fun readData(
        machineNo: Int,
        cmd: Int,
        totalLength: Int,
        param: Int = 0,
        inParam: Int = 0,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): FkTransportResponse

    fun writeData(
        machineNo: Int,
        cmd: Int,
        payload: ByteArray,
        param: Int = 0,
        inParam: Int = 0,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): FkTransportResponse

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5_000
        const val DEFAULT_BLOCK_SIZE = 1024
        /** Khớp mã máy `FKAttend.dll @ 0x1000b638`: khi `commVersion == 0x103`, hạ chunk xuống 512B. */
        const val SMALL_BLOCK_SIZE = 512
    }
}
