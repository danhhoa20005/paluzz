package com.edusing.chamcongtvbox.presentation.login

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edusing.chamcongtvbox.domain.model.AppMode
import com.edusing.chamcongtvbox.presentation.components.TammiGlassPanel
import com.edusing.chamcongtvbox.presentation.components.orbColor
import com.edusing.chamcongtvbox.presentation.theme.AppColors
import com.edusing.chamcongtvbox.presentation.theme.TammiRadii
import com.edusing.chamcongtvbox.presentation.theme.TammiSpacing
import com.edusing.chamcongtvbox.presentation.theme.rememberReducedMotion
import com.edusing.chamcongtvbox.presentation.theme.stainedLiquidGlass

/** Khung giới thiệu **Stained Liquid Glass 3D** thuần màu sắc & chữ dành cho màn hình rộng TV Box / Tablet. */
@Composable
internal fun LoginHeroPanel(
    mode: AppMode,
    title: String,
    orgLabel: String,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = rememberReducedMotion()
    val accent by animateColorAsState(
        targetValue = mode.orbColor(),
        animationSpec = if (reducedMotion) snap() else spring(stiffness = 320f),
        label = "loginHeroTint",
    )

    TammiGlassPanel(modifier = modifier.fillMaxWidth(), padding = PaddingValues(TammiSpacing.lg)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .stainedLiquidGlass(shape = TammiRadii.inputShape, baseColor = accent, shadowElevation = 10.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(AppColors.GreenBright).border(1.5.dp, Color.White, CircleShape))
                    Text(title, fontSize = 18.sp, fontWeight = FontWeight.Black, color = Color.White)
                }
                Text("Trạm cầu nối 3-trong-1 • Chạy nền 24/7", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = AppColors.HeroSubtitle)
            }
        }
        Spacer(Modifier.height(TammiSpacing.md))
        HeroFeatureRow(AppColors.OrbTeal, "Đẩy chấm công tức thì về web $orgLabel")
        Spacer(Modifier.height(10.dp))
        HeroFeatureRow(AppColors.OrbCobalt, "Tự dò LAN & tự phục hồi kết nối 24/7")
        Spacer(Modifier.height(10.dp))
        HeroFeatureRow(AppColors.OrbAmber, "Đẩy danh sách thẻ & xuất Word 1 chạm")
    }
}

@Composable
private fun HeroFeatureRow(accentColor: Color, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(6.dp)
                .height(22.dp)
                .shadow(4.dp, CircleShape, ambientColor = accentColor.copy(alpha = 0.40f), spotColor = accentColor.copy(alpha = 0.40f))
                .clip(CircleShape)
                .background(Brush.verticalGradient(listOf(accentColor, accentColor.copy(alpha = 0.65f)))),
        )
        Spacer(Modifier.width(12.dp))
        Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = AppColors.Text)
    }
}
