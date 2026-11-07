package app.polyglot.dev.preview

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.polyglot.dev.data.local.MessageEntity
import app.polyglot.dev.data.local.MessageStatus
import app.polyglot.dev.data.local.ProviderEntity
import app.polyglot.dev.data.local.Role
import app.polyglot.dev.data.local.SessionEntity
import app.polyglot.dev.data.repo.ModelOrigin
import app.polyglot.dev.data.repo.ProviderPresets
import app.polyglot.dev.feature.chat.ChatConversationContent
import app.polyglot.dev.feature.chat.ChatDrawerContent
import app.polyglot.dev.feature.chat.ModelTarget
import app.polyglot.dev.feature.chat.SwitchModelPage
import app.polyglot.dev.feature.config.CatalogUiState
import app.polyglot.dev.feature.config.ConfigEditContent
import app.polyglot.dev.feature.config.ConfigForm
import app.polyglot.dev.feature.config.ConfigListContent
import app.polyglot.dev.feature.config.FieldErrors
import app.polyglot.dev.feature.config.ModelChoice
import app.polyglot.dev.feature.config.TestUiState
import app.polyglot.dev.feature.onboarding.OnboardingScreen
import app.polyglot.dev.ui.theme.PolyglotTheme

@Preview(name = "首次使用", widthDp = 393, heightDp = 851, locale = "zh")
@Composable
fun OnboardingPreview() = PreviewScreen {
    OnboardingScreen(onConfigure = {})
}

@Preview(name = "空白聊天", widthDp = 393, heightDp = 851, locale = "zh")
@Composable
fun EmptyChatPreview() = PreviewScreen {
    PreviewConversation(session = null, messages = emptyList())
}

@Preview(name = "已有对话", widthDp = 393, heightDp = 851, locale = "zh")
@Composable
fun ConversationPreview() = PreviewScreen {
    PreviewConversation(session = sampleSession, messages = sampleMessages)
}

@Preview(name = "会话抽屉", widthDp = 393, heightDp = 851, locale = "zh")
@Composable
fun SessionsPreview() = PreviewScreen {
    ModalNavigationDrawer(
        drawerState = rememberDrawerState(DrawerValue.Open),
        gesturesEnabled = false,
        drawerContent = {
            ChatDrawerContent(
                sessions = sampleSessions,
                currentSessionId = sampleSession.id,
                searchQuery = "",
                onSearch = {},
                onNewChat = {},
                onOpenSession = {},
                onRename = { _, _ -> },
                onDelete = {},
                onOpenConfig = {}
            )
        }
    ) {
        PreviewConversation(session = sampleSession, messages = sampleMessages)
    }
}

@Preview(name = "换模型续聊", widthDp = 393, heightDp = 851, locale = "zh")
@Composable
fun SwitchModelPreview() = PreviewScreen {
    SwitchModelPage(
        targets = sampleTargets,
        current = sampleSession,
        requiresTitle = true,
        generatingOnCurrent = false,
        switching = false,
        onBack = {},
        onStopAndContinue = {},
        onConfirm = { _, _, _ -> }
    )
}

@Preview(name = "模型服务列表", widthDp = 393, heightDp = 851, locale = "zh")
@Composable
fun ModelServicesPreview() = PreviewScreen {
    ConfigListContent(
        providers = sampleProviders,
        snackbar = remember { SnackbarHostState() },
        modelCount = { provider -> sampleTargets.count { it.configId == provider.id } },
        onBack = {},
        onAdd = {},
        onEdit = {},
        onDelete = {}
    )
}

@Preview(name = "添加模型服务", widthDp = 393, heightDp = 1020, locale = "zh")
@Composable
fun AddServicePreview() = PreviewScreen {
    val preset = ProviderPresets.selectable.first()
    PreviewServiceEditor(
        form = ConfigForm(providerType = preset.type, baseUrl = preset.defaultBaseUrl),
        testState = TestUiState.Idle
    )
}

@Preview(name = "编辑模型服务", widthDp = 393, heightDp = 1120, locale = "zh")
@Composable
fun EditServicePreview() = PreviewScreen {
    val service = sampleProviders.first()
    PreviewServiceEditor(
        form = ConfigForm(
            id = service.id,
            providerType = service.providerType,
            baseUrl = service.baseUrl,
            apiKey = "preview-only",
            selectedModelId = service.selectedModelId,
            modelName = service.name,
            nameEdited = true,
            advancedOpen = true,
            savedTestState = service.testState
        ),
        testState = TestUiState.Success
    )
}

@Composable
private fun PreviewScreen(content: @Composable () -> Unit) {
    PolyglotTheme {
        Surface(modifier = Modifier.fillMaxSize(), content = content)
    }
}

@Composable
private fun PreviewConversation(session: SessionEntity?, messages: List<MessageEntity>) {
    ChatConversationContent(
        session = session,
        modelLabel = session?.modelNameSnapshot ?: sampleSession.modelNameSnapshot,
        messages = messages,
        draft = "",
        generating = false,
        ready = true,
        hasProvider = true,
        showDeleteUndo = false,
        snackbar = remember { SnackbarHostState() },
        onOpenDrawer = {},
        onOpenSwitch = {},
        onOpenConfigList = {},
        onFirstConfig = {},
        onDraftChange = {},
        onSend = {},
        onStop = {},
        onUndoDelete = {},
        errorText = { "回复失败，请重试" to null },
        truncationText = { "回复可能不完整" to null },
        onRetry = {}
    )
}

@Composable
private fun PreviewServiceEditor(form: ConfigForm, testState: TestUiState) {
    ConfigEditContent(
        form = form,
        catalogState = CatalogUiState.IDLE,
        primaryChoices = sampleTargets
            .filter { it.configId == form.id }
            .map { ModelChoice(it.modelId, "", ModelOrigin.PUBLIC_REFERENCE) },
        testState = testState,
        errors = FieldErrors(),
        focusRequest = null,
        listUpdatedAt = null,
        snackbar = remember { SnackbarHostState() },
        onBack = {},
        onProviderChange = {},
        onApiKeyChange = {},
        onModelChange = {},
        onManualModelEntryChange = {},
        onTestConnection = {},
        onModelNameChange = {},
        onSave = {},
        onToggleAdvanced = {},
        onBaseUrlChange = {}
    )
}

// 这些数据只用于 debug 截图，预览直接调用界面函数。
private val sampleTime = System.currentTimeMillis()
private const val DAY_MILLIS = 24L * 60 * 60 * 1000

private val sampleTargets = listOf(
    ModelTarget("deepseek", "DeepSeek 日常助手", "deepseek-chat"),
    ModelTarget("deepseek", "DeepSeek 日常助手", "deepseek-reasoner"),
    ModelTarget("kimi", "Kimi 长文阅读", "moonshot-v1-8k")
)

private val sampleProviders = sampleTargets.groupBy { it.configId }.map { (id, targets) ->
    val first = targets.first()
    val tested = id == "deepseek"
    ProviderEntity(
        id = id,
        name = first.configName,
        providerType = id,
        apiType = "openai_compatible",
        baseUrl = ProviderPresets.byType(id).defaultBaseUrl,
        modelIdsJson = targets.joinToString(prefix = "[", postfix = "]") { "\"${it.modelId}\"" },
        selectedModelId = first.modelId,
        testState = if (tested) "success" else "none",
        testedAt = if (tested) sampleTime else null,
        createdAt = sampleTime,
        updatedAt = sampleTime
    )
}

private val sampleSession = SessionEntity(
    id = "preview-session",
    title = "周末读书计划",
    configId = sampleTargets.first().configId,
    modelId = sampleTargets.first().modelId,
    modelNameSnapshot = "${sampleTargets.first().configName} · ${sampleTargets.first().modelId}",
    sourceSessionId = null,
    sourceTitle = null,
    createdAt = sampleTime,
    updatedAt = sampleTime,
    deleteUntil = null
)

private val sampleSessions = listOf(
    sampleSession,
    sampleSession.copy(
        id = "preview-yesterday",
        title = "把会议记录整理成待办",
        createdAt = sampleTime - DAY_MILLIS,
        updatedAt = sampleTime - DAY_MILLIS
    ),
    sampleSession.copy(
        id = "preview-earlier",
        title = "旅行行李清单",
        createdAt = sampleTime - 3 * DAY_MILLIS,
        updatedAt = sampleTime - 3 * DAY_MILLIS
    )
)

private val sampleMessages = listOf(
    sampleMessage(1, Role.USER, "帮我安排一个轻松的周末读书计划。"),
    sampleMessage(
        2,
        Role.ASSISTANT,
        """
        ## 周末读书计划

        **周六：从一本想读的书开始**
        - 上午读 25 分钟，标记一段喜欢的内容。
        - 散步时回想书里的一个想法。

        **周日：留下一点读书笔记**
        - 继续读 25 分钟，用三句话记下收获。
        """.trimIndent()
    )
)

private fun sampleMessage(index: Int, role: String, content: String) = MessageEntity(
    id = "preview-message-$index",
    sessionId = sampleSession.id,
    role = role,
    seq = index,
    content = content,
    status = MessageStatus.DONE,
    requestId = null,
    errorKind = null,
    createdAt = sampleTime + index
)
