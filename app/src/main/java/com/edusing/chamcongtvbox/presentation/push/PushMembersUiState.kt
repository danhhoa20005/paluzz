package com.edusing.chamcongtvbox.presentation.push

import com.edusing.chamcongtvbox.domain.model.BatchProgress
import com.edusing.chamcongtvbox.domain.model.ClassGroup
import com.edusing.chamcongtvbox.domain.model.Member
import com.edusing.chamcongtvbox.domain.model.PushResultSummary
import com.edusing.chamcongtvbox.domain.usecase.ConnectedDeviceHandle

/** Tab phân loại người dùng theo trạng thái hiện có trên máy chấm công đích. */
enum class PushListTab {
    NOT_ON_DEVICE,
    ON_DEVICE,
}

/** Một dòng thành viên hiển thị trong nhóm Lớp/Phòng ban. */
data class MemberDisplayRow(
    val member: Member,
    val className: String,
    val previewShortName: String,
    val isOnTargetDevice: Boolean,
    val isSelected: Boolean,
)

/** Một nhóm Lớp/Phòng ban có thể đóng mở và chọn nhanh toàn bộ thành viên. */
data class MemberGroupSection(
    val classId: Int,
    val groupName: String,
    val rows: List<MemberDisplayRow>,
    val isExpanded: Boolean,
) {
    val pushableCount: Int get() = rows.count { it.member.isPushable }
    val selectedCount: Int get() = rows.count { it.isSelected }
    val allPushableSelected: Boolean get() = pushableCount > 0 && selectedCount == pushableCount
}

/** Trạng thái UI toàn diện cho màn hình Đẩy danh sách xuống máy chấm công. */
data class PushMembersUiState(
    val personLabel: String = "Học sinh",
    val groupLabel: String = "Lớp",
    val maxNameBytes: Int = 6,
    val connectedDevices: List<ConnectedDeviceHandle> = emptyList(),
    val selectedTargetKey: String? = null,
    val activeTab: PushListTab = PushListTab.NOT_ON_DEVICE,
    val searchQuery: String = "",
    val isLoading: Boolean = false,
    val isPushing: Boolean = false,
    val isExporting: Boolean = false,
    val progress: BatchProgress? = null,
    val errorMessage: String? = null,
    val exportSuccessPath: String? = null,
    val showPushAllWarningDialog: Boolean = false,
    val pushSummary: PushResultSummary? = null,
    val classes: List<ClassGroup> = emptyList(),
    val allMembers: List<Member> = emptyList(),
    val existingCardIdsOnTarget: Set<Int> = emptySet(),
    val existingNamesOnTarget: Set<String> = emptySet(),
    val previewShortNameByCard: Map<Int, String> = emptyMap(),
    val selectedCardIds: Set<Int> = emptySet(),
    val collapsedGroupIds: Set<Int> = emptySet(),
    val visibleSections: List<MemberGroupSection> = emptyList(),
) {
    val isTargetAll: Boolean get() = selectedTargetKey == TARGET_ALL_KEY

    val targetHandles: List<ConnectedDeviceHandle>
        get() = if (isTargetAll) connectedDevices else connectedDevices.filter { it.device.key == selectedTargetKey }

    /** Nhãn nút đẩy ghi rõ tên máy đích (đỏ khi đẩy nhiều máy, xanh khi đẩy 1 máy). */
    val pushButtonLabel: String
        get() {
            val count = selectedCardIds.size
            val targets = targetHandles
            return when {
                targets.size > 1 -> "ĐẨY XUỐNG TẤT CẢ ${targets.size} MÁY ($count)"
                targets.size == 1 -> "ĐẨY XUỐNG ${targets.first().device.displayName} ($count)"
                else -> "ĐẨY XUỐNG MÁY ($count)"
            }
        }

    val notOnDeviceCount: Int
        get() = allMembers.count { m -> val id = m.cardIdInt; id == null || id !in existingCardIdsOnTarget }

    val onDeviceCount: Int
        get() = allMembers.count { m -> val id = m.cardIdInt; id != null && id in existingCardIdsOnTarget }

    companion object {
        const val TARGET_ALL_KEY = "__ALL_DEVICES__"
    }
}
