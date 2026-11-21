package com.edusing.chamcongtvbox.domain.model

import java.net.URI

/**
 * 3 chế độ sản phẩm hợp nhất trong 1 ứng dụng duy nhất (khớp tuyệt đối `Branding.cs` của `AppMayTinh`):
 * - [DIEM_DANH]: Trường học (`api.diemdanhhs.com`, header `DIEMDANH`)
 * - [PALUZZ]: Cơ quan / doanh nghiệp (`api.paluzz.com`, header `PALUZZ`)
 * - [DON_TRA]: Đón - trả học sinh (`api.dontrahs.com`, header `DONTRA`)
 */
enum class AppMode(
    val code: String,
    val shortLabel: String,
    val subtitle: String,
) {
    DIEM_DANH(
        code = "DIEMDANH",
        shortLabel = "Điểm danh HS",
        subtitle = "Trường học • api.diemdanhhs.com",
    ),
    PALUZZ(
        code = "PALUZZ",
        shortLabel = "PALUZZ Cơ quan",
        subtitle = "Cơ quan / DN • api.paluzz.com",
    ),
    DON_TRA(
        code = "DONTRA",
        shortLabel = "Đón trả HS",
        subtitle = "Đón trả • api.dontrahs.com",
    );

    companion object {
        fun fromCode(code: String?): AppMode = entries.firstOrNull {
            it.code.equals(code?.trim(), ignoreCase = true)
        } ?: PALUZZ
    }
}

/**
 * Cấu hình thương hiệu động theo [AppMode] đang bật (có thể chuyển chế độ tức thì trong app)
 * và bộ từ vựng hiển thị động (Trường/Lớp/Học sinh hoặc Đơn vị/Phòng ban/Nhân viên).
 */
data class BrandingConfig(
    val isOffice: Boolean,
    val isDonTra: Boolean,
    val brandHeader: String,
    val defaultApiDomain: String,
    val appTitle: String,
    val loginTitle: String,
    val mode: AppMode = when {
        isOffice -> AppMode.PALUZZ
        isDonTra -> AppMode.DON_TRA
        else -> AppMode.DIEM_DANH
    },
    val shortAppName: String = mode.shortLabel,
) {
    val org: String get() = if (isOffice) "Đơn vị" else "Trường"
    val orgLower: String get() = if (isOffice) "đơn vị" else "trường"
    val group: String get() = if (isOffice) "Phòng ban" else "Lớp"
    val groupLower: String get() = if (isOffice) "phòng ban" else "lớp"
    val person: String get() = if (isOffice) "Nhân viên" else "Học sinh"
    val personLower: String get() = if (isOffice) "nhân viên" else "học sinh"

    /**
     * Chuẩn hoá địa chỉ máy chủ API:
     * - Tự thêm `https://` nếu người dùng gõ thiếu giao thức.
     * - Chặn trường hợp cấu hình cũ trỏ nhầm sang tên miền của chế độ khác.
     */
    fun normalizeApiDomain(domain: String?): String {
        val raw = (domain ?: "").trim().trimEnd('/')
        if (raw.isEmpty()) return defaultApiDomain
        val withScheme = if (raw.startsWith("http://", ignoreCase = true) ||
            raw.startsWith("https://", ignoreCase = true)
        ) {
            raw
        } else {
            "https://$raw"
        }
        val ownHost = runCatching { URI(defaultApiDomain).host?.lowercase().orEmpty() }
            .getOrDefault("")
        val lower = withScheme.lowercase()
        for (knownHost in KNOWN_BRAND_HOSTS) {
            if (knownHost != ownHost && lower.contains(knownHost)) {
                return defaultApiDomain
            }
        }
        return withScheme
    }

    companion object {
        private val KNOWN_BRAND_HOSTS = listOf(
            "api.diemdanhhs.com",
            "api.paluzz.com",
            "api.dontrahs.com",
        )

        /** Tạo cấu hình thương hiệu khớp 100% `Branding.cs` trong `AppMayTinh` cho từng [AppMode]. */
        fun forMode(mode: AppMode): BrandingConfig = when (mode) {
            AppMode.PALUZZ -> BrandingConfig(
                isOffice = true,
                isDonTra = false,
                brandHeader = "PALUZZ",
                defaultApiDomain = "https://api.paluzz.com",
                appTitle = "Phần mềm quản lý điểm danh",
                loginTitle = "Phần mềm quản lý điểm danh PALUZZ",
                mode = AppMode.PALUZZ,
                shortAppName = "PALUZZ Chấm công",
            )
            AppMode.DON_TRA -> BrandingConfig(
                isOffice = false,
                isDonTra = true,
                brandHeader = "DONTRA",
                defaultApiDomain = "https://api.dontrahs.com",
                appTitle = "Phần mềm quản lý đón - trả học sinh",
                loginTitle = "Phần mềm quản lý đón - trả học sinh",
                mode = AppMode.DON_TRA,
                shortAppName = "Đón trả HS",
            )
            AppMode.DIEM_DANH -> BrandingConfig(
                isOffice = false,
                isDonTra = false,
                brandHeader = "DIEMDANH",
                defaultApiDomain = "https://api.diemdanhhs.com",
                appTitle = "Phần mềm quản lý điểm danh học sinh",
                loginTitle = "Phần mềm quản lý điểm danh học sinh",
                mode = AppMode.DIEM_DANH,
                shortAppName = "Điểm danh HS",
            )
        }
    }
}
