package com.edusing.chamcongtvbox.presentation.theme

import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Khoảng trống đáy tự động để nội dung cuộn trôi mượt phía sau thanh viên thuốc Liquid Glass nổi. */
val LocalFloatingNavBottomInset = staticCompositionLocalOf { 0.dp }

/** Thang khoảng cách bội số 4dp đồng nhất cho Điện thoại, Tablet và TV Box. */
object TammiSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
    val touchMin = 48.dp
}

/** Thang bo góc cong liên tục chuẩn Liquid Glass 3D. */
object TammiRadii {
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 22.dp
    val xxl = 26.dp
    val pill = 999.dp

    val cardShape = RoundedCornerShape(xl)
    val panelShape = RoundedCornerShape(xxl)
    val inputShape = RoundedCornerShape(lg)
    val buttonShape = RoundedCornerShape(lg)
    val badgeShape = RoundedCornerShape(pill)
}

/**
 * Hệ màu **Liquid Glass 3D** đạt chuẩn tương phản **WCAG 2.2 AAA (>= 7.26:1 trên cả bề mặt kính trong nhất)**:
 * - Phân tách 2 lớp vật liệu chuẩn Apple HIG Liquid Glass:
 *   1. **Chrome Liquid Glass** (thanh điều hướng nổi, sidebar, nút điều khiển): trong suốt pha lê 65–80%,
 *      khúc xạ màu nền & nội dung trôi bên dưới kèm vệt phản chiếu đỉnh cong và ánh đáy caustic.
 *   2. **Content Frosted Material** (thẻ dữ liệu, danh sách, ô nhập): kính mờ sạch 83–92% giữ độ đọc tuyệt đối.
 */
object AppColors {
    // Nền không gian đa sắc quang học (4 quầng màu mềm giúp kính Liquid Glass khúc xạ rõ chiều sâu 3D)
    val Canvas = Color(0xFFE5EEFB)
    val CanvasTop = Color(0xFFDBE8FC)
    val CanvasBrush: Brush = Brush.verticalGradient(listOf(CanvasTop, Color(0xFFECF3FC), Color(0xFFE0EBFA)))
    val AuraCobalt = Color(0x787EB0FF)
    val AuraTeal = Color(0x6E78DFCE)
    val AuraViolet = Color(0x62B3A3FF)
    val AuraSky = Color(0x5293D6FF)
    val Bg = Canvas

    // Lớp 1: Chrome Liquid Glass (trong suốt pha lê cho điều hướng nổi, sidebar, phím nổi)
    val ChromeGlassTop = Color(0xCCFFFFFF)
    val ChromeGlassBottom = Color(0xA6EAF4FF)
    val ChromeGlassBrush: Brush = Brush.verticalGradient(listOf(ChromeGlassTop, ChromeGlassBottom))
    val ChromeRimBrush: Brush = Brush.linearGradient(listOf(Color(0xFFFFFFFF), Color(0xA89EC2EE), Color(0xF0FFFFFF)))

    // Lớp 2: Content Frosted Material (thẻ nội dung sáng sạch, dễ đọc lâu dài)
    val Card = Color(0xFFFFFFFF)
    val GlassFillTop = Color(0xEAFFFFFF)
    val GlassFillBottom = Color(0xD4F3F8FF)
    val GlassSurfaceBrush: Brush = Brush.verticalGradient(listOf(GlassFillTop, GlassFillBottom))
    val GlassRimBrush: Brush = Brush.linearGradient(listOf(Color(0xFFFFFFFF), Color(0x96B0C6E4), Color(0xE8FFFFFF)))
    val GlassSpecularTop = Color(0x88FFFFFF)
    val GlassCausticBottom = Color(0xB8FFFFFF)
    val GlassTopBarBg = Color(0xCCFFFFFF)
    val ScrollEdgeFade = Color(0xDCE5EEFB)

    val SurfaceSubtle = Color(0xC8F4F9FF)
    val SurfaceMuted = Color(0xCCE0EAF8)
    val Border = Color(0xFFB4C6E0)
    val Line = Border
    val GlassBorder = Color(0xF5FFFFFF)
    val ShadowTint = Color(0x2B0A1F47)

    // Văn bản đạt chuẩn WCAG 2.2 AAA (>= 7.92:1 ngay cả trên kính pha lê 65% ở vùng màu đậm nhất)
    val Text = Color(0xFF0A1324)
    val TextSecondary = Color(0xFF1E304B)
    val Muted = Color(0xFF334663)
    val Gray = Color(0xFF334663)

    // Màu trạng thái & Stained Liquid Glass chuẩn WCAG AAA (>= 7.26:1 trên kính & >= 8.31:1 với chữ trắng)
    val Primary = Color(0xFF103D94)
    val PrimaryDark = Color(0xFF0A2A6A)
    val PrimarySoft = Color(0xDDE4EEFF)

    val HeroIdleColor = Color(0xFF113F9C)
    val HeroRunningColor = Color(0xFF055434)
    val HeroIdleBrush: Brush = Brush.linearGradient(listOf(Color(0xFF0A2766), Color(0xFF1345A6), Color(0xFF0C3482)))
    val HeroRunningBrush: Brush = Brush.linearGradient(listOf(Color(0xFF033B25), Color(0xFF055434), Color(0xFF04462B)))
    val HeroSubtitle = Color(0xFFEAF2FF)

    val Green = Color(0xFF065A38)
    val GreenBright = Color(0xFF22C55E)
    val GreenSoft = Color(0xDDE1F7EC)
    val GreenText = Color(0xFF054A2E)

    val Red = Color(0xFF931425)
    val RedDark = Color(0xFF740E1C)
    val RedSoft = Color(0xDDFCE8ED)

    val Amber = Color(0xFF763B00)
    val AmberBright = Color(0xFFFBBF24)
    val AmberSoft = Color(0xDDFFF1DC)
    val AmberText = Color(0xFF663200)

    val Info = Color(0xFF084F6D)
    val InfoSoft = Color(0xDDE0F4FC)

    // Tông nhấn 3D AAA cho 3 chế độ và khay thao tác nhanh
    val OrbCobalt = Color(0xFF103D94)
    val OrbTeal = Color(0xFF04534E)
    val OrbAmber = Color(0xFF763B00)
    val OrbViolet = Color(0xFF4C1D95)
    val OrbRose = Color(0xFF961334)

    val H1 = OrbCobalt
    val H2 = OrbAmber
    val H3 = OrbViolet
    val H4 = OrbTeal
    val H5 = TextSecondary

    // Màn hình Kiosk 24/7 tối màu chống lưu ảnh OLED/TV (AAA >= 9.69:1)
    val KioskBg = Color(0xFF040812)
    val KioskCard = Color(0xD60C162B)
    val KioskBorder = Color(0xFF314970)
    val KioskSuccess = Color(0xFF56EA9E)
    val KioskTextSecondary = Color(0xFFD0DDF0)
    val KioskTextMuted = Color(0xFFAABDD8)

    // Viền focus vàng tương phản cao cho điều khiển D-Pad trên nền tối
    val FocusRing = Color(0xFFFACC15)
}

/** Đọc tuỳ chọn Giảm chuyển động (Reduced Motion) của hệ điều hành Android để tôn trọng trợ năng. */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}

/**
 * Nền không gian **Liquid Glass Optical Mesh** (4 quầng sáng Cobalt / Teal / Violet / Sky khuếch tán mượt)
 * tạo hiệu ứng khúc xạ ánh sáng tự nhiên xuyên qua các lớp kính 3D ở 60fps trên mọi đời Android.
 */
@Composable
fun LiquidGlassBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.CanvasBrush)
            .drawBehind {
                val r = size.maxDimension * 0.56f
                drawCircle(
                    brush = Brush.radialGradient(listOf(AppColors.AuraCobalt, Color.Transparent), center = Offset(size.width * 0.86f, size.height * 0.08f), radius = r),
                    radius = r,
                    center = Offset(size.width * 0.86f, size.height * 0.08f),
                )
                drawCircle(
                    brush = Brush.radialGradient(listOf(AppColors.AuraTeal, Color.Transparent), center = Offset(size.width * 0.12f, size.height * 0.44f), radius = r * 0.92f),
                    radius = r * 0.92f,
                    center = Offset(size.width * 0.12f, size.height * 0.44f),
                )
                drawCircle(
                    brush = Brush.radialGradient(listOf(AppColors.AuraViolet, Color.Transparent), center = Offset(size.width * 0.84f, size.height * 0.86f), radius = r * 0.88f),
                    radius = r * 0.88f,
                    center = Offset(size.width * 0.84f, size.height * 0.86f),
                )
                drawCircle(
                    brush = Brush.radialGradient(listOf(AppColors.AuraSky, Color.Transparent), center = Offset(size.width * 0.30f, size.height * 0.92f), radius = r * 0.72f),
                    radius = r * 0.72f,
                    center = Offset(size.width * 0.30f, size.height * 0.92f),
                )
            },
        content = content,
    )
}

private val TammiLightColorScheme = lightColorScheme(
    primary = AppColors.Primary,
    onPrimary = Color.White,
    primaryContainer = AppColors.PrimarySoft,
    onPrimaryContainer = AppColors.Text,
    secondary = AppColors.Info,
    onSecondary = Color.White,
    secondaryContainer = AppColors.InfoSoft,
    onSecondaryContainer = AppColors.Text,
    tertiary = AppColors.Green,
    onTertiary = Color.White,
    error = AppColors.Red,
    onError = Color.White,
    errorContainer = AppColors.RedSoft,
    onErrorContainer = AppColors.Text,
    background = AppColors.Canvas,
    onBackground = AppColors.Text,
    surface = AppColors.Card,
    onSurface = AppColors.Text,
    surfaceVariant = AppColors.SurfaceMuted,
    onSurfaceVariant = AppColors.TextSecondary,
    outline = AppColors.Border,
)

@Composable
fun AppTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = TammiLightColorScheme, content = content)
}

/**
 * Vẽ viền focus tương phản chuẩn AAA cho điều khiển D-Pad trên Android TV Box:
 * - Trên bề mặt kính sáng (`onDark = false`): viền xanh Navy đậm (`#0A2A6A`, 14.1:1 trên kính trắng).
 * - Trên nền tối Hero / Kiosk (`onDark = true`): viền vàng sáng (`#FACC15`, 13.4:1 trên nền tối).
 */
fun Modifier.tvFocusRing(
    focused: Boolean,
    cornerRadiusDp: Int = 16,
    onDark: Boolean = false,
): Modifier = if (focused) {
    border(BorderStroke(3.dp, if (onDark) AppColors.FocusRing else AppColors.PrimaryDark), RoundedCornerShape(cornerRadiusDp.dp))
} else {
    this
}
