package com.edusing.chamcongtvbox.presentation.push

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.edusing.chamcongtvbox.domain.model.BatchProgress
import com.edusing.chamcongtvbox.domain.repository.ConfigRepository
import com.edusing.chamcongtvbox.domain.usecase.AbbreviateNamesUseCase
import com.edusing.chamcongtvbox.domain.usecase.ExportMemberListDocUseCase
import com.edusing.chamcongtvbox.domain.usecase.LoadMembersTreeUseCase
import com.edusing.chamcongtvbox.domain.usecase.PushMembersToDevicesUseCase
import com.edusing.chamcongtvbox.service.AttendanceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel màn hình Đẩy danh sách xuống máy chấm công (chuyển thể từ `PushForm.cs`).
 * Luôn mặc định chọn 1 máy đơn đầu tiên và hiện cảnh báo đỏ khi người dùng chủ động chọn tất cả máy.
 */
class PushMembersViewModel(
    private val configRepository: ConfigRepository,
    private val attendanceEngine: AttendanceEngine,
    private val loadMembersTreeUseCase: LoadMembersTreeUseCase,
    private val pushMembersToDevicesUseCase: PushMembersToDevicesUseCase,
    private val abbreviateNamesUseCase: AbbreviateNamesUseCase,
    private val exportMemberListDocUseCase: ExportMemberListDocUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        PushMembersUiState(
            personLabel = configRepository.getBranding().person,
            groupLabel = configRepository.getBranding().group,
            maxNameBytes = configRepository.getConfig().maxDeviceNameBytes,
        )
    )
    val uiState: StateFlow<PushMembersUiState> = _uiState.asStateFlow()

    init {
        refreshTargetsAndLoad()
    }

    fun refreshTargetsAndLoad() {
        val branding = configRepository.getBranding()
        val connected = attendanceEngine.getConnectedHandles()
        val currentTarget = _uiState.value.selectedTargetKey
        // An toàn: luôn mặc định chọn máy ĐẦU TIÊN thay vì "Tất cả máy"
        val nextTarget = when {
            connected.isEmpty() -> null
            currentTarget == PushMembersUiState.TARGET_ALL_KEY && connected.size > 1 -> currentTarget
            connected.any { it.device.key == currentTarget } -> currentTarget
            else -> connected.first().device.key
        }
        updateAndRecompute {
            it.copy(
                personLabel = branding.person,
                groupLabel = branding.group,
                connectedDevices = connected,
                selectedTargetKey = nextTarget,
                maxNameBytes = configRepository.getConfig().maxDeviceNameBytes,
            )
        }
        loadDataForCurrentTarget()
    }

    fun selectTarget(targetKey: String) {
        if (_uiState.value.isPushing) return
        updateAndRecompute {
            it.copy(selectedTargetKey = targetKey, selectedCardIds = emptySet(), activeTab = PushListTab.NOT_ON_DEVICE, errorMessage = null)
        }
        loadDataForCurrentTarget()
    }

    fun selectTab(tab: PushListTab) = updateAndRecompute { it.copy(activeTab = tab) }
    fun onSearchQueryChange(query: String) = updateAndRecompute { it.copy(searchQuery = query) }

    fun toggleGroupExpanded(classId: Int) = updateAndRecompute { s ->
        val next = if (classId in s.collapsedGroupIds) s.collapsedGroupIds - classId else s.collapsedGroupIds + classId
        s.copy(collapsedGroupIds = next)
    }

    fun toggleMemberSelected(cardId: Int) = updateAndRecompute { s ->
        val next = if (cardId in s.selectedCardIds) s.selectedCardIds - cardId else s.selectedCardIds + cardId
        s.copy(selectedCardIds = next)
    }

    fun toggleGroupSelection(section: MemberGroupSection) {
        val groupIds = section.rows.mapNotNull { it.member.cardIdInt }.toSet()
        if (groupIds.isEmpty()) return
        updateAndRecompute { s ->
            val next = if (groupIds.all { it in s.selectedCardIds }) s.selectedCardIds - groupIds else s.selectedCardIds + groupIds
            s.copy(selectedCardIds = next)
        }
    }

    fun selectAllVisible() {
        val visibleIds = _uiState.value.visibleSections.flatMap { it.rows }.mapNotNull { it.member.cardIdInt }.toSet()
        updateAndRecompute { it.copy(selectedCardIds = it.selectedCardIds + visibleIds) }
    }

    fun clearSelection() = updateAndRecompute { it.copy(selectedCardIds = emptySet()) }

    fun requestPushSelected() {
        val s = _uiState.value
        if (s.isPushing || s.isLoading) return
        if (s.targetHandles.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Không có máy chấm công nào đang kết nối.") }
            return
        }
        if (s.selectedCardIds.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Hãy tick chọn ít nhất 1 ${configRepository.getBranding().personLower} để đẩy.") }
            return
        }
        if (s.isTargetAll && s.targetHandles.size > 1) {
            _uiState.update { it.copy(showPushAllWarningDialog = true) }
        } else {
            executePush()
        }
    }

    fun dismissPushAllWarning() = _uiState.update { it.copy(showPushAllWarningDialog = false) }
    fun confirmPushAll() { _uiState.update { it.copy(showPushAllWarningDialog = false) }; executePush() }
    fun dismissPushSummary() = _uiState.update { it.copy(pushSummary = null) }

    fun exportWordDocument() {
        val s = _uiState.value
        if (s.isExporting || s.allMembers.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isExporting = true, errorMessage = null, exportSuccessPath = null) }
            val chosen = if (s.selectedCardIds.isNotEmpty()) s.allMembers.filter { it.cardIdInt in s.selectedCardIds } else s.allMembers
            val cfg = configRepository.getConfig()
            val res = exportMemberListDocUseCase(
                orgName = cfg.orgName.ifBlank { cfg.username },
                classes = s.classes,
                members = chosen,
                existingDeviceNames = s.existingNamesOnTarget,
                maxNameBytes = cfg.maxDeviceNameBytes,
            )
            res.fold(
                onSuccess = { path -> _uiState.update { it.copy(isExporting = false, exportSuccessPath = path) } },
                onFailure = { err -> _uiState.update { it.copy(isExporting = false, errorMessage = "Xuất Word lỗi: ${err.message}") } },
            )
        }
    }

    private fun loadDataForCurrentTarget() {
        val handles = _uiState.value.targetHandles.map { it.handle }
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = loadMembersTreeUseCase(handles)
            res.fold(
                onSuccess = { tree ->
                    val maxBytes = configRepository.getConfig().maxDeviceNameBytes
                    val pushable = tree.members.filter { it.cardIdInt != null }
                    val shorts = abbreviateNamesUseCase.abbreviateBatch(pushable.map { it.name }, tree.existingNamesOnTarget, maxBytes)
                    val previewMap = LinkedHashMap<Int, String>(pushable.size).apply {
                        pushable.forEachIndexed { idx, m -> m.cardIdInt?.let { put(it, shorts[idx]) } }
                    }
                    updateAndRecompute {
                        it.copy(
                            isLoading = false,
                            maxNameBytes = maxBytes,
                            classes = tree.classes,
                            allMembers = tree.members,
                            existingCardIdsOnTarget = tree.existingCardIdsOnTarget,
                            existingNamesOnTarget = tree.existingNamesOnTarget,
                            previewShortNameByCard = previewMap,
                            selectedCardIds = emptySet(),
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = err.message ?: "Không tải được danh sách từ máy chủ.") }
                },
            )
        }
    }

    private fun executePush() {
        val s = _uiState.value
        val targets = s.targetHandles
        val chosen = s.allMembers.filter { it.cardIdInt != null && it.cardIdInt in s.selectedCardIds }
        if (targets.isEmpty() || chosen.isEmpty()) return

        // Nếu đang ở tab "Đã có trên máy" thì cho phép ghi đè cập nhật lại tên; ngược lại bỏ qua thẻ đã có
        val skipExisting = s.activeTab == PushListTab.NOT_ON_DEVICE

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(isPushing = true, errorMessage = null, progress = BatchProgress(0, targets.size * chosen.size, "Đang chuẩn bị đẩy dữ liệu..."))
            }
            val summary = attendanceEngine.withDeviceIoLock {
                pushMembersToDevicesUseCase(targets, chosen, skipExisting, configRepository.getConfig().maxDeviceNameBytes) { prog ->
                    _uiState.update { it.copy(progress = prog) }
                }
            }
            updateAndRecompute { it.copy(isPushing = false, progress = null, pushSummary = summary, selectedCardIds = emptySet()) }
            loadDataForCurrentTarget()
        }
    }

    private fun updateAndRecompute(transform: (PushMembersUiState) -> PushMembersUiState) {
        _uiState.update { prev ->
            val next = transform(prev)
            next.copy(visibleSections = buildSections(next))
        }
    }

    /** Lọc tiếng Việt không dấu theo tên, mã thẻ hoặc tên lớp/phòng ban. */
    private fun buildSections(s: PushMembersUiState): List<MemberGroupSection> {
        val classById = s.classes.associate { it.id to it.name }
        val key = abbreviateNamesUseCase.stripVietnameseDiacritics(s.searchQuery.trim()).lowercase()

        val filtered = s.allMembers.filter { m ->
            val id = m.cardIdInt
            val onDevice = id != null && id in s.existingCardIdsOnTarget
            val matchTab = if (s.activeTab == PushListTab.NOT_ON_DEVICE) !onDevice else onDevice
            if (!matchTab) return@filter false
            if (key.isEmpty()) true
            else {
                val cleanName = abbreviateNamesUseCase.stripVietnameseDiacritics(m.name).lowercase()
                val cleanClass = abbreviateNamesUseCase.stripVietnameseDiacritics(classById[m.classId].orEmpty()).lowercase()
                cleanName.contains(key) || m.cardId.contains(key) || m.rawCard.lowercase().contains(key) || cleanClass.contains(key)
            }
        }

        val grouped = filtered.groupBy { it.classId }
        val orderedIds = s.classes.map { it.id }.filter { grouped.containsKey(it) } +
            grouped.keys.filter { id -> s.classes.none { it.id == id } }.sorted()

        return orderedIds.map { cid ->
            val grpName = classById[cid] ?: "Chưa xếp ${s.groupLabel.lowercase()}"
            val rows = grouped[cid].orEmpty().map { m ->
                val id = m.cardIdInt
                MemberDisplayRow(
                    member = m,
                    className = grpName,
                    previewShortName = id?.let { s.previewShortNameByCard[it] }.orEmpty(),
                    isOnTargetDevice = id != null && id in s.existingCardIdsOnTarget,
                    isSelected = id != null && id in s.selectedCardIds,
                )
            }
            MemberGroupSection(cid, grpName, rows, isExpanded = cid !in s.collapsedGroupIds)
        }
    }
}
