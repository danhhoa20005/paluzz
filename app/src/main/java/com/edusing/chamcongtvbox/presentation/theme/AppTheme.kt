package com.edusing.chamcongtvbox.presentation.theme

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Khoảng trống đáy tự động để nội dung cuộn trôi mượt phía sau thanh viên thuốc Liquid Glass nổi. */
val LocalFloatingNavBottomInset = staticCompositionLocalOf { 0.dp }

/** Thang khoảng cách bội số 4/8dp đồng nhất tuyệt đối cho Điện thoại, Tablet và TV Box. */
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

/** Thang bo góc cong liên tục chuẩn Apple iOS 26 Liquid Glass. */
object TammiRadii {
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 22.dp
    val xxl = 28.dp
    val pill = 999.dp

    val cardShape = RoundedCornerShape(xl)
    val panelShape = RoundedCornerShape(xxl)
    val inputShape = RoundedCornerShape(lg)
    val buttonShape = RoundedCornerShape(lg)
    val badgeShape = RoundedCornerShape(pill)
}

/**
 * Cổng đọc màu ngữ nghĩa **Liquid Glass 3D** động (tự chuyển mượt giữa Daylight Crystal & Midnight Obsidian):
 * đảm bảo **WCAG 2.2 AAA** cả ngày lẫn đêm mà không cần viết lại từng màn hình.
 */
object AppColors {
    private val p: LiquidPalette
        @Composable @ReadOnlyComposable get() = LocalLiquidPalette.current

    val isDark: Boolean @Composable @ReadOnlyComposable get() = p.isDark
    val Canvas: Color @Composable @ReadOnlyComposable get() = p.canvas
    val CanvasBrush: Brush @Composable @ReadOnlyComposable get() = p.canvasBrush
    val AuraCobalt: Color @Composable @ReadOnlyComposable get() = p.auraCobalt
    val AuraTeal: Color @Composable @ReadOnlyComposable get() = p.auraTeal
    val AuraViolet: Color @Composable @ReadOnlyComposable get() = p.auraViolet
    val AuraSky: Color @Composable @ReadOnlyComposable get() = p.auraSky
    val Bg: Color @Composable @ReadOnlyComposable get() = p.canvas

    val ChromeGlassBrush: Brush @Composable @ReadOnlyComposable get() = p.chromeGlassBrush
    val ChromeRimBrush: Brush @Composable @ReadOnlyComposable get() = p.chromeRimBrush
    val Card: Color @Composable @ReadOnlyComposable get() = p.card
    val GlassSurfaceBrush: Brush @Composable @ReadOnlyComposable get() = p.glassSurfaceBrush
    val GlassRimBrush: Brush @Composable @ReadOnlyComposable get() = p.glassRimBrush
    val ScrollEdgeFade: Color @Composable @ReadOnlyComposable get() = p.scrollEdgeFade
    val SegThumbTint: Color @Composable @ReadOnlyComposable get() = p.segThumbTint
    val InputFocusedBg: Color @Composable @ReadOnlyComposable get() = p.inputFocusedBg

    val SurfaceSubtle: Color @Composable @ReadOnlyComposable get() = p.surfaceSubtle
    val SurfaceMuted: Color @Composable @ReadOnlyComposable get() = p.surfaceMuted
    val Border: Color @Composable @ReadOnlyComposable get() = p.border
    val Line: Color @Composable @ReadOnlyComposable get() = p.border
    val ShadowTint: Color @Composable @ReadOnlyComposable get() = p.shadowTint

    val Text: Color @Composable @ReadOnlyComposable get() = p.text
    val TextSecondary: Color @Composable @ReadOnlyComposable get() = p.textSecondary
    val Muted: Color @Composable @ReadOnlyComposable get() = p.muted
    val Gray: Color @Composable @ReadOnlyComposable get() = p.muted

    val Primary: Color @Composable @ReadOnlyComposable get() = p.primary
    val PrimaryDark: Color @Composable @ReadOnlyComposable get() = p.primaryDark
    val PrimaryText: Color @Composable @ReadOnlyComposable get() = p.primaryText
    val PrimarySoft: Color @Composable @ReadOnlyComposable get() = p.primarySoft

    val HeroIdleColor: Color @Composable @ReadOnlyComposable get() = p.heroIdleColor
    val HeroRunningColor: Color @Composable @ReadOnlyComposable get() = p.heroRunningColor
    val HeroSubtitle: Color @Composable @ReadOnlyComposable get() = p.heroSubtitle

    val Green: Color @Composable @ReadOnlyComposable get() = p.green
    val GreenBright: Color @Composable @ReadOnlyComposable get() = p.greenBright
    val GreenSoft: Color @Composable @ReadOnlyComposable get() = p.greenSoft
    val GreenText: Color @Composable @ReadOnlyComposable get() = p.greenText

    val Red: Color @Composable @ReadOnlyComposable get() = p.red
    val RedText: Color @Composable @ReadOnlyComposable get() = p.redText
    val RedSoft: Color @Composable @ReadOnlyComposable get() = p.redSoft

    val Amber: Color @Composable @ReadOnlyComposable get() = p.amber
    val AmberBright: Color @Composable @ReadOnlyComposable get() = p.amberBright
    val AmberSoft: Color @Composable @ReadOnlyComposable get() = p.amberSoft
    val AmberText: Color @Composable @ReadOnlyComposable get() = p.amberText

    val Info: Color @Composable @ReadOnlyComposable get() = p.info
    val InfoText: Color @Composable @ReadOnlyComposable get() = p.infoText
    val InfoSoft: Color @Composable @ReadOnlyComposable get() = p.infoSoft

    val OrbCobalt: Color @Composable @ReadOnlyComposable get() = p.orbCobalt
    val OrbTeal: Color @Composable @ReadOnlyComposable get() = p.orbTeal
    val OrbAmber: Color @Composable @ReadOnlyComposable get() = p.orbAmber
    val OrbViolet: Color @Composable @ReadOnlyComposable get() = p.orbViolet
    val OrbRose: Color @Composable @ReadOnlyComposable get() = p.orbRose

    // Kiosk 24/7 luôn giữ nền đêm sâu chống lưu ảnh OLED/TV (AAA >= 9.69:1)
    val KioskBg = Color(0xFF040812)
    val KioskCard = Color(0xD60C162B)
    val KioskBorder = Color(0xFF314970)
    val KioskSuccess = Color(0xFF56EA9E)
    val KioskTextSecondary = Color(0xFFD0DDF0)
    val KioskTextMuted = Color(0xFFAABDD8)
    val FocusRing = Color(0xFFFACC15)

    /** Chuyển màu nhấn sang tông chữ AAA trên bề mặt kính (đặc biệt khi ở Dark Mode). */
    @Composable @ReadOnlyComposable
    fun readableAccent(color: Color): Color = if (!p.isDark) color else when (color) {
        p.primary, p.primaryDark, p.orbCobalt -> p.primaryText
        p.green, p.orbTeal -> p.greenText
        p.red, p.orbRose -> p.redText
        p.amber, p.orbAmber -> p.amberText
        p.info -> p.infoText
        else -> color
    }
}

/** Đọc tuỳ chọn Giảm chuyển động (Reduced Motion) của Android để tôn trọng trợ năng. */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
}

/**
 * Nền không gian **Living Liquid Glass Optical Mesh**:
 * 4 quầng sáng Cobalt / Teal / Violet / Sky dịch chuyển siêu êm (chu kỳ 22 giây, 60fps)
 * tạo cảm giác khúc xạ chất lỏng sống động phía sau các tấm kính (tự đứng yên khi bật Reduced Motion).
 */
@Composable
fun LiquidGlassBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val palette = LocalLiquidPalette.current
    val reducedMotion = rememberReducedMotion()
    val phase = if (reducedMotion) 0f else {
        val transition = rememberInfiniteTransition(label = "liquidAuraMesh")
        val anim by transition.animateFloat(
            initialValue = 0f,
            targetValue = (2.0 * PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(22_000, easing = LinearEasing), RepeatMode.Restart),
            label = "auraPhase",
        )
        anim
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(palette.canvasBrush)
            .drawBehind {
                val r = size.maxDimension * 0.58f
                val driftX = size.width * 0.045f * cos(phase)
                val driftY = size.height * 0.035f * sin(phase)
                val c1 = Offset(size.width * 0.86f + driftX, size.height * 0.09f + driftY)
                val c2 = Offset(size.width * 0.12f - driftX, size.height * 0.42f + driftY * 0.8f)
                val c3 = Offset(size.width * 0.82f - driftX * 0.7f, size.height * 0.84f - driftY)
                val c4 = Offset(size.width * 0.28f + driftX * 0.6f, size.height * 0.90f - driftY * 0.6f)
                drawCircle(Brush.radialGradient(listOf(palette.auraCobalt, Color.Transparent), c1, r), r, c1)
                drawCircle(Brush.radialGradient(listOf(palette.auraTeal, Color.Transparent), c2, r * 0.92f), r * 0.92f, c2)
                drawCircle(Brush.radialGradient(listOf(palette.auraViolet, Color.Transparent), c3, r * 0.88f), r * 0.88f, c3)
                drawCircle(Brush.radialGradient(listOf(palette.auraSky, Color.Transparent), c4, r * 0.74f), r * 0.74f, c4)
            },
        content = content,
    )
}

@Composable
fun AppTvTheme(
    content: @Composable () -> Unit,
) {
    val palette = DaylightLiquidPalette
    val colorScheme = lightColorScheme(
        primary = palette.primary, onPrimary = Color.White, primaryContainer = palette.primarySoft, onPrimaryContainer = palette.text,
        secondary = palette.info, onSecondary = Color.White, tertiary = palette.green,
        error = palette.red, background = palette.canvas, onBackground = palette.text,
        surface = palette.card, onSurface = palette.text, surfaceVariant = palette.surfaceMuted, onSurfaceVariant = palette.textSecondary, outline = palette.border,
    )
    CompositionLocalProvider(LocalLiquidPalette provides palette) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}

/** Viền focus tương phản chuẩn AAA cho D-Pad TV Box (tự thích ứng nền sáng/tối). */
@Composable
fun Modifier.tvFocusRing(
    focused: Boolean,
    cornerRadiusDp: Int = 16,
    onDark: Boolean = false,
): Modifier = if (focused) {
    border(BorderStroke(3.dp, if (onDark) AppColors.FocusRing else AppColors.PrimaryDark), RoundedCornerShape(cornerRadiusDp.dp))
} else this
