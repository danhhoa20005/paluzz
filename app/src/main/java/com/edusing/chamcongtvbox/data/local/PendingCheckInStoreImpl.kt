package com.edusing.chamcongtvbox.data.local

import android.content.Context
import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.DeviceProtocol
import com.edusing.chamcongtvbox.domain.repository.PendingCheckInStorage
import com.edusing.chamcongtvbox.domain.repository.StoredPendingCheckIn
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lưu bền vững hàng đợi điểm danh ngoại tuyến xuống bộ nhớ flash (`commit()`),
 * đảm bảo không mất lượt quét mặt kể cả khi TV Box bị cúp điện ngay lúc đang đứt mạng WAN.
 */
class PendingCheckInStoreImpl(
    context: Context,
) : PendingCheckInStorage {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun loadAll(): List<StoredPendingCheckIn> = runCatching {
        val raw = prefs.getString(KEY_ITEMS, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        val arr = JSONArray(raw)
        buildList(arr.length()) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val cardId = o.optString("cardId", "").trim()
                val ip = o.optString("ip", "").trim()
                if (cardId.isEmpty() || ip.isEmpty()) continue
                add(
                    StoredPendingCheckIn(
                        cardId = cardId,
                        device = DeviceConfig(
                            ip = ip,
                            port = o.optInt("port", DeviceConfig.DEFAULT_PORT),
                            machineNo = o.optInt("machineNo", DeviceConfig.DEFAULT_MACHINE_NO),
                            name = o.optString("name", ""),
                            password = o.optInt("password", DeviceConfig.DEFAULT_PASSWORD),
                            license = o.optInt("license", DeviceConfig.DEFAULT_LICENSE),
                            useUdp = o.optBoolean("useUdp", false),
                            protocol = DeviceProtocol.fromCode(o.optString("protocol", "FK")),
                        ),
                        userName = o.optString("userName", ""),
                        queuedAtMs = o.optLong("queuedAtMs", System.currentTimeMillis()),
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    override fun saveAll(items: List<StoredPendingCheckIn>) {
        runCatching {
            val arr = JSONArray()
            items.forEach { r ->
                arr.put(
                    JSONObject().apply {
                        put("cardId", r.cardId)
                        put("ip", r.device.ip)
                        put("port", r.device.port)
                        put("machineNo", r.device.machineNo)
                        put("name", r.device.name)
                        put("password", r.device.password)
                        put("license", r.device.license)
                        put("useUdp", r.device.useUdp)
                        put("protocol", r.device.protocol.code)
                        put("userName", r.userName)
                        put("queuedAtMs", r.queuedAtMs)
                    }
                )
            }
            prefs.edit().putString(KEY_ITEMS, arr.toString()).commit()
        }
    }

    companion object {
        private const val PREFS_NAME = "chamcong_pending_checkins_v1"
        private const val KEY_ITEMS = "pending_items_json"
    }
}
