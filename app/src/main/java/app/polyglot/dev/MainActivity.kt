package app.polyglot.dev

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.polyglot.dev.feature.chat.ChatScreen
import app.polyglot.dev.feature.config.ConfigEditScreen
import app.polyglot.dev.feature.config.ConfigListScreen
import app.polyglot.dev.feature.onboarding.OnboardingScreen
import app.polyglot.dev.ui.theme.PolyglotTheme
import kotlinx.coroutines.flow.map

object Routes {
    const val ONBOARDING = "onboarding"
    const val CHAT = "chat"
    const val CONFIG_LIST = "configList"
    const val CONFIG_EDIT = "configEdit?configId={configId}"
    const val CONFIG_EDIT_NEW = "configEdit"
    fun configEdit(id: String) = "configEdit?configId=$id"
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as PolyglotApp).container
        setContent {
            PolyglotTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PolyglotRoot(container)
                }
            }
        }
    }
}

/**
 * 退出前台（切到其他 App、锁屏）时主动结束进行中的客户端生成，
 * 保留已落盘内容并标记“已中断”。首版不配置后台持续生成服务。
 *
 * 这里用应用级生命周期而不是 Activity 的 onStop：
 * 旋转屏幕同样会走到 Activity.onStop，用它会把正常旋转误判成退到后台。
 */
class AppLifecycleObserver(
    private val onLeftForeground: () -> Unit
) : DefaultLifecycleObserver {
    override fun onStop(owner: LifecycleOwner) {
        onLeftForeground()
    }
}

@Composable
fun PolyglotRoot(container: AppContainer) {
    // 首帧先读“是否已有配置”，避免先闪聊天页再跳引导页
    val hasConfig by container.configRepository.providers
        .map { it.isNotEmpty() }
        .collectAsStateWithLifecycle(initialValue = null)

    val ready = hasConfig
    if (ready == null) {
        Box(modifier = Modifier.fillMaxSize())
        return
    }
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = if (ready) Routes.CHAT else Routes.ONBOARDING
    ) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onConfigure = { navController.navigate(Routes.CONFIG_EDIT_NEW) }
            )
        }
        composable(Routes.CHAT) {
            ChatScreen(
                container = container,
                onOpenConfigList = { navController.navigate(Routes.CONFIG_LIST) },
                onFirstConfig = { navController.navigate(Routes.CONFIG_EDIT_NEW) }
            )
        }
        composable(Routes.CONFIG_LIST) {
            ConfigListScreen(
                container = container,
                onBack = { navController.popBackStack() },
                onAdd = { navController.navigate(Routes.CONFIG_EDIT_NEW) },
                onEdit = { id -> navController.navigate(Routes.configEdit(id)) }
            )
        }
        composable(
            route = Routes.CONFIG_EDIT,
            arguments = listOf(navArgument("configId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            })
        ) { entry ->
            ConfigEditScreen(
                container = container,
                configId = entry.arguments?.getString("configId"),
                onBack = { navController.popBackStack() },
                onSavedStartChat = {
                    navController.navigate(Routes.CHAT) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
    }
}
