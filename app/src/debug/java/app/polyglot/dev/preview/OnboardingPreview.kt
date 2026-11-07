package app.polyglot.dev.preview

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.polyglot.dev.feature.onboarding.OnboardingScreen
import app.polyglot.dev.ui.theme.PolyglotTheme

@Preview(
    name = "首次使用",
    widthDp = 393,
    heightDp = 851,
    showBackground = true,
    locale = "zh"
)
@Composable
fun OnboardingPreview() {
    PolyglotTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            OnboardingScreen(onConfigure = {})
        }
    }
}
