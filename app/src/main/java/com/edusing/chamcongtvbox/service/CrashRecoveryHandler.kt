package com.edusing.chamcongtvbox.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.edusing.chamcongtvbox.AttendanceTvApp

/**
 * Cơ chế tự hồi sinh sau sự cố crash không lường trước dành cho TV Box chạy mù 24/7 (không màn hình):
 * - Bắt mọi `UncaughtException`, hẹn `AlarmManager` khởi chạy lại `AttendanceForegroundService` sau 3 giây.
 * - Có bộ đếm chống vòng lặp crash (tối đa 5 lần trong 2 phút) để tránh treo thiết bị nếu gặp lỗi phần cứng.
 */
object CrashRecoveryHandler {

    private const val TAG = "CrashRecoveryHandler"
    private const val PREFS_NAME = "chamcong_crash_guard_v1"
    private const val KEY_TIMESTAMPS = "crash_timestamps_csv"
    private const val KEY_LAST_ERROR = "last_crash_summary"
    private const val REQUEST_CODE = 1099
    private const val RESTART_DELAY_MS = 3_000L
    internal const val MAX_RESTARTS_IN_WINDOW = 5
    internal const val WINDOW_MS = 120_000L

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val appCtx = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                handleUncaught(appCtx, throwable)
            } catch (_: Throwable) {
                // Không để lỗi trong handler cản trở việc thoát tiến trình sạch
            } finally {
                previous?.uncaughtException(thread, throwable)
            }
        }
    }

    fun readLastCrashSummary(context: Context): String = runCatching {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_ERROR, "")
            .orEmpty()
    }.getOrDefault("")

    private fun handleUncaught(appCtx: Context, throwable: Throwable) {
        val now = System.currentTimeMillis()
        val summary = "${throwable.javaClass.simpleName}: ${throwable.message.orEmpty().take(160)}"
        runCatching { Log.e(TAG, "Phát hiện crash bất ngờ — chuẩn bị tự hồi sinh: $summary", throwable) }

        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_TIMESTAMPS, "")
            .orEmpty()
            .split(',')
            .mapNotNull { it.trim().toLongOrNull() }
        val decision = evaluateRestart(existing, now)
        prefs.edit()
            .putString(KEY_TIMESTAMPS, decision.updatedTimestamps.joinToString(","))
            .putString(KEY_LAST_ERROR, summary)
            .commit()

        if (!decision.allowRestart) return

        val app = appCtx as? AttendanceTvApp
        val cfg = runCatching { app?.container?.configRepository?.getConfig() }.getOrNull()
        if (cfg != null && !cfg.autoStartOnBoot) return

        scheduleServiceResurrection(appCtx)
    }

    private fun scheduleServiceResurrection(appCtx: Context) {
        runCatching {
            val intent = Intent(appCtx, AttendanceForegroundService::class.java).apply {
                action = AttendanceForegroundService.ACTION_BOOT_AUTOSTART
            }
            val flags = PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(appCtx, REQUEST_CODE, intent, flags)
            } else {
                PendingIntent.getService(appCtx, REQUEST_CODE, intent, flags)
            }
            val am = appCtx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            am?.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + RESTART_DELAY_MS, pi)
        }
    }

    internal data class RestartDecision(
        val allowRestart: Boolean,
        val updatedTimestamps: List<Long>,
    )

    internal fun evaluateRestart(
        recentTimestampsMs: List<Long>,
        nowMs: Long,
        maxRestarts: Int = MAX_RESTARTS_IN_WINDOW,
        windowMs: Long = WINDOW_MS,
    ): RestartDecision {
        val inWindow = recentTimestampsMs.filter { nowMs - it in 0L..windowMs }
        val allow = inWindow.size < maxRestarts
        val updated = (inWindow + nowMs).takeLast(maxRestarts)
        return RestartDecision(allowRestart = allow, updatedTimestamps = updated)
    }
}
