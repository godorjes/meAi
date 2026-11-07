package app.polyglot.dev.feature.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.polyglot.dev.data.local.MessageEntity
import app.polyglot.dev.data.local.MessageStatus
import app.polyglot.dev.data.local.Role
import app.polyglot.dev.data.local.SessionEntity
import app.polyglot.dev.ui.theme.BrandMark
import app.polyglot.dev.ui.theme.BlueSoft
import app.polyglot.dev.ui.theme.Canvas
import app.polyglot.dev.ui.theme.BrandBlue
import app.polyglot.dev.ui.theme.ErrorLine
import app.polyglot.dev.ui.theme.ErrorRed
import app.polyglot.dev.ui.theme.ErrorSoft
import app.polyglot.dev.ui.theme.ErrorText
import app.polyglot.dev.ui.theme.Gray
import app.polyglot.dev.ui.theme.GrayLight
import app.polyglot.dev.ui.theme.Ink
import app.polyglot.dev.ui.theme.Ink2
import app.polyglot.dev.ui.theme.Line
import app.polyglot.dev.ui.theme.SoftBg
import app.polyglot.dev.ui.theme.ToastDark
import app.polyglot.dev.ui.theme.UserBubble
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    container: app.polyglot.dev.AppContainer,
    onOpenConfigList: () -> Unit,
    onFirstConfig: () -> Unit
) {
    val context = LocalContext.current
    val vm: ChatViewModel = viewModel(factory = viewModelFactory {
        initializer { ChatViewModel(context.applicationContext as android.app.Application) }
    })

    val session by vm.session.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()
    val drawerSessions by vm.drawerSessions.collectAsStateWithLifecycle()
    val searchQuery by vm.searchQuery.collectAsStateWithLifecycle()
    val currentSessionId by vm.currentSessionId.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val generating by vm.generating.collectAsStateWithLifecycle()
    val ready by vm.ready.collectAsStateWithLifecycle()
    val switchPageVisible by vm.switchPageVisible.collectAsStateWithLifecycle()
    val switching by vm.switching.collectAsStateWithLifecycle()
    val generatingSessionId by vm.generatingSessionId.collectAsStateWithLifecycle()
    val pendingDelete by vm.pendingDelete.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val modelLabel by vm.displayedModelLabel.collectAsStateWithLifecycle()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(toast) { toast?.let { snackbar.showSnackbar(it); vm.clearToast() } }

    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    // 抽屉收起时同时收起键盘并清掉焦点：
    // 在抽屉里点过搜索框后直接点空白区关抽屉，键盘不会自己退下去
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(drawerState.isOpen) {
        if (!drawerState.isOpen) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }

    LaunchedEffect(switchPageVisible) {
        if (switchPageVisible) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = !switchPageVisible,
            drawerContent = {
                ChatDrawerContent(
                    sessions = drawerSessions,
                    currentSessionId = currentSessionId,
                    searchQuery = searchQuery,
                    onSearch = vm::updateSearch,
                    onNewChat = { vm.newChat(); scope.launch { drawerState.close() } },
                    onOpenSession = { id -> vm.openSession(id); scope.launch { drawerState.close() } },
                    onRename = vm::renameSession,
                    onDelete = vm::deleteSession,
                    onOpenConfig = {
                        scope.launch { drawerState.close() }
                        onOpenConfigList()
                    }
                )
            }
        ) {
            ChatConversationContent(
                session = session,
                modelLabel = modelLabel,
                messages = messages,
                draft = draft,
                generating = generating,
                ready = ready,
                hasProvider = providers.isNotEmpty(),
                showDeleteUndo = pendingDelete != null,
                snackbar = snackbar,
                onOpenDrawer = { scope.launch { drawerState.open() } },
                onOpenSwitch = vm::openSwitchModel,
                onOpenConfigList = onOpenConfigList,
                onFirstConfig = onFirstConfig,
                onDraftChange = vm::updateDraft,
                onSend = vm::send,
                onStop = { vm.stop() },
                onUndoDelete = vm::undoDelete,
                errorText = vm::errorText,
                truncationText = vm::truncationText,
                onRetry = vm::retry
            )
        }

        AnimatedVisibility(
            visible = switchPageVisible,
            modifier = Modifier.fillMaxSize(),
            enter = slideInHorizontally(animationSpec = tween(280), initialOffsetX = { it }),
            exit = slideOutHorizontally(animationSpec = tween(240), targetOffsetX = { it })
        ) {
            BackHandler { vm.closeSwitchModel() }
            SwitchModelPage(
                targets = vm.modelTargets(),
                current = session,
                // 有内容才需要复制会话，才需要新标题
                requiresTitle = messages.isNotEmpty(),
                generatingOnCurrent = generatingSessionId != null && generatingSessionId == currentSessionId,
                switching = switching,
                onBack = { vm.closeSwitchModel() },
                onStopAndContinue = { vm.stop() },
                onConfirm = { target, title, cb -> vm.switchModel(target, title, cb) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatDrawerContent(
    sessions: List<SessionEntity>,
    currentSessionId: String?,
    searchQuery: String,
    onSearch: (String) -> Unit,
    onNewChat: () -> Unit,
    onOpenSession: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onOpenConfig: () -> Unit
) {
    // 侧边栏宽度：300dp，并保证窄屏上至少留出 72dp 让聊天页的遮罩可见。
    // 手机上一个会话抽屉读起来约在屏宽的 70%—80%（Material 的模态抽屉上限是 360dp），
    // 300dp 既能放下搜索框、够长的会话标题，也不会因为内容少而显得空旷
    val drawerWidth = (LocalConfiguration.current.screenWidthDp.dp - 72.dp)
        .coerceAtMost(300.dp)
        .coerceAtLeast(260.dp)

    ModalDrawerSheet(modifier = Modifier.width(drawerWidth)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                SessionDrawer(
                    sessions = sessions,
                    currentSessionId = currentSessionId,
                    searchQuery = searchQuery,
                    onSearch = onSearch,
                    onNewChat = onNewChat,
                    onOpenSession = onOpenSession,
                    onRename = onRename,
                    onDelete = onDelete,
                    onOpenConfig = {}
                )
            }
            HorizontalDivider(color = Line)
            DrawerFooter(onOpenConfig = onOpenConfig)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatConversationContent(
    session: SessionEntity?,
    modelLabel: String?,
    messages: List<MessageEntity>,
    draft: String,
    generating: Boolean,
    ready: Boolean,
    hasProvider: Boolean,
    showDeleteUndo: Boolean,
    snackbar: SnackbarHostState,
    onOpenDrawer: () -> Unit,
    onOpenSwitch: () -> Unit,
    onOpenConfigList: () -> Unit,
    onFirstConfig: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onUndoDelete: () -> Unit,
    errorText: (String?) -> Pair<String, String?>,
    truncationText: () -> Pair<String, String?>,
    onRetry: (MessageEntity) -> Unit
) {
    Scaffold(
        topBar = {
            // CenterAlignedTopAppBar 按整条 bar 居中，标题不会被左侧菜单或右侧按钮挤偏
            CenterAlignedTopAppBar(
                title = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            session?.title?.ifBlank { "新会话" } ?: "新会话",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            modelLabel ?: "未选择模型",
                            fontSize = 12.sp,
                            color = Gray,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "打开会话列表")
                    }
                },
                actions = {
                    // 换模型入口收起为右上角按钮，不再占用标题行
                    IconButton(
                        onClick = onOpenSwitch,
                        enabled = hasProvider
                    ) {
                        Icon(
                            Icons.Default.SwapHoriz,
                            contentDescription = "换模型续聊"
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ---------- 消息区 ----------
                Box(modifier = Modifier.weight(1f)) {
                    if (messages.isEmpty()) {
                        EmptyChat(hasProvider = hasProvider, onFirstConfig = onFirstConfig)
                    } else {
                        MessageList(
                            messages = messages,
                            errorText = errorText,
                            truncationText = truncationText,
                            onRetry = onRetry,
                            onOpenConfigList = onOpenConfigList,
                            onOpenSwitch = onOpenSwitch
                        )
                    }
                }
                // ---------- 输入区 ----------
                Composer(
                    draft = draft,
                    onDraftChange = onDraftChange,
                    generating = generating,
                    // 启动恢复完成前不允许开始新的生成
                    enabled = ready && hasProvider && (session == null || session.configId != null),
                    needConfig = !hasProvider,
                    configDeleted = session != null && session.configId == null,
                    onSend = onSend,
                    onStop = onStop,
                    onPickModel = onOpenConfigList
                )
            }

            // ---------- 删除撤销 Toast ----------
            androidx.compose.animation.AnimatedVisibility(
                visible = showDeleteUndo,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 96.dp)
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(ToastDark)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("会话已删除", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(18.dp))
                    Text(
                        "撤销",
                        color = Color(0xFF7EB0F7),
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onUndoDelete)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyChat(hasProvider: Boolean, onFirstConfig: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        BrandMark()
        Spacer(Modifier.height(24.dp))
        Text(if (hasProvider) "今天，想聊些什么？" else "让对话从这里开始", fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Spacer(Modifier.height(10.dp))
        Text(if (hasProvider) "一个问题，一点灵感，或一个新的想法。" else "连接你喜欢的模型，开启第一段对话。", fontSize = 14.sp, lineHeight = 23.sp, color = Gray, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        if (hasProvider) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("理清思路", "探索新知", "打磨文字").forEach { label ->
                    Text(label, fontSize = 12.sp, color = Gray, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(SoftBg).padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
        } else {
            Button(onClick = onFirstConfig, shape = RoundedCornerShape(14.dp)) {
                Text("连接模型", fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(50.dp))
    }
}

@Composable
private fun MessageList(
    messages: List<MessageEntity>,
    errorText: (String?) -> Pair<String, String?>,
    truncationText: () -> Pair<String, String?>,
    onRetry: (MessageEntity) -> Unit,
    onOpenConfigList: () -> Unit,
    onOpenSwitch: () -> Unit
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val atBottom by remember {
        derivedStateOf { !listState.canScrollForward }
    }

    // 每个新问题只定位一次，让用户从本轮回复的开头阅读。
    // 回复长度、状态变化和重试都不触发滚动，手动滑动后保留阅读位置。
    val latestQuestion = messages.lastOrNull { it.role == Role.USER }
    LaunchedEffect(latestQuestion?.id) {
        if (latestQuestion != null) {
            val questionIndex = messages.indexOfFirst { it.id == latestQuestion.id }
            if (questionIndex >= 0) listState.scrollToItem(questionIndex)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            items(messages, key = { it.id }) { message ->
                if (message.role == Role.USER) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Box(
                            modifier = Modifier
                                .widthIn(max = 300.dp)
                                .clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                                .background(UserBubble)
                                .padding(horizontal = 15.dp, vertical = 11.dp)
                        ) {
                            Text(message.content, color = Color.White, fontSize = 15.sp, lineHeight = 24.sp)
                        }
                    }
                } else {
                    AssistantMessage(
                        message = message,
                        // 重试只对最后一条尚未成功完成的回复开放
                        isLast = message.id == messages.lastOrNull()?.id,
                        errorText = errorText(message.errorKind),
                        truncationText = truncationText(),
                        onRetry = { onRetry(message) },
                        onOpenSettings = onOpenConfigList,
                        onPickModel = onOpenSwitch
                    )
                }
            }
        }

        // 回到底部
        if (!atBottom) {
            IconButton(
                onClick = {
                    scope.launch {
                        val lastIndex = listState.layoutInfo.totalItemsCount - 1
                        if (lastIndex >= 0) {
                            listState.animateScrollToItem(lastIndex)
                            // 最后一条回复可能比一屏更长，要继续滚到正文末尾。
                            val lastItem = listState.layoutInfo.visibleItemsInfo
                                .firstOrNull { it.index == lastIndex }
                            if (lastItem != null) listState.animateScrollBy(lastItem.size.toFloat())
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 8.dp)
                    .clip(CircleShape)
                    .background(SoftBg)
                    .border(1.dp, Line, CircleShape)
            ) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "回到底部", tint = Ink2)
            }
        }
    }
}

@Composable
private fun AssistantMessage(
    message: MessageEntity,
    isLast: Boolean,
    errorText: Pair<String, String?>,
    truncationText: Pair<String, String?>,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onPickModel: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(BlueSoft),
            contentAlignment = Alignment.Center
        ) {
            Text("AI", color = BrandBlue, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("助手", fontSize = 11.5.sp, color = Gray, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(5.dp))

            when (message.status) {
                MessageStatus.FAILED -> {
                    if (message.content.isNotBlank()) {
                        MarkdownText(message.content)
                        Spacer(Modifier.height(8.dp))
                    }
                    ErrorCard(errorText = errorText, onRetry = onRetry, onOpenSettings = onOpenSettings, onPickModel = onPickModel)
                }
                MessageStatus.GENERATING -> {
                    if (message.content.isNotBlank()) MarkdownText(message.content)
                    Spacer(Modifier.height(4.dp))
                    StreamingDots()
                }
                // 截断：服务端没给出结束标记，正文可能不完整，既不是完成也不是失败
                MessageStatus.TRUNCATED -> {
                    if (message.content.isNotBlank()) MarkdownText(message.content)
                    PartialNote(truncationText.first, onRetry.takeIf { isLast })
                }
                MessageStatus.STOPPED -> {
                    if (message.content.isNotBlank()) MarkdownText(message.content)
                    PartialNote("已停止", onRetry.takeIf { isLast })
                }
                MessageStatus.INTERRUPTED -> {
                    if (message.content.isNotBlank()) MarkdownText(message.content)
                    PartialNote("已中断", onRetry.takeIf { isLast })
                }
                else -> MarkdownText(message.content)
            }
        }
    }
}

/** 部分回复的说明行：可重试时只对最后一条未完成的回复提供入口 */
@Composable
private fun PartialNote(label: String, onRetry: (() -> Unit)?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 6.dp)
    ) {
        Text(label, fontSize = 11.5.sp, color = GrayLight)
        if (onRetry != null) {
            Spacer(Modifier.width(4.dp))
            TextButton(
                onClick = onRetry,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
                Text("重试", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ErrorCard(
    errorText: Pair<String, String?>,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onPickModel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ErrorSoft)
            .border(1.dp, ErrorLine, RoundedCornerShape(16.dp))
            .padding(horizontal = 15.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = ErrorRed, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(7.dp))
            Text("回复失败", color = ErrorRed, fontSize = 13.5.sp, fontWeight = FontWeight.ExtraBold)
        }
        Spacer(Modifier.height(5.dp))
        Text(errorText.first, color = ErrorText, fontSize = 12.5.sp)
        Spacer(Modifier.height(12.dp))
        Row {
            when (errorText.second) {
                "settings" -> {
                    Button(
                        onClick = onOpenSettings,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = Ink),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)
                    ) { Text("去设置", fontSize = 12.5.sp, fontWeight = FontWeight.Bold) }
                }
                "model" -> {
                    Button(
                        onClick = onPickModel,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = Ink),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)
                    ) { Text("选择模型", fontSize = 12.5.sp, fontWeight = FontWeight.Bold) }
                }
                else -> {
                    Button(
                        onClick = onRetry,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = Ink),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("重试", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamingDots() {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "dots")
    val alpha by transition.animateFloat(
        initialValue = 0.25f, targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(600),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "alpha"
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) {
            Box(
                modifier = Modifier
                    .padding(end = 4.dp)
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(GrayLight.copy(alpha = alpha))
            )
        }
        Spacer(Modifier.width(3.dp))
        Text("正在生成…", fontSize = 12.sp, color = Gray)
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    generating: Boolean,
    enabled: Boolean,
    needConfig: Boolean,
    configDeleted: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onPickModel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        when {
            needConfig || configDeleted -> {
                Button(
                    onClick = onPickModel,
                    modifier = Modifier.fillMaxWidth(),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Ink)
                ) { Text("请选择模型", fontWeight = FontWeight.Bold) }
            }
            else -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color.White)
                        .border(1.dp, Line, RoundedCornerShape(22.dp))
                        .padding(start = 16.dp, end = 7.dp, top = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = onDraftChange,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("写下你的问题…", fontSize = 14.sp, color = Gray) },
                        maxLines = 5,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    if (generating) {
                        IconButton(
                            onClick = onStop,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Ink)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = "停止生成", tint = Color.White, modifier = Modifier.size(17.dp))
                        }
                    } else {
                        IconButton(
                            onClick = onSend,
                            enabled = draft.isNotBlank() && enabled,
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(if (draft.isNotBlank() && enabled) BrandBlue else Line)
                        ) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = "发送", tint = Color.White, modifier = Modifier.size(17.dp))
                        }
                    }
                }
            }
        }
    }
}
