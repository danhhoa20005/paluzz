package com.edusing.chamcongtvbox.service

import android.content.Intent
import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kiểm thử logic tự bật màn hình TV Box khi có điện lại theo từng đời Android (7–15) và sự kiện boot. */
class TvAutoBootHelperTest {

    @Test
    fun android7To9_canAutoLaunchWithoutExtraPermissions() {
        assertTrue(
            TvAutoBootHelper.canAutoLaunchUiOnBootForSdk(
                sdkInt = Build.VERSION_CODES.P,
                hasOverlayPermission = false,
                isDefaultHomeLauncher = false,
            )
        )
    }

    @Test
    fun android10Plus_requiresOverlayOrDefaultHomeLauncher() {
        assertFalse(
            TvAutoBootHelper.canAutoLaunchUiOnBootForSdk(
                sdkInt = Build.VERSION_CODES.Q,
                hasOverlayPermission = false,
                isDefaultHomeLauncher = false,
            )
        )
        assertTrue(
            TvAutoBootHelper.canAutoLaunchUiOnBootForSdk(
                sdkInt = Build.VERSION_CODES.R,
                hasOverlayPermission = true,
                isDefaultHomeLauncher = false,
            )
        )
        assertTrue(
            TvAutoBootHelper.canAutoLaunchUiOnBootForSdk(
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                hasOverlayPermission = false,
                isDefaultHomeLauncher = true,
            )
        )
    }

    @Test
    fun bootReceiver_acceptsAllTvBoxBootActionsAndRejectsUnrelated() {
        assertTrue(BootReceiver.isBootAction(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(BootReceiver.isBootAction(Intent.ACTION_LOCKED_BOOT_COMPLETED))
        assertTrue(BootReceiver.isBootAction(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertTrue(BootReceiver.isBootAction(Intent.ACTION_REBOOT))
        assertTrue(BootReceiver.isBootAction("android.intent.action.QUICKBOOT_POWERON"))
        assertTrue(BootReceiver.isBootAction("com.htc.intent.action.QUICKBOOT_POWERON"))
        assertTrue(BootReceiver.isBootAction("com.intent.action.BOOT"))
        assertFalse(BootReceiver.isBootAction(Intent.ACTION_AIRPLANE_MODE_CHANGED))
    }
}
