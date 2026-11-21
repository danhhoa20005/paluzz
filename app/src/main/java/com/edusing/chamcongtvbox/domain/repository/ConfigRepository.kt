package com.edusing.chamcongtvbox.domain.repository

import com.edusing.chamcongtvbox.domain.model.AppConfig
import com.edusing.chamcongtvbox.domain.model.AppMode
import com.edusing.chamcongtvbox.domain.model.BrandingConfig
import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.SavedAccount
import kotlinx.coroutines.flow.StateFlow

interface ConfigRepository {
    val configFlow: StateFlow<AppConfig>
    fun getConfig(): AppConfig
    fun getBranding(): BrandingConfig
    suspend fun switchMode(targetMode: AppMode): AppConfig
    suspend fun saveConfig(config: AppConfig)
    suspend fun upsertSavedAccount(account: SavedAccount)
    suspend fun removeSavedAccount(username: String)
    suspend fun updateDevices(devices: List<DeviceConfig>)
    suspend fun setAutoStartRunning(running: Boolean)
    suspend fun clearActiveCredentials()
}
