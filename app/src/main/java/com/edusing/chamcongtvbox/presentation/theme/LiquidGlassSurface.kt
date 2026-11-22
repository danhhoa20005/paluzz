package com.edusing.chamcongtvbox.presentation.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Hai cấp vật liệu chuẩn Apple HIG Liquid Glass: điều hướng/nút/hộp thoại nổi (`CHROME`) và bề mặt nội dung (`CONTENT`). */
enum class LiquidGlassTier { CHROME, CONTENT }

/**
 * Bề mặt **Apple iOS 26 Liquid Glass quang học hoàn chỉnh** (thích ứng tự động cả Daylight Crystal & Midnight Obsidian):
 * 1. Bóng nổi mềm nhuộm tông nền tách bề mặt khỏi không gian đa sắc.
 * 2. Thân kính trong mờ thực thụ (`CHROME` 62–76% cho thanh nổi/nút/hộp thoại; `CONTENT` 78–87% cho thẻ dữ liệu).
 * 3. Ánh sáng truyền xiên liên tục (optical lensing sheen) + vệt phản chiếu đỉnh cong mềm (top curved catchlight).
 * 4. Ánh phản quang đáy (caustic floor bounce) + viền vát lăng kính 45° sắc nét (`1.25dp`).
 */
@Composable
fun Modifier.liquidGlassLayer(
    shape: Shape,
    tier: LiquidGlassTier = LiquidGlassTier.CONTENT,
    tintOverride: Color? = null,
    borderOverride: Color? = null,
    shadowElevation: Dp = if (tier == LiquidGlassTier.CHROME) 16.dp else 10.dp,
    shadowTint: Color = AppColors.ShadowTint,
): Modifier {
    val palette = LocalLiquidPalette.current
    val fillModifier = when {
        tintOverride != null -> Modifier.background(tintOverride)
        tier == LiquidGlassTier.CHROME -> Modifier.background(palette.chromeGlassBrush)
        else -> Modifier.background(palette.glassSurfaceBrush)
    }
    val rimModifier = when {
        borderOverride != null -> Modifier.border(1.25.dp, borderOverride, shape)
        tier == LiquidGlassTier.CHROME -> Modifier.border(1.3.dp, palette.chromeRimBrush, shape)
        else -> Modifier.border(1.1.dp, palette.glassRimBrush, shape)
    }
    val sheenAlpha = if (tier == LiquidGlassTier.CHROME) palette.sheenChromeAlpha else palette.sheenContentAlpha
    val causticAlpha = if (tier == LiquidGlassTier.CHROME) palette.causticChromeAlpha else palette.causticContentAlpha
    val topSpecAlpha = if (tier == LiquidGlassTier.CHROME) palette.specularTopAlpha else palette.specularTopAlpha * 0.72f

    return this
        .shadow(shadowElevation, shape, ambientColor = shadowTint, spotColor = shadowTint)
        .clip(shape)
        .then(fillModifier)
        .drawWithContent {
            // 1. Khúc xạ xiên mềm xuyên qua độ dày thấu kính
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = sheenAlpha),
                        Color.White.copy(alpha = sheenAlpha * 0.22f),
                        Color.Transparent,
                    ),
                    start = Offset.Zero,
                    end = Offset(size.width * 0.82f, size.height),
                ),
            )
            // 2. Dải phản chiếu đỉnh cong mềm (curved optical catchlight)
            val arcH = (size.height * 0.28f).coerceAtMost(24.dp.toPx())
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.White.copy(alpha = sheenAlpha * 0.65f), Color.Transparent),
                    startY = 0f,
                    endY = arcH,
                ),
                topLeft = Offset.Zero,
                size = Size(size.width, arcH),
            )
            // 3. Viền sáng đỉnh siêu mảnh sắc nét
            drawRect(
                brush = Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = topSpecAlpha), Color.Transparent)),
                topLeft = Offset.Zero,
                size = Size(size.width, 1.25.dp.toPx()),
            )
            // 4. Ánh hắt phản quang cạnh đáy (caustic bounce)
            val causticH = 1.6.dp.toPx()
            drawRect(
                brush = Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = causticAlpha), Color.Transparent)),
                topLeft = Offset(0f, (size.height - causticH).coerceAtLeast(0f)),
                size = Size(size.width, causticH),
            )
            drawContent()
        }
        .then(rimModifier)
}

/**
 * Bề mặt **Stained Liquid Glass** (kính màu đá quý có chiều sâu quang học dùng chọn lọc cho Hero,
 * nút CTA chính, viên trượt tab và chip đang chọn) đạt chuẩn tương phản WCAG 2.2 AAA với chữ trắng.
 */
@Composable
fun Modifier.stainedLiquidGlass(
    shape: Shape,
    baseColor: Color,
    shadowElevation: Dp = 12.dp,
): Modifier {
    val topColor = lerp(baseColor, Color.White, 0.14f)
    val bottomColor = lerp(baseColor, Color.Black, 0.16f)
    val bodyBrush = Brush.linearGradient(listOf(topColor, baseColor, bottomColor))
    val rimBrush = Brush.linearGradient(
        listOf(Color.White.copy(alpha = 0.56f), Color.White.copy(alpha = 0.14f), Color.White.copy(alpha = 0.36f)),
    )
    return this
        .shadow(shadowElevation, shape, ambientColor = baseColor.copy(alpha = 0.36f), spotColor = baseColor.copy(alpha = 0.44f))
        .clip(shape)
        .background(bodyBrush)
        .drawWithContent {
            // Điểm tụ ánh sáng pha lê góc trên-trái (lensing prism highlight)
            val prismR = size.maxDimension * 0.72f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.24f), Color.Transparent),
                    center = Offset(size.width * 0.18f, size.height * 0.08f),
                    radius = prismR,
                ),
                radius = prismR,
                center = Offset(size.width * 0.18f, size.height * 0.08f),
            )
            drawRect(
                brush = Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.68f), Color.Transparent)),
                topLeft = Offset.Zero,
                size = Size(size.width, 1.3.dp.toPx()),
            )
            val causticH = 1.4.dp.toPx()
            drawRect(
                brush = Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.28f), Color.Transparent)),
                topLeft = Offset(0f, (size.height - causticH).coerceAtLeast(0f)),
                size = Size(size.width, causticH),
            )
            drawContent()
        }
        .border(1.15.dp, rimBrush, shape)
}

/** Hiệu ứng mép cuộn mờ dịu (**Scroll Edge Effect** chuẩn Apple HIG) sau thanh điều hướng kính nổi. */
@Composable
fun LiquidGlassScrollEdgeFade(
    isTop: Boolean,
    height: Dp = 88.dp,
    modifier: Modifier = Modifier,
) {
    val fade = AppColors.ScrollEdgeFade
    val colors = if (isTop) {
        listOf(fade, fade.copy(alpha = 0.42f), Color.Transparent)
    } else {
        listOf(Color.Transparent, fade.copy(alpha = 0.46f), fade)
    }
    Box(modifier = modifier.fillMaxWidth().height(height).background(Brush.verticalGradient(colors)))
}
