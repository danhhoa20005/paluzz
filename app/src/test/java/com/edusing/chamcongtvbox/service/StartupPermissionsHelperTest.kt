package com.edusing.chamcongtvbox.service

import android.os.Build
import com.edusing.chamcongtvbox.domain.model.StartupPermissionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kiểm thử cổng cấp quyền bắt buộc lúc mở ứng dụng theo từng đời Android (7–15) & thiết bị TV Box / Điện thoại. */
class StartupPermissionsHelperTest {

    @Test
    fun android14Phone_missingAnyPermission_blocksEntryAndPrioritizesAutoLaunchFirst() {
        val snap = StartupPermissionsHelper.evaluateForCapabilities(
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            isTvDevice = false,
            canAutoBoot = false,
            isDefaultHomeLauncher = false,
            canResolveOverlay = true,
            canResolveHome = true,
            ignoringBattery = false,
            canResolveBattery = true,
            notifGranted = false,
            installGranted = false,
            canResolveInstall = true,
        )
        assertFalse(snap.allMandatoryGranted)
        assertEquals(4, snap.totalMandatoryCount)
        assertEquals(0, snap.grantedMandatoryCount)
        assertEquals(StartupPermissionType.AUTO_LAUNCH_ON_BOOT, snap.nextPending?.type)
    }

    @Test
    fun android14_autoLaunchGrantedThenBatteryPending_advancesSequentially() {
        val snap = StartupPermissionsHelper.evaluateForCapabilities(
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            isTvDevice = true,
            canAutoBoot = true,
            isDefaultHomeLauncher = true,
            canResolveOverlay = false,
            canResolveHome = true,
            ignoringBattery = false,
            canResolveBattery = true,
            notifGranted = true,
            installGranted = true,
            canResolveInstall = true,
        )
        assertFalse(snap.allMandatoryGranted)
        assertEquals(3, snap.grantedMandatoryCount)
        assertEquals(4, snap.totalMandatoryCount)
        assertEquals(StartupPermissionType.BATTERY_UNRESTRICTED, snap.nextPending?.type)
    }

    @Test
    fun android14_allMandatoryGranted_unlocksAppImmediately() {
        val snap = StartupPermissionsHelper.evaluateForCapabilities(
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            isTvDevice = false,
            canAutoBoot = true,
            isDefaultHomeLauncher = false,
            canResolveOverlay = true,
            canResolveHome = true,
            ignoringBattery = true,
            canResolveBattery = true,
            notifGranted = true,
            installGranted = true,
            canResolveInstall = true,
        )
        assertTrue(snap.allMandatoryGranted)
        assertNull(snap.nextPending)
        assertEquals(4, snap.grantedMandatoryCount)
    }

    @Test
    fun android11TvBox_noNotificationRuntimePermissionRequired() {
        val snap = StartupPermissionsHelper.evaluateForCapabilities(
            sdkInt = Build.VERSION_CODES.R,
            isTvDevice = true,
            canAutoBoot = false,
            isDefaultHomeLauncher = false,
            canResolveOverlay = true,
            canResolveHome = true,
            ignoringBattery = true,
            canResolveBattery = true,
            notifGranted = true,
            installGranted = true,
            canResolveInstall = true,
        )
        assertFalse(snap.allMandatoryGranted)
        assertEquals(3, snap.totalMandatoryCount)
        assertEquals(2, snap.grantedMandatoryCount)
        assertEquals(StartupPermissionType.AUTO_LAUNCH_ON_BOOT, snap.nextPending?.type)
    }

    @Test
    fun android9TvBox_autoLaunchAlreadySupportedNatively_requiresBatteryAndOtaOnly() {
        val snap = StartupPermissionsHelper.evaluateForCapabilities(
            sdkInt = Build.VERSION_CODES.P,
            isTvDevice = true,
            canAutoBoot = true,
            isDefaultHomeLauncher = false,
            canResolveOverlay = true,
            canResolveHome = true,
            ignoringBattery = false,
            canResolveBattery = true,
            notifGranted = true,
            installGranted = false,
            canResolveInstall = true,
        )
        assertFalse(snap.allMandatoryGranted)
        assertEquals(2, snap.totalMandatoryCount)
        assertEquals(StartupPermissionType.BATTERY_UNRESTRICTED, snap.nextPending?.type)
    }

    @Test
    fun strippedTvRomWithoutBatteryOrInstallActivities_neverSoftLocksDevice() {
        val snap = StartupPermissionsHelper.evaluateForCapabilities(
            sdkInt = Build.VERSION_CODES.R,
            isTvDevice = true,
            canAutoBoot = true,
            isDefaultHomeLauncher = true,
            canResolveOverlay = false,
            canResolveHome = true,
            ignoringBattery = false,
            canResolveBattery = false,
            notifGranted = true,
            installGranted = false,
            canResolveInstall = false,
        )
        assertTrue(snap.allMandatoryGranted)
        assertEquals(1, snap.totalMandatoryCount)
        assertEquals(1, snap.grantedMandatoryCount)
    }
}
