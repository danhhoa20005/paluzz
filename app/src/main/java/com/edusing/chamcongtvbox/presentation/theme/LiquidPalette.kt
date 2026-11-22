package com.edusing.chamcongtvbox.presentation.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Bộ token quang học **Apple iOS 26 Clear Liquid Glass (Sáng — Tối giản — Ít màu sắc)**:
 * nền trắng sứ pha lê sạch, khúc xạ ánh sáng bạc dịu nhẹ, phân cấp chủ yếu bằng độ trong suốt & mực than chì
 * đạt chuẩn tương phản **WCAG 2.2 AAA** mà không rực màu hay rối mắt.
 */
@Immutable
data class LiquidPalette(
    val isDark: Boolean,
    val canvas: Color,
    val canvasTop: Color,
    val canvasBrush: Brush,
    val auraCobalt: Color,
    val auraTeal: Color,
    val auraViolet: Color,
    val auraSky: Color,
    val chromeGlassBrush: Brush,
    val chromeRimBrush: Brush,
    val card: Color,
    val glassSurfaceBrush: Brush,
    val glassRimBrush: Brush,
    val specularTopAlpha: Float,
    val sheenChromeAlpha: Float,
    val sheenContentAlpha: Float,
    val causticChromeAlpha: Float,
    val causticContentAlpha: Float,
    val scrollEdgeFade: Color,
    val segThumbTint: Color,
    val inputFocusedBg: Color,
    val surfaceSubtle: Color,
    val surfaceMuted: Color,
    val border: Color,
    val shadowTint: Color,
    val text: Color,
    val textSecondary: Color,
    val muted: Color,
    val primary: Color,
    val primaryDark: Color,
    val primaryText: Color,
    val primarySoft: Color,
    val heroIdleColor: Color,
    val heroRunningColor: Color,
    val heroSubtitle: Color,
    val green: Color,
    val greenBright: Color,
    val greenSoft: Color,
    val greenText: Color,
    val red: Color,
    val redText: Color,
    val redSoft: Color,
    val amber: Color,
    val amberBright: Color,
    val amberSoft: Color,
    val amberText: Color,
    val info: Color,
    val infoText: Color,
    val infoSoft: Color,
    val orbCobalt: Color,
    val orbTeal: Color,
    val orbAmber: Color,
    val orbViolet: Color,
    val orbRose: Color,
)

internal val DaylightLiquidPalette = LiquidPalette(
    isDark = false,
    canvas = Color(0xFFF3F5F8),
    canvasTop = Color(0xFFF7F8FA),
    canvasBrush = Brush.verticalGradient(listOf(Color(0xFFF7F8FA), Color(0xFFF3F5F8), Color(0xFFEDF0F5))),
    // Ánh sáng nền trung tính ngọc trai / sương bạc siêu dịu (không loang màu cầu vồng)
    auraCobalt = Color(0x28CBD5E1),
    auraTeal = Color(0x20D8E2EC),
    auraViolet = Color(0x1EE2E8F0),
    auraSky = Color(0x1CD0DAE6),
    // Tầng CHROME: kính pha lê trắng trong mờ tinh khiết, viền sáng mảnh sắc nét
    chromeGlassBrush = Brush.verticalGradient(listOf(Color(0xDEFFFFFF), Color(0xB8F5F7FA))),
    chromeRimBrush = Brush.linearGradient(listOf(Color(0xFFFFFFFF), Color(0x88C4CCD8), Color(0xF5FFFFFF))),
    card = Color(0xFFFFFFFF),
    // Tầng CONTENT: kính tuyết mờ trong nhẹ, tách khối tinh tế trên nền sáng
    glassSurfaceBrush = Brush.verticalGradient(listOf(Color(0xEDFFFFFF), Color(0xD8F8F9FB))),
    glassRimBrush = Brush.linearGradient(listOf(Color(0xFFFFFFFF), Color(0x75CCD4DF), Color(0xEEFFFFFF))),
    specularTopAlpha = 0.96f,
    sheenChromeAlpha = 0.36f,
    sheenContentAlpha = 0.22f,
    causticChromeAlpha = 0.54f,
    causticContentAlpha = 0.32f,
    scrollEdgeFade = Color(0xE8F3F5F8),
    segThumbTint = Color(0xFAFFFFFF),
    inputFocusedBg = Color(0xFFFFFFFF),
    surfaceSubtle = Color(0xCCF4F6F9),
    surfaceMuted = Color(0xD4E6EAF0),
    border = Color(0xFFD2D8E2),
    shadowTint = Color(0x1E0F172A),
    // Mực chữ đơn sắc Than chì / Xám đá chuẩn WCAG 2.2 AAA (>= 7.5:1)
    text = Color(0xFF0F172A),
    textSecondary = Color(0xFF334155),
    muted = Color(0xFF4B5563),
    // Màu nhấn chính tối giản: Than chì sâu (#1E293B — sang trọng, không chói màu)
    primary = Color(0xFF1E293B),
    primaryDark = Color(0xFF0F172A),
    primaryText = Color(0xFF1E293B),
    primarySoft = Color(0xD8EAEEF4),
    heroIdleColor = Color(0xFF1E293B),
    heroRunningColor = Color(0xFF1F5E47),
    heroSubtitle = Color(0xFF334155),
    // Các màu ngữ nghĩa tiết chế dịu mắt (chỉ dùng cho chấm trạng thái & nút xác nhận/cảnh báo)
    green = Color(0xFF1E5C45),
    greenBright = Color(0xFF22A06B),
    greenSoft = Color(0xD8EAF5F0),
    greenText = Color(0xFF174A37),
    red = Color(0xFF962534),
    redText = Color(0xFF881D2B),
    redSoft = Color(0xD8FAECEE),
    amber = Color(0xFF7A480D),
    amberBright = Color(0xFFD98A1C),
    amberSoft = Color(0xD8FBF3E4),
    amberText = Color(0xFF6B3E0A),
    info = Color(0xFF2B4C73),
    infoText = Color(0xFF234164),
    infoSoft = Color(0xD8EAF0F6),
    // Tông khoáng nhẹ thay vì màu rực
    orbCobalt = Color(0xFF1E293B),
    orbTeal = Color(0xFF2B4F54),
    orbAmber = Color(0xFF5C4B3A),
    orbViolet = Color(0xFF3E4259),
    orbRose = Color(0xFF7D2E3B),
)

val LocalLiquidPalette = staticCompositionLocalOf { DaylightLiquidPalette }
