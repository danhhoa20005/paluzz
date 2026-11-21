package com.edusing.chamcongtvbox.presentation.theme

import android.Manifest
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.edusing.chamcongtvbox.service.TvAutoBootHelper
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Thông số bố cục thích ứng riêng biệt cho **Điện thoại**, **Tablet** và **Android TV Box**:
 * - **Điện thoại (`isCompact`, `< 600dp`)**: 1 cột gọn, thanh viên thuốc kính nổi đáy, thao tác 1 tay.
 * - **Android TV Box (`isTvDevice`)**: Bố cục 16:9 không thanh đỉnh dư thừa (sidebar kính nổi bên trái
 *   chiếm toàn chiều cao + 2 cột điều khiển rộng), lề an toàn overscan, cỡ chữ lớn nhìn xa 2–3m,
 *   nhận diện cả TV chuẩn Leanback lẫn Box AOSP phổ thông không cảm ứng.
 * - **Tablet / Ngang rộng (`isTwoPane`, `>= 760dp`)**: 2 cột cân đối + sidebar kính nổi bên trái.
 */
data class DeviceUiSpec(
    val widthDp: Int,
    val heightDp: Int,
    val isTvDevice: Boolean,
) {
    val isCompact: Boolean get() = widthDp < 600 && !isTvDevice
    val isTwoPane: Boolean get() = widthDp >= 760 || isTvDevice

    /** Chiều rộng thanh điều hướng kính nổi bên trái trên TV Box / Tablet. */
    val sidebarWidth: Dp get() = when {
        isTvDevice -> 212.dp
        widthDp >= 960 -> 200.dp
        else -> 182.dp
    }

    /** Lề ngang an toàn (TV Box chừa overscan rộng để viền TV không cắt giao diện). */
    val screenPadding: Dp get() = when {
        isTvDevice -> 22.dp
        isCompact -> 12.dp
        else -> 16.dp
    }
    val horizontalPadding: Dp get() = when {
        isTvDevice -> 22.dp
        isCompact -> 12.dp
        else -> 18.dp
    }
    val verticalPadding: Dp get() = when {
        isTvDevice -> 16.dp
        isCompact -> 10.dp
        else -> 14.dp
    }
    val cardSpacing: Dp get() = when {
        isTvDevice -> 14.dp
        isCompact -> 10.dp
        else -> 12.dp
    }
    val sectionTitleSize: TextUnit get() = when {
        isTvDevice -> 17.5.sp
        isCompact -> 15.sp
        else -> 16.sp
    }
    val bodyTextSize: TextUnit get() = when {
        isTvDevice -> 14.sp
        isCompact -> 12.5.sp
        else -> 13.sp
    }
}

/** Nhận diện chính xác cả Android TV chuẩn Leanback lẫn TV Box AOSP phổ thông không màn hình cảm ứng. */
internal fun detectIsTvDevice(context: Context, cfg: Configuration): Boolean {
    val pm = context.packageManager
    val uiMode = (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType
    if (uiMode == Configuration.UI_MODE_TYPE_TELEVISION) return true
    if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return true
    if (!pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) return true
    return cfg.screenWidthDp >= 800 &&
        cfg.screenHeightDp >= 450 &&
        cfg.screenWidthDp > cfg.screenHeightDp &&
        cfg.navigation == Configuration.NAVIGATION_DPAD
}

@Composable
fun rememberDeviceUiSpec(): DeviceUiSpec {
    val cfg = LocalConfiguration.current
    val context = LocalContext.current
    return DeviceUiSpec(
        widthDp = cfg.screenWidthDp,
        heightDp = cfg.screenHeightDp,
        isTvDevice = detectIsTvDevice(context, cfg),
    )
}

/** Tình trạng tối ưu thực tế của thiết bị để hiển thị trong màn Cấu hình 24/7. */
data class DeviceHealthInfo(
    val isTvDevice: Boolean,
    val formFactorLabel: String,
    val modelAndOs: String,
    val networkLabel: String,
    val localIp: String,
    val ignoringBatteryOptimizations: Boolean,
    val notificationGranted: Boolean,
    val canAutoOpenUiOnBoot: Boolean,
    val isTvHomeLauncherEnabled: Boolean,
    val isDefaultHomeLauncher: Boolean,
)

/** Đọc nhanh thông tin thiết bị, mạng LAN/Wi-Fi, quyền thông báo và trạng thái bỏ tối ưu pin. */
fun readDeviceHealthInfo(context: Context): DeviceHealthInfo {
    val cfg = context.resources.configuration
    val isTv = detectIsTvDevice(context, cfg)
    val smallestWidth = cfg.smallestScreenWidthDp
    val formFactor = when {
        isTv -> "Android TV Box"
        smallestWidth >= 600 -> "Máy tính bảng"
        else -> "Điện thoại"
    }

    val mfg = Build.MANUFACTURER.orEmpty().trim()
    val model = Build.MODEL.orEmpty().trim()
    val fullModel = if (model.startsWith(mfg, ignoreCase = true)) model else "$mfg $model".trim()
    val modelAndOs = "$fullModel • Android ${Build.VERSION.RELEASE}"

    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
    val networkLabel = when {
        caps == null -> "Chưa có mạng"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Mạng dây LAN"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "4G/5G (không cùng LAN)"
        else -> "Mạng khác"
    }

    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val ignoringBattery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        runCatching { pm?.isIgnoringBatteryOptimizations(context.packageName) == true }.getOrDefault(false)
    } else true

    val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else true

    return DeviceHealthInfo(
        isTvDevice = isTv,
        formFactorLabel = formFactor,
        modelAndOs = modelAndOs,
        networkLabel = networkLabel,
        localIp = detectLocalIpv4().ifBlank { "Chưa có IP" },
        ignoringBatteryOptimizations = ignoringBattery,
        notificationGranted = notifGranted,
        canAutoOpenUiOnBoot = TvAutoBootHelper.canAutoLaunchUiOnBoot(context),
        isTvHomeLauncherEnabled = TvAutoBootHelper.isTvHomeAliasEnabled(context),
        isDefaultHomeLauncher = TvAutoBootHelper.isDefaultHomeLauncher(context),
    )
}

private fun detectLocalIpv4(): String = runCatching {
    val ifaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
    ifaces.asSequence()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .filterNot { it.name.startsWith("tun") || it.name.startsWith("wg") || it.name.startsWith("tailscale") }
        .flatMap { it.interfaceAddresses.asSequence() }
        .mapNotNull { it.address as? Inet4Address }
        .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
        ?.hostAddress.orEmpty()
}.getOrDefault("")
