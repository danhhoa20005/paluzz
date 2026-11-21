package com.edusing.chamcongtvbox.di

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.edusing.chamcongtvbox.presentation.adddevice.AddDeviceViewModel
import com.edusing.chamcongtvbox.presentation.login.LoginViewModel
import com.edusing.chamcongtvbox.presentation.main.MainViewModel
import com.edusing.chamcongtvbox.presentation.push.PushMembersViewModel
import com.edusing.chamcongtvbox.presentation.settings.SettingsViewModel
import com.edusing.chamcongtvbox.presentation.users.DeviceUsersViewModel

/**
 * Factory gắn kết [AppContainer] với vòng đời [ViewModel] của từng màn hình MVVM.
 */
class AppViewModelFactory(
    private val container: AppContainer,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(LoginViewModel::class.java) -> LoginViewModel(
            configRepository = container.configRepository,
            attendanceEngine = container.attendanceEngine,
            loginUseCase = container.loginUseCase,
            internetProbe = container.internetProbe,
        ) as T

        modelClass.isAssignableFrom(MainViewModel::class.java) -> MainViewModel(
            configRepository = container.configRepository,
            attendanceEngine = container.attendanceEngine,
            logoutUseCase = container.logoutUseCase,
        ) as T

        modelClass.isAssignableFrom(AddDeviceViewModel::class.java) -> AddDeviceViewModel(
            configRepository = container.configRepository,
            scanLanDevicesUseCase = container.scanLanDevicesUseCase,
            suggestFreeIpUseCase = container.suggestFreeIpUseCase,
            attendanceEngine = container.attendanceEngine,
        ) as T

        modelClass.isAssignableFrom(PushMembersViewModel::class.java) -> PushMembersViewModel(
            configRepository = container.configRepository,
            attendanceEngine = container.attendanceEngine,
            loadMembersTreeUseCase = container.loadMembersTreeUseCase,
            pushMembersToDevicesUseCase = container.pushMembersToDevicesUseCase,
            abbreviateNamesUseCase = container.abbreviateNamesUseCase,
            exportMemberListDocUseCase = container.exportMemberListDocUseCase,
        ) as T

        modelClass.isAssignableFrom(DeviceUsersViewModel::class.java) -> DeviceUsersViewModel(
            configRepository = container.configRepository,
            attendanceEngine = container.attendanceEngine,
            readDeviceUsersUseCase = container.readDeviceUsersUseCase,
            deleteDeviceUsersUseCase = container.deleteDeviceUsersUseCase,
            abbreviateNamesUseCase = container.abbreviateNamesUseCase,
        ) as T

        modelClass.isAssignableFrom(SettingsViewModel::class.java) -> SettingsViewModel(
            configRepository = container.configRepository,
            attendanceEngine = container.attendanceEngine,
            appUpdateCoordinator = container.appUpdateCoordinator,
        ) as T

        else -> throw IllegalArgumentException("Không hỗ trợ ViewModel: ${modelClass.name}")
    }
}
