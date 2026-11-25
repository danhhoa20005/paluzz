package com.edusing.chamcongtvbox.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.edusing.chamcongtvbox.AttendanceTvApp

/**
 * Tự động khởi chạy dịch vụ nền 24/7 và mở thẳng giao diện chấm công trên TV Box sau khi mất điện có lại:
 * - Hỗ trợ đầy đủ tín hiệu boot chuẩn (`BOOT_COMPLETED`), khởi động nhanh (`QUICKBOOT_POWERON`),
 *   box Trung Quốc (`com.intent.action.BOOT`), `LOCKED_BOOT_COMPLETED`, `REBOOT` và cập nhật gói.
 * - Mở thẳng `MainActivity` kể cả khi phiên vừa hết nhưng vẫn còn lưu tài khoản/mật khẩu để tự chờ mạng & đăng nhập.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (!isBootAction(action)) return

        val appCtx = context.applicationContext
        if (!TvAutoBootHelper.isUserUnlocked(appCtx)) return

        val app = appCtx as? AttendanceTvApp ?: return
        val cfg = app.container.configRepository.getConfig()
        if (cfg.autoStartOnBoot) {
            AttendanceForegroundService.start(appCtx, fromBoot = true)
            TvAutoBootHelper.launchMainActivitySafe(appCtx)
        }
    }

    companion object {
        private val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_REBOOT,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            "com.intent.action.BOOT",
        )

        internal fun isBootAction(action: String): Boolean = action in BOOT_ACTIONS
    }
}
