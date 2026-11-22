package com.edusing.chamcongtvbox.presentation.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Bộ token **Apple iOS 26 Light Liquid Glass — Chuẩn Siêu Nét AAAAA**:
 * - Nền sáng sương dịu (`#E8EEF5`) tách bạch rõ rệt khỏi thẻ kính pha lê trắng tinh (`#FFFFFF`) để không bị chìm đơn sắc.
 * - Quy tắc 85-10-5: 85% nền/kính trắng sáng sạch, **1 màu nhấn chủ đạo Xanh Apple (`#0A58CA`)**, 5% màu ngữ nghĩa trung thực (Xanh lá / Đỏ / Hổ phách) chỉ cho trạng thái.
 * - Mọi cấp chữ đạt tương phản **>= 7.5:1 đến 18.9:1 (WCAG 2.2 AAA+)** dễ nhìn tức thì cả trên điện thoại lẫn TV Box từ xa 3m.
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
    // Nền sáng có chiều sâu sương xanh rất nhẹ để thẻ kính trắng tinh bật nổi 100% (không chìm trắng-trên-trắng)
    canvas = Color(0xFFE7EDF5),
    canvasTop = Color(0xFFECF1F8),
    canvasBrush = Brush.verticalGradient(listOf(Color(0xFFECF2F9), Color(0xFFE7EDF5), Color(0xFFE1E8F2))),
    // Ánh sáng ban ngày nhẹ nhàng 1 tông xanh trời rất nhạt (tạo chiều sâu kính mà không loang nhiều màu)
    auraCobalt = Color(0x3689B4F8),
    auraTeal = Color(0x229EC5F8),
    auraViolet = Color(0x1AC7D7EE),
    auraSky = Color(0x26A8CCF8),
    // Tầng CHROME: kính trắng pha lê sáng trong, viền sáng + viền xám xanh rõ nét
    chromeGlassBrush = Brush.verticalGradient(listOf(Color(0xF5FFFFFF), Color(0xE0F6F9FD))),
    chromeRimBrush = Brush.linearGradient(listOf(Color(0xFFFFFFFF), Color(0xFFB5C5DA), Color(0xF0FFFFFF))),
    card = Color(0xFFFFFFFF),
    // Tầng CONTENT: tấm kính trắng sáng rạch ròi với bóng nền rõ khối
    glassSurfaceBrush = Brush.verticalGradient(listOf(Color(0xFAFFFFFF), Color(0xEBF8FAFD))),
    glassRimBrush = Brush.linearGradient(listOf(Color(0xFFFFFFFF), Color(0xFFBCCCE0), Color(0xF2FFFFFF))),
    specularTopAlpha = 1.0f,
    sheenChromeAlpha = 0.34f,
    sheenContentAlpha = 0.18f,
    causticChromeAlpha = 0.56f,
    causticContentAlpha = 0.34f,
    scrollEdgeFade = Color(0xEBE7EDF5),
    segThumbTint = Color(0xFFFFFFFF),
    inputFocusedBg = Color(0xFFFFFFFF),
    surfaceSubtle = Color(0xFFF3F6FB),
    surfaceMuted = Color(0xFFDCE5F1),
    border = Color(0xFFB8C6DA),
    shadowTint = Color(0x2E0B1D3A),
    // Hệ chữ siêu sắc nét AAA+ (18.9:1 / 11.8:1 / 7.9:1 trên nền kính trắng)
    text = Color(0xFF09111F),
    textSecondary = Color(0xFF27364B),
    muted = Color(0xFF42526B),
    // 1 MÀU NHẤN CHỦ ĐẠO DUY NHẤT: Xanh Apple sắc nét (#0A52BD — 7.6:1 AAA với chữ trắng)
    primary = Color(0xFF0A52BD),
    primaryDark = Color(0xFF073B8A),
    primaryText = Color(0xFF0846A3),
    primarySoft = Color(0xFFE3EEFF),
    heroIdleColor = Color(0xFF0A52BD),
    heroRunningColor = Color(0xFF05603A),
    heroSubtitle = Color(0xFF27364B),
    // Màu ngữ nghĩa trung thực, rõ ràng ngay trong 0.1 giây (chỉ dùng cho trạng thái & hành động quan trọng)
    green = Color(0xFF05603A),
    greenBright = Color(0xFF16A34A),
    greenSoft = Color(0xFFDCFCE7),
    greenText = Color(0xFF044E2F),
    red = Color(0xFFA31624),
    redText = Color(0xFF8F111E),
    redSoft = Color(0xFFFEE4E6),
    amber = Color(0xFF92400E),
    amberBright = Color(0xFFD97706),
    amberSoft = Color(0xFFFEF3C7),
    amberText = Color(0xFF78350F),
    info = Color(0xFF0A52BD),
    infoText = Color(0xFF0846A3),
    infoSoft = Color(0xFFE3EEFF),
    // Thống nhất 1 tông nhấn chính + sắc độ tinh gọn thay vì cầu vồng
    orbCobalt = Color(0xFF0A52BD),
    orbTeal = Color(0xFF05603A),
    orbAmber = Color(0xFF92400E),
    orbViolet = Color(0xFF0A52BD),
    orbRose = Color(0xFFB41B2A),
)

val LocalLiquidPalette = staticCompositionLocalOf { DaylightLiquidPalette }
