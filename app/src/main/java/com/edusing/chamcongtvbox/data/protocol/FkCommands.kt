package com.edusing.chamcongtvbox.data.protocol

/**
 * Bảng mã lệnh (opcode) và chỉ số trạng thái chuẩn của giao thức FKAttend / LFW.
 * Đã được kiểm chứng trực tiếp từ mã máy của `FKAttend.dll`.
 */
object FkCommands {
    // Khởi tạo & điều khiển phiên
    const val GET_DEVICE_INFO = 0x13
    const val CONNECT_AUTH = 0x80
    const val ENABLE_DEVICE = 0x81

    // Quản lý dữ liệu người dùng & thẻ
    const val SAVE_ENROLL_DATA = 0x92
    const val DELETE_ENROLL_DATA = 0x93
    const val READ_ALL_USER_ID = 0x97
    const val PUT_ENROLL_DATA = 0x98
    const val LFW_GET_USER_INFO = 0x98
    const val LFW_SET_USER_INFO = 0x99
    const val LFW_PUT_ENROLL_DATA = 0x9B

    // Đọc & xoá nhật ký chấm công
    const val READ_UNREAD_GLOG = 0xA1
    const val MARK_GLOG_READ = 0xA2
    const val READ_ALL_GLOG = 0xA4
    const val EMPTY_GENERAL_LOG = 0xA8
    const val GET_DEVICE_STATUS = 0xB4

    // Đọc & ghi tên người dùng
    const val GET_USER_NAME = 0xC7
    const val SET_USER_NAME = 0xC8
    const val GET_USER_INFO = 0xE8
    const val SET_USER_INFO = 0xE9

    // Chỉ số truyền vào CMD_GET_DEVICE_STATUS (0xB4)
    const val STATUS_UNREAD_GLOG = 6
    const val STATUS_TOTAL_GLOG = 8
}
