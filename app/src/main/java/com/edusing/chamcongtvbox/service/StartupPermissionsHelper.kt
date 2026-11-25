package com.edusing.chamcongtvbox.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.edusing.chamcongtvbox.domain.model.StartupPermissionItem
import com.edusing.chamcongtvbox.domain.model.StartupPermissionType
import com.edusing.chamcongtvbox.domain.model.StartupPermissionsSnapshot
import com.edusing.chamcongtvbox.presentation.theme.detectIsTvDevice
import java.util.Locale

/**
 * Bộ kiểm tra & mở trực tiếp từng trang cấp quyền bắt buộc ngay lúc mới vào ứng dụng:
 * 1. Tự động bật ứng dụng khi có điện / khởi động máy (`SYSTEM_ALERT_WINDOW` trên Android 10+ hoặc Màn hình chính TV Box).
 * 2. Bỏ giới hạn pin để dịch vụ chấm công chạy nền 24/7 (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`).
 * 3. Quyền thông báo chạy nền (`POST_NOTIFICATIONS`, Android 13+ — tự mở Cài đặt nếu đã từng từ chối vĩnh viễn).
 * 4. Quyền cài đặt bản cập nhật tự động OTA (`REQUEST_INSTALL_PACKAGES`, Android 8+).
 */
object StartupPermissionsHelper {

    fun evaluate(context: Context): StartupPermissionsSnapshot {
        val sdk = Build.VERSION.SDK_INT
        val isTv = detectIsTvDevice(context, context.resources.configuration)
        val isHome = TvAutoBootHelper.isDefaultHomeLauncher(context)
        val hasOverlay = TvAutoBootHelper.hasOverlayPermission(context)
        val canAutoBoot = TvAutoBootHelper.canAutoLaunchUiOnBootForSdk(sdk, hasOverlay, isHome)
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val ignoringBattery = if (sdk >= Build.VERSION_CODES.M) {
            runCatching { pm?.isIgnoringBatteryOptimizations(context.packageName) == true }.getOrDefault(false)
        } else true
        val notifGranted = if (sdk >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        val installGranted = if (sdk >= Build.VERSION_CODES.O) {
            runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
        } else true

        return evaluateForCapabilities(
            sdkInt = sdk,
            isTvDevice = isTv,
            canAutoBoot = canAutoBoot,
            isDefaultHomeLauncher = isHome,
            canResolveOverlay = sdk >= Build.VERSION_CODES.M && canResolveOverlaySettings(context),
            canResolveHome = canResolveHomeSettings(context),
            ignoringBattery = ignoringBattery,
            canResolveBattery = sdk >= Build.VERSION_CODES.M && canResolveBatterySettings(context),
            notifGranted = notifGranted,
            installGranted = installGranted,
            canResolveInstall = sdk >= Build.VERSION_CODES.O && canResolveInstallSources(context),
        )
    }

    internal fun evaluateForCapabilities(
        sdkInt: Int,
        isTvDevice: Boolean,
        canAutoBoot: Boolean,
        isDefaultHomeLauncher: Boolean,
        canResolveOverlay: Boolean,
        canResolveHome: Boolean,
        ignoringBattery: Boolean,
        canResolveBattery: Boolean,
        notifGranted: Boolean,
        installGranted: Boolean,
        canResolveInstall: Boolean,
    ): StartupPermissionsSnapshot {
        val items = mutableListOf<StartupPermissionItem>()
        // 1. Quyền tự động bật app khi mất điện có lại / mở máy (luôn đặt trên cùng)
        val autoLaunchRequired = (sdkInt >= Build.VERSION_CODES.Q) && (canResolveOverlay || canResolveHome)
        items += StartupPermissionItem(
            type = StartupPermissionType.AUTO_LAUNCH_ON_BOOT,
            title = "Tự động bật app khi mở máy",
            description = if (sdkInt >= Build.VERSION_CODES.Q) {
                "Bật \"Hiển thị trên ứng dụng khác\" (hoặc chọn làm Màn hình chính TV) để mất điện có lại tự mở thẳng app."
            } else {
                "Thiết bị Android ${sdkInt} hỗ trợ tự khởi chạy sẵn; có thể đặt thêm làm Màn hình chính TV Box bên dưới."
            },
            actionLabel = if (canResolveOverlay) "Cấp quyền tự bật" else "Đặt Màn hình chính",
            granted = canAutoBoot,
            required = autoLaunchRequired,
        )

        // 2. Bỏ giới hạn pin để không bị tắt ngầm sau vài phút
        if (sdkInt >= Build.VERSION_CODES.M) {
            items += StartupPermissionItem(
                type = StartupPermissionType.BATTERY_UNRESTRICTED,
                title = "Chạy nền 24/7 (Bỏ giới hạn pin)",
                description = "Ngăn Android tự tắt dịch vụ chấm công khi tắt màn hình hoặc chạy liên tục cả ngày.",
                actionLabel = "Bỏ giới hạn pin",
                granted = ignoringBattery,
                required = canResolveBattery,
            )
        }

        // 3. Thông báo trạng thái 24/7 trên Android 13+
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            items += StartupPermissionItem(
                type = StartupPermissionType.NOTIFICATIONS,
                title = "Thông báo trạng thái 24/7",
                description = "Hiển thị thanh trạng thái dịch vụ đang trực và cảnh báo ngay khi mất mạng / mất máy.",
                actionLabel = "Cho phép thông báo",
                granted = notifGranted,
                required = true,
            )
        }

        // 4. Cho phép cài bản cập nhật tự động OTA trên Android 8+
        if (sdkInt >= Build.VERSION_CODES.O) {
            items += StartupPermissionItem(
                type = StartupPermissionType.INSTALL_UPDATES,
                title = "Cho phép tự cập nhật (OTA)",
                description = "Cho phép cài đặt ngay bản nâng cấp mới từ hệ thống mà không phải tìm bật lại sau này.",
                actionLabel = "Cho phép cài đặt",
                granted = installGranted,
                required = canResolveInstall,
            )
        }

        return StartupPermissionsSnapshot(
            isTvDevice = isTvDevice,
            isDefaultHomeLauncher = isDefaultHomeLauncher,
            canSetHomeLauncher = canResolveHome,
            items = items,
        )
    }

    /** Mở màn hình bỏ giới hạn pin (hỗ trợ Oppo/Realme/OnePlus và mọi máy Android 6–15). */
    @SuppressLint("BatteryLife")
    fun openBatteryOptimizationSettings(context: Context) {
        val mfg = Build.MANUFACTURER.orEmpty().lowercase(Locale.ROOT)
        if (mfg.contains("oppo") || mfg.contains("realme") || mfg.contains("oneplus")) {
            val opened = startSafe(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            if (opened) return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val direct = startSafe(context, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
            if (direct) return
            if (startSafe(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) return
        }
        startSafe(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    }

    /** Mở trực tiếp trang cấp quyền tự bật app (Overlay; tự fallback Màn hình chính nếu ROM không có Overlay). */
    fun openAutoLaunchSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && canResolveOverlaySettings(context)) {
            TvAutoBootHelper.openOverlayPermissionSettings(context)
        } else {
            TvAutoBootHelper.enableTvHomeLauncherAndPrompt(context)
        }
    }

    /** Mở trang Cài đặt thông báo ứng dụng (dùng khi hộp thoại hệ thống bị từ chối vĩnh viễn). */
    fun openNotificationSettings(context: Context) {
        val opened = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startSafe(context, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        } else false
        if (!opened) {
            startSafe(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        }
    }

    /** Mở trang cho phép cài đặt bản cập nhật APK (Nguồn không xác định, Android 8+). */
    fun openInstallUnknownAppsSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val direct = startSafe(context, Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            if (direct) return
        }
        startSafe(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    }

    /** Kiểm tra có nên mở thẳng Cài đặt Thông báo thay vì gọi lại popup hệ thống đã bị tắt vĩnh viễn hay không. */
    fun shouldOpenNotificationSettingsDirectly(activity: Activity, requestedBefore: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        if (!requestedBefore) return false
        return !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun canResolveOverlaySettings(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && (
            canResolve(context, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) ||
                canResolve(context, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            )

    private fun canResolveHomeSettings(context: Context): Boolean =
        canResolve(context, Intent(Settings.ACTION_HOME_SETTINGS)) ||
            canResolve(context, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))

    @SuppressLint("BatteryLife")
    private fun canResolveBatterySettings(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && (
            canResolve(context, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))) ||
                canResolve(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            )

    private fun canResolveInstallSources(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            canResolve(context, Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))

    private fun canResolve(context: Context, intent: Intent): Boolean = runCatching {
        intent.resolveActivity(context.packageManager) != null
    }.getOrDefault(false)

    private fun startSafe(context: Context, intent: Intent): Boolean = runCatching {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}
