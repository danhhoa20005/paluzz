package com.edusing.chamcongtvbox.domain.repository

import com.edusing.chamcongtvbox.domain.model.DeviceConfig

/** Bản ghi điểm danh ngoại tuyến dùng cho lưu bền vững chống mất khi TV Box sập điện giữa lúc đứt mạng WAN. */
data class StoredPendingCheckIn(
    val cardId: String,
    val device: DeviceConfig,
    val userName: String,
    val queuedAtMs: Long,
)

/** Giao diện thuần Kotlin lưu trữ hàng đợi chấm công ngoại tuyến xuống bộ nhớ flash. */
interface PendingCheckInStorage {
    fun loadAll(): List<StoredPendingCheckIn> = emptyList()
    fun saveAll(items: List<StoredPendingCheckIn>) = Unit

    object NoOp : PendingCheckInStorage
}
