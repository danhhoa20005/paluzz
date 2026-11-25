package com.edusing.chamcongtvbox.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.edusing.chamcongtvbox.AttendanceTvApp
import com.edusing.chamcongtvbox.R
import com.edusing.chamcongtvbox.domain.repository.InternetStatus
import com.edusing.chamcongtvbox.presentation.MainActivity
import com.edusing.chamcongtvbox.service.web.LocalWebConsoleServer
import com.edusing.chamcongtvbox.service.web.WebConsoleStatusSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Dịch vụ nền 24/7 (`ForegroundService`) tối ưu không chạm cho Android TV Box và điện thoại:
 * - Giữ CPU không ngủ sâu (`PARTIAL_WAKE_LOCK`) và giữ Wi-Fi hiệu năng cao (`WifiLock`).
 * - Gắn [NetworkStateObserver] kép để phát hiện ngay mọi biến động Wi-Fi/LAN/Internet và tự phục hồi toàn diện.
 */
class AttendanceForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var networkObserver: NetworkStateObserver? = null
    private var webConsole: LocalWebConsoleServer? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundSafe(buildNotification("Đang khởi động cầu nối chấm công..."))

        val container = (application as AttendanceTvApp).container
        val engine = container.attendanceEngine
        engine.startBackgroundMonitors()
        container.appUpdateCoordinator.startBackgroundLoop()
        ServiceEnhancementsObserver.attach(serviceScope, container)
        acquireLocks()
        networkObserver = NetworkStateObserver(this, engine).also { it.register() }
        webConsole = LocalWebConsoleServer(
            context = this,
            apiHandler = WebConsoleStatusSerializer.createApiHandler(this, container) { webConsole?.refreshPublishedUrl() },
            hostProvider = { "${container.configRepository.getConfig().safeLocalHostName}.local" },
            onHikvisionWebhook = container::handleHikvisionWebhook,
            onPortBound = { port -> container.activeWebConsolePort = port },
            onListeningUrlChanged = engine::updateWebConsoleUrl,
        ).also { it.start() }

        serviceScope.launch {
            var prevHadLan = false
            combine(engine.state, container.configRepository.configFlow) { snapshot, _ ->
                if (snapshot.hasLanOrWifi && (!prevHadLan || snapshot.webConsoleUrl.isBlank())) {
                    webConsole?.refreshPublishedUrl()
                }
                prevHadLan = snapshot.hasLanOrWifi
                val title = container.configRepository.getBranding().shortAppName
                val body = when {
                    snapshot.wifiHardwareDisabled -> "Wi-Fi đang tắt — hãy bật Wi-Fi để tự nối lại & chờ quét"
                    !snapshot.hasLanOrWifi -> "Mất kết nối Wi-Fi/LAN — đang tự nối lại..."
                    snapshot.internetStatus == InternetStatus.OFFLINE -> "Mất mạng Internet — đang kiểm tra & tự kết nối lại..."
                    snapshot.internetStatus == InternetStatus.RECOVERING || snapshot.isDiscovering -> "Đã có mạng — đang tự quét & kết nối máy quét mặt..."
                    snapshot.isRunning -> "Đang chờ quét 24/7 • ${snapshot.connectedCount}/${snapshot.totalDeviceCount} máy • ${snapshot.totalCheckInsToday} lượt"
                    else -> "Sẵn sàng • ${snapshot.connectedCount}/${snapshot.totalDeviceCount} máy kết nối"
                }
                title to body
            }
                .distinctUntilChanged()
                .collect { (title, text) -> updateNotification(title, text) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as AttendanceTvApp).container
        val engine = container.attendanceEngine
        serviceScope.launch(Dispatchers.IO) {
            when (intent?.action) {
                ACTION_BOOT_AUTOSTART -> {
                    engine.startBackgroundMonitors()
                    var cfg = container.configRepository.getConfig()
                    if (cfg.autoStartOnBoot && !cfg.isLoggedIn && cfg.rememberAccount && cfg.username.isNotBlank() && cfg.password.isNotBlank()) {
                        runCatching { container.validateCredentialsUseCase() }
                        cfg = container.configRepository.getConfig()
                    }
                    if (cfg.autoStartOnBoot) {
                        serviceScope.launch {
                            if (!TvAutoBootHelper.isAppInForeground()) TvAutoBootHelper.launchMainActivitySafe(applicationContext)
                            delay(BOOT_LAUNCHER_GUARD_DELAY_MS)
                            if (container.configRepository.getConfig().autoStartOnBoot && !TvAutoBootHelper.isAppInForeground()) {
                                TvAutoBootHelper.launchMainActivitySafe(applicationContext)
                            }
                        }
                        if (cfg.isLoggedIn) engine.autoDiscoverConnectAndReady(fromInternetRecovery = false)
                    }
                }
                ACTION_STOP_SERVICE -> {
                    engine.disconnectAllAndStop()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                else -> engine.startBackgroundMonitors()
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Nếu bị gạt tác vụ hoặc Android TV thu hồi tiến trình UI, tự hẹn khởi chạy lại sau 2 giây để giữ 24/7
        runCatching {
            val cfg = (application as AttendanceTvApp).container.configRepository.getConfig()
            if (cfg.autoStartOnBoot) {
                val restart = Intent(applicationContext, AttendanceForegroundService::class.java).apply { action = ACTION_BOOT_AUTOSTART }
                val flags = PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    PendingIntent.getForegroundService(applicationContext, RESTART_REQUEST_CODE, restart, flags)
                } else {
                    PendingIntent.getService(applicationContext, RESTART_REQUEST_CODE, restart, flags)
                }
                (getSystemService(Context.ALARM_SERVICE) as? AlarmManager)
                    ?.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 2_000L, pi)
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        webConsole?.stop(); webConsole = null
        networkObserver?.unregister(); networkObserver = null
        releaseLocks(); serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundSafe(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(CHANNEL_ID, "Dịch vụ chấm công 24/7", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Giữ kết nối máy chấm công hoạt động liên tục trên TV Box"
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String, title: String = getString(R.string.app_name)): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.brand_logo)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(title: String, contentText: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(contentText, title))
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AppTVBox::AttendanceWakeLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        runCatching {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm?.createWifiLock(mode, "AppTVBox::AttendanceWifiLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        runCatching { wifiLock?.let { if (it.isHeld) it.release() } }
        wakeLock = null
        wifiLock = null
    }

    companion object {
        private const val CHANNEL_ID = "chamcong_tvbox_service"
        private const val NOTIFICATION_ID = 1001
        private const val RESTART_REQUEST_CODE = 1002
        private const val BOOT_LAUNCHER_GUARD_DELAY_MS = 3_500L
        const val ACTION_START = "com.edusing.chamcongtvbox.ACTION_START"
        const val ACTION_BOOT_AUTOSTART = "com.edusing.chamcongtvbox.ACTION_BOOT_AUTOSTART"
        const val ACTION_STOP_SERVICE = "com.edusing.chamcongtvbox.ACTION_STOP_SERVICE"

        fun start(context: Context, fromBoot: Boolean = false) {
            val intent = Intent(context, AttendanceForegroundService::class.java).apply {
                action = if (fromBoot) ACTION_BOOT_AUTOSTART else ACTION_START
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }
    }
}
