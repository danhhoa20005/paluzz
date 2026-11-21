package com.edusing.chamcongtvbox.domain.protocol

import com.edusing.chamcongtvbox.domain.model.AttendanceLog
import com.edusing.chamcongtvbox.domain.model.DeviceConfig

/**
 * Cổng giao tiếp máy chấm công tầng Domain (thay thế hoàn toàn P/Invoke trong `FKAttend.cs`).
 * Tầng Data cung cấp bản cài đặt thuần Kotlin hỗ trợ cả TCP và UDP (FKViaDev + LFWViaDev).
 */
interface FkDeviceGateway {

    /** Mở kết nối tới máy chấm công (hỗ trợ TCP và UDP, tự fallback sang LFW nếu cần). */
    suspend fun connect(
        machineNo: Int,
        ip: String,
        port: Int,
        timeoutMs: Int = 5_000,
        netPassword: Int = 0,
        license: Int = DeviceConfig.DEFAULT_LICENSE,
        useUdp: Boolean = false,
    ): FkResult<FkConnectionInfo>

    /** Mở kết nối từ cấu hình đầy đủ (tự định tuyến máy FK cổng 5005 hoặc Hikvision ISAPI cổng 80/443). */
    suspend fun connect(
        device: DeviceConfig,
        timeoutMs: Int = 5_000,
    ): FkResult<FkConnectionInfo> = connect(
        machineNo = device.machineNo,
        ip = device.ip,
        port = device.port,
        timeoutMs = timeoutMs,
        netPassword = device.password,
        license = device.license,
        useUdp = device.useUdp,
    )

    /** Dò nhanh không cần mật khẩu xem IP có chạy giao thức Hikvision ISAPI (DS-K1T320MX...) hay không. */
    suspend fun probeHikvisionHost(ip: String, port: Int = DeviceConfig.DEFAULT_HIK_PORT): String? = null

    /** Ngắt kết nối một phiên máy chấm công theo handle. */
    suspend fun disconnect(handle: Int)

    /** Đóng sạch toàn bộ các phiên đang mở. */
    suspend fun disconnectAll()

    /** Khoá (`enable = false`) hoặc mở lại (`enable = true`) máy chấm công khi đọc/ghi dữ liệu. */
    suspend fun enableDevice(handle: Int, enable: Boolean): FkResult<Unit>

    /** Tải danh sách lượt chấm công (`readMark = true`: chỉ đọc lượt mới và đánh dấu đã đọc). */
    suspend fun loadGeneralLogData(handle: Int, readMark: Boolean): FkResult<List<AttendanceLog>>

    /** Xoá sạch toàn bộ bộ nhớ log chấm công trên máy. */
    suspend fun emptyGeneralLogData(handle: Int): FkResult<Unit>

    /** Đọc danh sách toàn bộ mã người dùng đang lưu trong máy. */
    suspend fun readAllUserIds(handle: Int): FkResult<List<FkUserCredential>>

    /** Ghi thông tin thẻ (`backupNumber = 11`) cho một người dùng xuống máy. */
    suspend fun putCardEnrollData(
        handle: Int,
        enrollNumber: Int,
        cardNumber: Int = enrollNumber,
        privilege: Int = 0,
    ): FkResult<Unit>

    /** Lưu dữ liệu người dùng vừa ghi từ RAM xuống bộ nhớ Flash của máy. */
    suspend fun saveEnrollData(handle: Int): FkResult<Unit>

    /** Xoá một loại dữ liệu xác thực (`backupNumber` 0..12) của người dùng trên máy. */
    suspend fun deleteEnrollData(handle: Int, enrollNumber: Int, backupNumber: Int): FkResult<Unit>

    /** Đọc tên hiển thị đang lưu trên máy chấm công của một mã thẻ. */
    suspend fun getUserName(handle: Int, enrollNumber: Int): FkResult<String>

    /** Ghi tên hiển thị (rút gọn không dấu) cho một mã thẻ trên máy chấm công. */
    suspend fun setUserName(handle: Int, enrollNumber: Int, name: String): FkResult<Unit>

    /** Lấy thông tin kết nối hiện hành nếu handle còn đang mở. */
    fun getConnectionInfo(handle: Int): FkConnectionInfo?
}
