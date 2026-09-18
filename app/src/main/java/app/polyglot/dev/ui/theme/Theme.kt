package app.polyglot.dev.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 统一的暖白、墨灰与靛蓝视觉主题
val Ink = Color(0xFF252B3B)        // 主按钮/深色
val InkText = Color(0xFF252B3B)
val Ink2 = Color(0xFF343D50)
val Gray = Color(0xFF707888)
val GrayLight = Color(0xFF8991A0)
val Line = Color(0xFFE4E7ED)
val SoftBg = Color(0xFFF1F3F7)
val BrandBlue = Color(0xFF5265C9)
val BlueDeep = Color(0xFF4254B5)
val BlueSoft = Color(0xFFEEF0FC)
val BlueSel = Color(0xFFF3F4FF)
val SuccessGreen = Color(0xFF18A860)
val GreenSoft = Color(0xFFE9F8F0)
val ErrorRed = Color(0xFFDA2C1D)
val ErrorText = Color(0xFFB3261E)
val ErrorSoft = Color(0xFFFDF1F2)
val ErrorLine = Color(0xFFF6C9C4)
val UserBubble = Color(0xFF5265C9)
val CodeBg = Color(0xFF343D50)
val ToastDark = Color(0xFF121927)

val Canvas = Color(0xFFFAFAF8)

private val PolyglotColors = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    secondary = BrandBlue,
    onSecondary = Color.White,
    tertiary = BlueDeep,
    background = Canvas,
    onBackground = InkText,
    surface = Canvas,
    onSurface = InkText,
    surfaceVariant = SoftBg,
    onSurfaceVariant = Gray,
    outline = Line,
    outlineVariant = Line,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    error = ErrorRed,
    onError = Color.White,
    errorContainer = ErrorSoft,
    onErrorContainer = ErrorText
)

@Composable
fun PolyglotTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PolyglotColors,
        typography = Typography(
            titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
            bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
            labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
        ),
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(20.dp)
        ),
        content = content
    )
}
