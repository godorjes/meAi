package app.polyglot.dev.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.polyglot.dev.R
import app.polyglot.dev.ui.theme.BrandMark
import app.polyglot.dev.ui.theme.BrandBlue
import app.polyglot.dev.ui.theme.Gray
import app.polyglot.dev.ui.theme.GrayLight
import app.polyglot.dev.ui.theme.Ink

@Composable
fun OnboardingScreen(onConfigure: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 34.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            BrandMark()
            Spacer(Modifier.height(16.dp))
            Text("Polyglot", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Gray)
            Spacer(Modifier.height(40.dp))
            Text("好想法，从对话开始。", fontSize = 28.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text(
                "连接喜欢的 AI 模型。\n提问、创作，让想法更进一步。",
                fontSize = 15.sp, lineHeight = 25.sp, color = Gray, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(30.dp))
            Button(
                onClick = onConfigure,
                modifier = Modifier.widthIn(min = 240.dp).height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BrandBlue)
            ) {
                Text("连接第一个模型", fontWeight = FontWeight.Bold)
            }
        }
        Text(
            "你的模型 · 你的灵感空间",
            fontSize = 11.sp,
            color = GrayLight,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 34.dp)
        )
    }
}
