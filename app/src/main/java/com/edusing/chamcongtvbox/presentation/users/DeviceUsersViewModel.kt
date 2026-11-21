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

/** Trạng thái UI màn hình quản lý người dùng đang lưu trong bộ nhớ máy chấm công. */
data class DeviceUsersUiState(
    val groupLabel: String = "Lớp",
    val connectedDevices: List<ConnectedDeviceHandle> = emptyList(),
    val selectedDeviceKey: String? = null,
    val users: List<DeviceUser> = emptyList(),
    val filteredUsers: List<DeviceUser> = emptyList(),
    val searchQuery: String = "",
    val selectedIds: Set<Int> = emptySet(),
    val isLoading: Boolean = false,
    val isDeleting: Boolean = false,
    val progress: BatchProgress? = null,
    val errorMessage: String? = null,
    val statusMessage: String? = null,
    val pendingDeleteIds: List<Int> = emptyList(),
    val pendingDeleteSummary: String? = null,
) {
    val selectedDevice: ConnectedDeviceHandle?
        get() = connectedDevices.firstOrNull { it.device.key == selectedDeviceKey }
}

/** ViewModel quản lý đọc, tìm kiếm không dấu và xoá đơn/hàng loạt người dùng trên máy. */
class DeviceUsersViewModel(
    private val configRepository: ConfigRepository,
    private val attendanceEngine: AttendanceEngine,
    private val readDeviceUsersUseCase: ReadDeviceUsersUseCase,
    private val deleteDeviceUsersUseCase: DeleteDeviceUsersUseCase,
    private val abbreviateNamesUseCase: AbbreviateNamesUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DeviceUsersUiState(groupLabel = configRepository.getBranding().group))
    val uiState: StateFlow<DeviceUsersUiState> = _uiState.asStateFlow()

    init {
        refreshAndLoad()
    }

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
                selectedIds = emptySet(),
            )
        }
        loadUsersForSelectedDevice()
    }

    fun selectDevice(deviceKey: String) {
        if (_uiState.value.isDeleting) return
        updateFiltered { it.copy(selectedDeviceKey = deviceKey, selectedIds = emptySet(), errorMessage = null, statusMessage = null) }
        loadUsersForSelectedDevice()
    }

    fun onSearchQueryChange(query: String) = updateFiltered { it.copy(searchQuery = query) }

    fun toggleUserSelected(enrollNumber: Int) {
        _uiState.update { s ->
            val next = if (enrollNumber in s.selectedIds) s.selectedIds - enrollNumber else s.selectedIds + enrollNumber
            s.copy(selectedIds = next)
        }
    }

    fun selectAllFiltered() {
        val ids = _uiState.value.filteredUsers.map { it.enrollNumber }.toSet()
        _uiState.update { it.copy(selectedIds = it.selectedIds + ids) }
    }

    fun clearSelection() = _uiState.update { it.copy(selectedIds = emptySet()) }

    fun requestDeleteSingle(user: DeviceUser) {
        if (_uiState.value.isDeleting) return
        val label = user.serverFullName?.takeIf { it.isNotBlank() } ?: user.deviceName.ifBlank { "Mã #${user.enrollNumber}" }
        _uiState.update {
            it.copy(
                pendingDeleteIds = listOf(user.enrollNumber),
                pendingDeleteSummary = "Xoá \"$label\" (Mã ${user.enrollNumber}) khỏi máy ${_uiState.value.selectedDevice?.device?.displayName.orEmpty()}?",
            )
        }
    }

    fun requestDeleteSelected() {
        val s = _uiState.value
        if (s.isDeleting || s.selectedIds.isEmpty()) return
        val sorted = s.selectedIds.sorted()
        _uiState.update {
            it.copy(
                pendingDeleteIds = sorted,
                pendingDeleteSummary = "Bạn có chắc chắn muốn XOÁ ${sorted.size} người đã chọn khỏi máy ${s.selectedDevice?.device?.displayName.orEmpty()}?\n(Thao tác xoá toàn bộ dữ liệu thẻ/vân tay/khuôn mặt của các mã này trên máy chấm công.)",
            )
        }
    }

    fun dismissDeleteDialog() = _uiState.update { it.copy(pendingDeleteIds = emptyList(), pendingDeleteSummary = null) }

    fun confirmDelete() {
        val s = _uiState.value
        val device = s.selectedDevice ?: return
        val ids = s.pendingDeleteIds
        if (ids.isEmpty()) return

        _uiState.update {
            it.copy(
                pendingDeleteIds = emptyList(),
                pendingDeleteSummary = null,
                isDeleting = true,
                errorMessage = null,
                statusMessage = null,
                progress = BatchProgress(0, ids.size, "Đang xoá người dùng trên máy..."),
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            deleteDeviceUsersUseCase(device.handle, ids) { prog ->
                _uiState.update { it.copy(progress = prog) }
            }.fold(
                onSuccess = { deleted ->
                    _uiState.update {
                        it.copy(
                            isDeleting = false,
                            progress = null,
                            selectedIds = emptySet(),
                            statusMessage = "Đã xoá thành công $deleted/${ids.size} người khỏi máy ${device.device.displayName}.",
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
        val device = _uiState.value.selectedDevice
        if (device == null) {
            updateFiltered { it.copy(isLoading = false, users = emptyList(), errorMessage = "Chưa có máy chấm công nào đang kết nối.") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            readDeviceUsersUseCase(device.handle).fold(
                onSuccess = { list -> updateFiltered { it.copy(isLoading = false, users = list, selectedIds = emptySet()) } },
                onFailure = { err -> updateFiltered { it.copy(isLoading = false, users = emptyList(), errorMessage = err.message ?: "Không đọc được máy.") } },
            )
        }
    }

    private fun updateFiltered(transform: (DeviceUsersUiState) -> DeviceUsersUiState) {
        _uiState.update { prev ->
            val next = transform(prev)
            val key = abbreviateNamesUseCase.stripVietnameseDiacritics(next.searchQuery.trim()).lowercase()
            val list = if (key.isEmpty()) {
                next.users
            } else {
                next.users.filter { u ->
                    u.enrollNumber.toString().contains(key) ||
                        abbreviateNamesUseCase.stripVietnameseDiacritics(u.deviceName).lowercase().contains(key) ||
                        abbreviateNamesUseCase.stripVietnameseDiacritics(u.serverFullName.orEmpty()).lowercase().contains(key) ||
                        abbreviateNamesUseCase.stripVietnameseDiacritics(u.className.orEmpty()).lowercase().contains(key)
                }
            }
            next.copy(filteredUsers = list)
        }
    }
}
