package com.edusing.chamcongtvbox.presentation.users

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.edusing.chamcongtvbox.domain.model.BatchProgress
import com.edusing.chamcongtvbox.domain.model.DeviceUser
import com.edusing.chamcongtvbox.domain.repository.ConfigRepository
import com.edusing.chamcongtvbox.domain.usecase.AbbreviateNamesUseCase
import com.edusing.chamcongtvbox.domain.usecase.ConnectedDeviceHandle
import com.edusing.chamcongtvbox.domain.usecase.DeleteDeviceUsersUseCase
import com.edusing.chamcongtvbox.domain.usecase.ReadDeviceUsersUseCase
import com.edusing.chamcongtvbox.service.AttendanceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Trạng thái UI màn hình quản lý người dùng đang lưu trong bộ nhớ máy chấm công (FK & Hikvision). */
data class DeviceUsersUiState(
    val groupLabel: String = "Lớp",
    val connectedDevices: List<ConnectedDeviceHandle> = emptyList(),
    val selectedDeviceKey: String? = null,
    val users: List<DeviceUser> = emptyList(),
    val filteredUsers: List<DeviceUser> = emptyList(),
    val searchQuery: String = "",
    val onlyMissingFace: Boolean = false,
    val selectedKeys: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val isDeleting: Boolean = false,
    val progress: BatchProgress? = null,
    val errorMessage: String? = null,
    val statusMessage: String? = null,
    val pendingDeleteUsers: List<DeviceUser> = emptyList(),
    val pendingDeleteSummary: String? = null,
) {
    val selectedDevice: ConnectedDeviceHandle?
        get() = connectedDevices.firstOrNull { it.device.key == selectedDeviceKey }
    val isHikvisionSelected: Boolean get() = selectedDevice?.device?.isHikvision == true
}

/** ViewModel quản lý đọc, lọc thiếu khuôn mặt, tìm kiếm không dấu và xoá đơn/hàng loạt người dùng trên máy. */
class DeviceUsersViewModel(
    private val configRepository: ConfigRepository,
    private val attendanceEngine: AttendanceEngine,
    private val readDeviceUsersUseCase: ReadDeviceUsersUseCase,
    private val deleteDeviceUsersUseCase: DeleteDeviceUsersUseCase,
    private val abbreviateNamesUseCase: AbbreviateNamesUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DeviceUsersUiState(groupLabel = configRepository.getBranding().group))
    val uiState: StateFlow<DeviceUsersUiState> = _uiState.asStateFlow()

    init { refreshAndLoad() }

    fun refreshAndLoad() {
        val connected = attendanceEngine.getConnectedHandles()
        val currentKey = _uiState.value.selectedDeviceKey
        val nextKey = when {
            connected.isEmpty() -> null
            connected.any { it.device.key == currentKey } -> currentKey
            else -> connected.first().device.key
        }
        updateFiltered {
            it.copy(
                groupLabel = configRepository.getBranding().group,
                connectedDevices = connected,
                selectedDeviceKey = nextKey,
                selectedKeys = emptySet(),
            )
        }
        loadUsersForSelectedDevice()
    }

    fun selectDevice(deviceKey: String) {
        if (_uiState.value.isDeleting) return
        updateFiltered {
            it.copy(selectedDeviceKey = deviceKey, selectedKeys = emptySet(), onlyMissingFace = false, errorMessage = null, statusMessage = null)
        }
        loadUsersForSelectedDevice()
    }

    fun onSearchQueryChange(query: String) = updateFiltered { it.copy(searchQuery = query) }
    fun toggleOnlyMissingFace() = updateFiltered { it.copy(onlyMissingFace = !it.onlyMissingFace) }

    fun toggleUserSelected(uniqueKey: String) {
        _uiState.update { s ->
            val next = if (uniqueKey in s.selectedKeys) s.selectedKeys - uniqueKey else s.selectedKeys + uniqueKey
            s.copy(selectedKeys = next)
        }
    }

    fun selectAllFiltered() {
        val keys = _uiState.value.filteredUsers.map { it.uniqueKey }.toSet()
        _uiState.update { it.copy(selectedKeys = it.selectedKeys + keys) }
    }

    fun clearSelection() = _uiState.update { it.copy(selectedKeys = emptySet()) }

    fun requestDeleteSingle(user: DeviceUser) {
        if (_uiState.value.isDeleting) return
        val label = user.serverFullName?.takeIf { it.isNotBlank() } ?: user.deviceName.ifBlank { "Mã #${user.displayCardId}" }
        _uiState.update {
            it.copy(
                pendingDeleteUsers = listOf(user),
                pendingDeleteSummary = "Xoá \"$label\" (Mã ${user.displayCardId}) khỏi máy ${_uiState.value.selectedDevice?.device?.displayName.orEmpty()}?",
            )
        }
    }

    fun requestDeleteSelected() {
        val s = _uiState.value
        if (s.isDeleting || s.selectedKeys.isEmpty()) return
        val targets = s.users.filter { it.uniqueKey in s.selectedKeys }
        if (targets.isEmpty()) return
        _uiState.update {
            it.copy(
                pendingDeleteUsers = targets,
                pendingDeleteSummary = "Bạn có chắc chắn muốn XOÁ ${targets.size} người đã chọn khỏi máy ${s.selectedDevice?.device?.displayName.orEmpty()}?\n(Thao tác xoá toàn bộ dữ liệu thẻ/vân tay/khuôn mặt trên máy chấm công.)",
            )
        }
    }

    fun dismissDeleteDialog() = _uiState.update { it.copy(pendingDeleteUsers = emptyList(), pendingDeleteSummary = null) }

    fun confirmDelete() {
        val s = _uiState.value
        val target = s.selectedDevice ?: return
        val usersToDelete = s.pendingDeleteUsers
        if (usersToDelete.isEmpty()) return

        _uiState.update {
            it.copy(
                pendingDeleteUsers = emptyList(), pendingDeleteSummary = null,
                isDeleting = true, errorMessage = null, statusMessage = null,
                progress = BatchProgress(0, usersToDelete.size, "Đang xoá người dùng trên máy..."),
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val result = if (target.device.isHikvision) {
                deleteDeviceUsersUseCase.deleteEmployeeNos(target.device, usersToDelete.map { it.displayCardId }) { prog ->
                    _uiState.update { it.copy(progress = prog) }
                }
            } else {
                deleteDeviceUsersUseCase(target.handle, usersToDelete.map { it.enrollNumber }) { prog ->
                    _uiState.update { it.copy(progress = prog) }
                }
            }
            result.fold(
                onSuccess = { deleted ->
                    _uiState.update {
                        it.copy(
                            isDeleting = false, progress = null, selectedKeys = emptySet(),
                            statusMessage = "Đã xoá thành công $deleted/${usersToDelete.size} người khỏi máy ${target.device.displayName}.",
                        )
                    }
                    loadUsersForSelectedDevice()
                },
                onFailure = { err ->
                    _uiState.update { it.copy(isDeleting = false, progress = null, errorMessage = "Xoá thất bại: ${err.message}") }
                    loadUsersForSelectedDevice()
                },
            )
        }
    }

    private fun loadUsersForSelectedDevice() {
        val target = _uiState.value.selectedDevice
        if (target == null) {
            updateFiltered { it.copy(isLoading = false, users = emptyList(), errorMessage = "Chưa có máy chấm công nào đang kết nối.") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            readDeviceUsersUseCase(target.handle, target.device).fold(
                onSuccess = { list -> updateFiltered { it.copy(isLoading = false, users = list, selectedKeys = emptySet()) } },
                onFailure = { err -> updateFiltered { it.copy(isLoading = false, users = emptyList(), errorMessage = err.message ?: "Không đọc được máy.") } },
            )
        }
    }

    private fun updateFiltered(transform: (DeviceUsersUiState) -> DeviceUsersUiState) {
        _uiState.update { prev ->
            val next = transform(prev)
            val key = abbreviateNamesUseCase.stripVietnameseDiacritics(next.searchQuery.trim()).lowercase()
            val list = next.users.filter { u ->
                val faceOk = !next.onlyMissingFace || u.faceCount == 0
                val textOk = key.isEmpty() ||
                    u.displayCardId.lowercase().contains(key) ||
                    abbreviateNamesUseCase.stripVietnameseDiacritics(u.deviceName).lowercase().contains(key) ||
                    abbreviateNamesUseCase.stripVietnameseDiacritics(u.serverFullName.orEmpty()).lowercase().contains(key) ||
                    abbreviateNamesUseCase.stripVietnameseDiacritics(u.className.orEmpty()).lowercase().contains(key)
                faceOk && textOk
            }
            next.copy(filteredUsers = list)
        }
    }
}
