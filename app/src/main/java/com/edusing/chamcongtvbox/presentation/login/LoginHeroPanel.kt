package com.edusing.chamcongtvbox.presentation.login

import androidx.compose.foundation.background
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edusing.chamcongtvbox.domain.model.AppMode
import com.edusing.chamcongtvbox.presentation.components.TammiGlassPanel
import com.edusing.chamcongtvbox.presentation.theme.AppColors
import com.edusing.chamcongtvbox.presentation.theme.LiquidGlassTier
import com.edusing.chamcongtvbox.presentation.theme.TammiSpacing

/** Khung giới thiệu **Clear Liquid Glass Sáng** tối giản cho màn hình rộng TV Box / Tablet. */
@Composable
internal fun LoginHeroPanel(
    mode: AppMode,
    title: String,
    orgLabel: String,
    modifier: Modifier = Modifier,
) {
    TammiGlassPanel(modifier = modifier.fillMaxWidth(), padding = PaddingValues(TammiSpacing.lg), tier = LiquidGlassTier.CHROME) {
        Column(verticalArrangement = Arrangement.spacedBy(TammiSpacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TammiSpacing.sm)) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(AppColors.GreenBright))
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.Black, color = AppColors.Text)
            }
            Text("Trạm cầu nối 3-trong-1 • Chạy nền 24/7", fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = AppColors.TextSecondary)
        }
        Spacer(Modifier.height(TammiSpacing.md))
        HorizontalDivider(color = AppColors.Border.copy(alpha = 0.55f))
        Spacer(Modifier.height(TammiSpacing.md))
        HeroFeatureRow("Đẩy chấm công tức thì về web $orgLabel")
        Spacer(Modifier.height(TammiSpacing.sm))
        HeroFeatureRow("Tự dò LAN & tự phục hồi kết nối 24/7")
        Spacer(Modifier.height(TammiSpacing.sm))
        HeroFeatureRow("Đẩy danh sách thẻ & xuất Word 1 chạm")
    }
}

@Composable
private fun HeroFeatureRow(title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(AppColors.Primary))
        Spacer(Modifier.width(TammiSpacing.md))
        Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Text)
    }
}
