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

/** Hai cấp vật liệu chuẩn Apple HIG Liquid Glass: điều hướng/nút nổi (`CHROME`) và bề mặt nội dung (`CONTENT`). */
enum class LiquidGlassTier { CHROME, CONTENT }

/**
 * Bề mặt **Liquid Glass quang học hiện đại** (chuẩn Apple HIG / iOS 26 — KHÔNG dùng vệt bóng cứng kiểu cũ):
 * 1. Bóng nổi không khí mềm tách bề mặt khỏi nền đa sắc.
 * 2. Thân kính trong mờ tự nhiên (`CHROME` 65–80% cho thanh nổi; `CONTENT` 84–92% cho thẻ dữ liệu).
 * 3. Ánh sáng truyền xiên mịn từ góc trên-trái (continuous diagonal optical sheen, không đường cắt ngang).
 * 4. Viền vát khúc xạ 45° sắc nét (`1.2dp`) + ánh phản quang mảnh cạnh đáy (`caustic bounce`).
 */
fun Modifier.liquidGlassLayer(
    shape: Shape,
    tier: LiquidGlassTier = LiquidGlassTier.CONTENT,
    tintOverride: Color? = null,
    borderOverride: Color? = null,
    shadowElevation: Dp = if (tier == LiquidGlassTier.CHROME) 14.dp else 9.dp,
    shadowTint: Color = AppColors.ShadowTint,
): Modifier {
    val fillModifier = when {
        tintOverride != null -> Modifier.background(tintOverride)
        tier == LiquidGlassTier.CHROME -> Modifier.background(AppColors.ChromeGlassBrush)
        else -> Modifier.background(AppColors.GlassSurfaceBrush)
    }
    val rimModifier = when {
        borderOverride != null -> Modifier.border(1.2.dp, borderOverride, shape)
        tier == LiquidGlassTier.CHROME -> Modifier.border(1.3.dp, AppColors.ChromeRimBrush, shape)
        else -> Modifier.border(1.1.dp, AppColors.GlassRimBrush, shape)
    }
    val sheenAlpha = if (tier == LiquidGlassTier.CHROME) 0.28f else 0.16f
    val causticAlpha = if (tier == LiquidGlassTier.CHROME) 0.48f else 0.28f

    return this
        .shadow(shadowElevation, shape, ambientColor = shadowTint, spotColor = shadowTint)
        .clip(shape)
        .then(fillModifier)
        .drawWithContent {
            // Ánh sáng truyền xiên liên tục mượt mà (không tạo vạch cắt ngang thô)
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = sheenAlpha),
                        Color.White.copy(alpha = sheenAlpha * 0.25f),
                        Color.Transparent,
                    ),
                    start = Offset.Zero,
                    end = Offset(size.width * 0.85f, size.height),
                ),
            )
            // Vệt sáng cạnh trên cực mảnh
            drawRect(
                brush = Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.86f), Color.Transparent)),
                topLeft = Offset.Zero,
                size = Size(size.width, 1.2.dp.toPx()),
            )
            // Ánh hắt phản quang cạnh dưới
            val causticH = 1.4.dp.toPx()
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
 * Bề mặt **Stained Liquid Glass** tinh tế (kính màu có chiều sâu dùng chọn lọc cho CTA chính,
 * thẻ Hero và viên trượt tab đang chọn) đạt chuẩn tương phản WCAG 2.2 AAA với chữ trắng.
 */
fun Modifier.stainedLiquidGlass(
    shape: Shape,
    baseColor: Color,
    shadowElevation: Dp = 10.dp,
): Modifier {
    val topColor = lerp(baseColor, Color.White, 0.10f)
    val bottomColor = lerp(baseColor, Color.Black, 0.14f)
    val bodyBrush = Brush.linearGradient(listOf(topColor, baseColor, bottomColor))
    val rimBrush = Brush.linearGradient(
        listOf(Color.White.copy(alpha = 0.48f), Color.White.copy(alpha = 0.12f), Color.White.copy(alpha = 0.32f)),
    )
    return this
        .shadow(shadowElevation, shape, ambientColor = baseColor.copy(alpha = 0.30f), spotColor = baseColor.copy(alpha = 0.38f))
        .clip(shape)
        .background(bodyBrush)
        .drawWithContent {
            // Độ sáng quang học liên tục xiên góc (mịn tự nhiên, không vạch chia đôi)
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(Color.White.copy(alpha = 0.16f), Color.Transparent),
                    start = Offset.Zero,
                    end = Offset(size.width * 0.7f, size.height * 0.9f),
                ),
            )
            drawRect(
                brush = Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.56f), Color.Transparent)),
                topLeft = Offset.Zero,
                size = Size(size.width, 1.2.dp.toPx()),
            )
            drawContent()
        }
        .border(1.1.dp, rimBrush, shape)
}

/** Hiệu ứng mép cuộn mờ dịu (**Scroll Edge Effect** chuẩn Apple HIG) sau thanh điều hướng kính nổi. */
@Composable
fun LiquidGlassScrollEdgeFade(
    isTop: Boolean,
    height: Dp = 88.dp,
    modifier: Modifier = Modifier,
) {
    val colors = if (isTop) {
        listOf(AppColors.ScrollEdgeFade, AppColors.ScrollEdgeFade.copy(alpha = 0.38f), Color.Transparent)
    } else {
        listOf(Color.Transparent, AppColors.ScrollEdgeFade.copy(alpha = 0.45f), AppColors.ScrollEdgeFade)
    }
    Box(modifier = modifier.fillMaxWidth().height(height).background(Brush.verticalGradient(colors)))
}
