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
 * ViewModel màn hình Đẩy danh sách xuống máy chấm công (hỗ trợ cả FK 6-byte và Hikvision UTF-8 / nguyên mã gốc).
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

    init { refreshTargetsAndLoad() }

    fun refreshTargetsAndLoad() {
        val branding = configRepository.getBranding()
        val connected = attendanceEngine.getConnectedHandles()
        val currentTarget = _uiState.value.selectedTargetKey
        val nextTarget = when {
            connected.isEmpty() -> null
            currentTarget == PushMembersUiState.TARGET_ALL_KEY && connected.size > 1 -> currentTarget
            connected.any { it.device.key == currentTarget } -> currentTarget
            else -> connected.first().device.key
        }
        updateAndRecompute {
            it.copy(
                personLabel = branding.person, groupLabel = branding.group,
                connectedDevices = connected, selectedTargetKey = nextTarget,
                maxNameBytes = configRepository.getConfig().maxDeviceNameBytes,
            )
        }
        loadDataForCurrentTarget()
    }

    fun selectTarget(targetKey: String) {
        if (_uiState.value.isPushing) return
        updateAndRecompute {
            it.copy(selectedTargetKey = targetKey, selectedKeys = emptySet(), activeTab = PushListTab.NOT_ON_DEVICE, errorMessage = null)
        }
        loadDataForCurrentTarget()
    }

    fun selectTab(tab: PushListTab) = updateAndRecompute { it.copy(activeTab = tab) }
    fun onSearchQueryChange(query: String) = updateAndRecompute { it.copy(searchQuery = query) }

    fun toggleGroupExpanded(classId: Int) = updateAndRecompute { s ->
        val next = if (classId in s.collapsedGroupIds) s.collapsedGroupIds - classId else s.collapsedGroupIds + classId
        s.copy(collapsedGroupIds = next)
    }

    fun toggleMemberSelected(pushKey: String) {
        if (pushKey.isBlank()) return
        updateAndRecompute { s ->
            val next = if (pushKey in s.selectedKeys) s.selectedKeys - pushKey else s.selectedKeys + pushKey
            s.copy(selectedKeys = next)
        }
    }

    fun toggleGroupSelection(section: MemberGroupSection) {
        val groupKeys = section.rows.filter { it.isPushableOnTarget }.map { it.pushKey }.toSet()
        if (groupKeys.isEmpty()) return
        updateAndRecompute { s ->
            val next = if (groupKeys.all { it in s.selectedKeys }) s.selectedKeys - groupKeys else s.selectedKeys + groupKeys
            s.copy(selectedKeys = next)
        }
    }

    fun selectAllVisible() {
        val visibleKeys = _uiState.value.visibleSections.flatMap { it.rows }.filter { it.isPushableOnTarget }.map { it.pushKey }.toSet()
        updateAndRecompute { it.copy(selectedKeys = it.selectedKeys + visibleKeys) }
    }

    fun clearSelection() = updateAndRecompute { it.copy(selectedKeys = emptySet()) }

    fun requestPushSelected() {
        val s = _uiState.value
        if (s.isPushing || s.isLoading) return
        if (s.targetHandles.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Không có máy chấm công nào đang kết nối.") }; return
        }
        if (s.selectedKeys.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Hãy tick chọn ít nhất 1 ${configRepository.getBranding().personLower} để đẩy.") }; return
        }
        if (s.isTargetAll && s.targetHandles.size > 1) _uiState.update { it.copy(showPushAllWarningDialog = true) } else executePush()
    }

    fun dismissPushAllWarning() = _uiState.update { it.copy(showPushAllWarningDialog = false) }
    fun confirmPushAll() { _uiState.update { it.copy(showPushAllWarningDialog = false) }; executePush() }
    fun dismissPushSummary() = _uiState.update { it.copy(pushSummary = null) }

    fun exportWordDocument() {
        val s = _uiState.value
        if (s.isExporting || s.allMembers.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isExporting = true, errorMessage = null, exportSuccessPath = null) }
            val isHik = s.isHikvisionMode
            val chosen = if (s.selectedKeys.isNotEmpty()) s.allMembers.filter { it.pushKeyFor(isHik) in s.selectedKeys } else s.allMembers
            val cfg = configRepository.getConfig()
            exportMemberListDocUseCase(cfg.orgName.ifBlank { cfg.username }, s.classes, chosen, s.existingNamesOnTarget, cfg.maxDeviceNameBytes).fold(
                onSuccess = { path -> _uiState.update { it.copy(isExporting = false, exportSuccessPath = path) } },
                onFailure = { err -> _uiState.update { it.copy(isExporting = false, errorMessage = "Xuất Word lỗi: ${err.message}") } },
            )
        }
    }

    private fun loadDataForCurrentTarget() {
        val targets = _uiState.value.targetHandles
        val isHik = _uiState.value.isHikvisionMode
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            loadMembersTreeUseCase.loadForTargets(targets).fold(
                onSuccess = { tree ->
                    val maxBytes = configRepository.getConfig().maxDeviceNameBytes
                    val pushable = tree.members.filter { it.isPushableFor(isHik) }
                    val previewMap = LinkedHashMap<String, String>(pushable.size)
                    if (isHik) {
                        pushable.forEach { m -> previewMap[m.hikvisionEmployeeNo] = m.name.trim().take(32) }
                    } else {
                        val shorts = abbreviateNamesUseCase.abbreviateBatch(pushable.map { it.name }, tree.existingNamesOnTarget, maxBytes)
                        pushable.forEachIndexed { idx, m -> previewMap[m.cardId] = shorts[idx] }
                    }
                    updateAndRecompute {
                        it.copy(
                            isLoading = false, maxNameBytes = maxBytes, classes = tree.classes, allMembers = tree.members,
                            existingCardIdsOnTarget = tree.existingCardIdsOnTarget,
                            existingEmployeeNosOnTarget = tree.existingEmployeeNosOnTarget,
                            existingNamesOnTarget = tree.existingNamesOnTarget,
                            previewShortNameByKey = previewMap, selectedKeys = emptySet(),
                        )
                    }
                },
                onFailure = { err -> _uiState.update { it.copy(isLoading = false, errorMessage = err.message ?: "Không tải được danh sách từ máy chủ.") } },
            )
        }
    }

    private fun executePush() {
        val s = _uiState.value
        val targets = s.targetHandles
        val isHik = s.isHikvisionMode
        val chosen = s.allMembers.filter { it.isPushableFor(isHik) && it.pushKeyFor(isHik) in s.selectedKeys }
        if (targets.isEmpty() || chosen.isEmpty()) return

        val skipExisting = s.activeTab == PushListTab.NOT_ON_DEVICE
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isPushing = true, errorMessage = null, progress = BatchProgress(0, targets.size * chosen.size, "Đang chuẩn bị đẩy dữ liệu...")) }
            val summary = attendanceEngine.withDeviceIoLock {
                pushMembersToDevicesUseCase(targets, chosen, skipExisting, configRepository.getConfig().maxDeviceNameBytes) { prog ->
                    _uiState.update { it.copy(progress = prog) }
                }
            }
            updateAndRecompute { it.copy(isPushing = false, progress = null, pushSummary = summary, selectedKeys = emptySet()) }
            loadDataForCurrentTarget()
        }
    }

    private fun updateAndRecompute(transform: (PushMembersUiState) -> PushMembersUiState) {
        _uiState.update { prev -> val next = transform(prev); next.copy(visibleSections = buildSections(next)) }
    }

    private fun buildSections(s: PushMembersUiState): List<MemberGroupSection> {
        val classById = s.classes.associate { it.id to it.name }
        val key = abbreviateNamesUseCase.stripVietnameseDiacritics(s.searchQuery.trim()).lowercase()
        val isHik = s.isHikvisionMode

        val filtered = s.allMembers.filter { m ->
            val onDevice = s.isMemberOnTarget(m)
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
                val pKey = m.pushKeyFor(isHik)
                val pushable = m.isPushableFor(isHik)
                MemberDisplayRow(
                    member = m, pushKey = pKey, isPushableOnTarget = pushable, className = grpName,
                    previewShortName = s.previewShortNameByKey[pKey].orEmpty(),
                    isOnTargetDevice = s.isMemberOnTarget(m),
                    isSelected = pushable && pKey in s.selectedKeys,
                )
            }
            MemberGroupSection(cid, grpName, rows, isExpanded = cid !in s.collapsedGroupIds)
        }
    }
}
