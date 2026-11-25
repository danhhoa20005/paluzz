package com.edusing.chamcongtvbox.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import com.edusing.chamcongtvbox.presentation.MainActivity
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bộ hỗ trợ tự mở thẳng ứng dụng trên Android TV Box sau khi mất điện có điện lại (như PC Startup):
 * - Lớp 1 (Khuyên dùng 100% mọi Android 7–15): Chế độ Màn hình chính TV Box (`TvHomeLauncherAlias` — tắt mặc định
 *   để không ảnh hưởng điện thoại; bật 1 chạm trong Cấu hình để TV Box boot thẳng vào app).
 * - Lớp 2 (Giữ Home cũ cho Android 10–15): Quyền `SYSTEM_ALERT_WINDOW` cho phép `BootReceiver` và
 *   `AttendanceForegroundService` mở bung `MainActivity` lên màn hình TV mà không bị Android chặn ngầm.
 */
object TvAutoBootHelper {

    private const val TV_HOME_ALIAS_CLASS = "com.edusing.chamcongtvbox.TvHomeLauncherAlias"

    private val foregroundFlag = AtomicBoolean(false)

    fun setAppInForeground(inForeground: Boolean) {
        foregroundFlag.set(inForeground)
    }

    fun isAppInForeground(): Boolean = foregroundFlag.get()

    /** Kiểm tra kho lưu trữ người dùng đã mở khoá (tránh đọc prefs lỗi lúc Direct Boot sớm). */
    fun isUserUnlocked(context: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val um = context.getSystemService(Context.USER_SERVICE) as? UserManager
            um?.isUserUnlocked != false
        } else {
            true
        }
    }.getOrDefault(true)

    /** Android 7–9 tự mở trực tiếp; Android 10+ cần quyền Hiển thị trên app khác hoặc làm Màn hình chính TV. */
    fun canAutoLaunchUiOnBoot(context: Context): Boolean = canAutoLaunchUiOnBootForSdk(
        sdkInt = Build.VERSION.SDK_INT,
        hasOverlayPermission = hasOverlayPermission(context),
        isDefaultHomeLauncher = isDefaultHomeLauncher(context),
    )

    internal fun canAutoLaunchUiOnBootForSdk(
        sdkInt: Int,
        hasOverlayPermission: Boolean,
        isDefaultHomeLauncher: Boolean,
    ): Boolean = isDefaultHomeLauncher || sdkInt < Build.VERSION_CODES.Q || hasOverlayPermission

    fun hasOverlayPermission(context: Context): Boolean = runCatching {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
    }.getOrDefault(false)

    fun isTvHomeAliasEnabled(context: Context): Boolean = runCatching {
        val comp = ComponentName(context.packageName, TV_HOME_ALIAS_CLASS)
        context.packageManager.getComponentEnabledSetting(comp) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    fun isDefaultHomeLauncher(context: Context): Boolean = runCatching {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = context.packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
        resolved?.activityInfo?.packageName == context.packageName
    }.getOrDefault(false)

    /** Bật alias Màn hình chính TV Box và mở hộp chọn Home mặc định (1 chạm). */
    fun enableTvHomeLauncherAndPrompt(context: Context) {
        setTvHomeAliasEnabled(context, enabled = true)
        val openedSettings = runCatching {
            val intent = Intent(Settings.ACTION_HOME_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)
        if (!openedSettings) {
            runCatching {
                val chooser = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(Intent.createChooser(chooser, "Chọn Màn hình chính mặc định").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    /** Tắt alias Màn hình chính TV Box để Android tự khôi phục ngay giao diện Home gốc của Box. */
    fun disableTvHomeLauncher(context: Context) {
        setTvHomeAliasEnabled(context, enabled = false)
    }

    /** Mở màn hình cấp quyền "Hiển thị trên ứng dụng khác" (Android 10+ dùng để tự bật app lúc khởi động). */
    fun openOverlayPermissionSettings(context: Context) {
        val opened = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                context.startActivity(intent)
                true
            }.getOrDefault(false)
        } else {
            false
        }
        if (!opened) {
            runCatching {
                val fallback = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}"),
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                context.startActivity(fallback)
            }
        }
    }

    /** Mở Cài đặt hệ thống của TV Box (lối thoát nhanh khi đang bật chế độ Màn hình chính). */
    fun openSystemSettings(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Mở thẳng [MainActivity] lên tiền cảnh an toàn sau khi TV Box vừa có điện lại. */
    fun launchMainActivitySafe(context: Context): Boolean = runCatching {
        val launch = Intent(context.applicationContext, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
            putExtra(EXTRA_FROM_BOOT, true)
        }
        context.applicationContext.startActivity(launch)
        true
    }.getOrDefault(false)

    private fun setTvHomeAliasEnabled(context: Context, enabled: Boolean) {
        runCatching {
            val comp = ComponentName(context.packageName, TV_HOME_ALIAS_CLASS)
            val newState = if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            context.packageManager.setComponentEnabledSetting(comp, newState, PackageManager.DONT_KILL_APP)
        }
    }

    const val EXTRA_FROM_BOOT = "extra_from_boot"
}
