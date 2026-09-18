package app.polyglot.dev.feature.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.polyglot.dev.data.local.SessionEntity
import androidx.compose.ui.text.style.TextOverflow
import app.polyglot.dev.ui.theme.Canvas
import app.polyglot.dev.ui.theme.BlueDeep
import app.polyglot.dev.ui.theme.BlueSel
import app.polyglot.dev.ui.theme.BlueSoft
import app.polyglot.dev.ui.theme.BrandBlue
import app.polyglot.dev.ui.theme.ErrorLine
import app.polyglot.dev.ui.theme.ErrorRed
import app.polyglot.dev.ui.theme.ErrorSoft
import app.polyglot.dev.ui.theme.ErrorText
import app.polyglot.dev.ui.theme.Gray
import app.polyglot.dev.ui.theme.Ink
import app.polyglot.dev.ui.theme.Line
import app.polyglot.dev.ui.theme.SoftBg
import kotlinx.coroutines.launch

/**
 * 换模型续聊页面（整页，不再用底部弹层）：
 * 有内容的会话复制当前对话创建独立副本，原会话保留；空会话直接就地换模型。
 *
 * 新会话标题**默认留空且必填**，不再自动填入原标题或模型名；
 * 创建失败时保留已选目标与已填标题，并给出明确提示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwitchModelPage(
    targets: List<ModelTarget>,
    current: SessionEntity?,
    /** 当前会话已有内容：需要标题并创建副本；否则就地换模型 */
    requiresTitle: Boolean,
    generatingOnCurrent: Boolean,
    switching: Boolean,
    onBack: () -> Unit,
    onStopAndContinue: () -> Unit,
    onConfirm: (ModelTarget, String, (Boolean, String?) -> Unit) -> Unit
) {
    BackHandler { onBack() }

    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val titleFocus = remember { FocusRequester() }

    // 默认选中"当前模型"，让用户先看清自己在哪个模型上再挑目标；
    // 没有当前会话时不预选，必须显式选一个，避免总是高亮列表第一项让人误以为已经选好了
    var selected by remember(current?.id) {
        mutableStateOf(
            targets.firstOrNull {
                it.configId == current?.configId && it.modelId == current?.modelId
            }
        )
    }
    // 默认留空；选择目标模型时不回填，避免把模型名当成会话标题
    var title by remember(current?.id) { mutableStateOf("") }
    var titleError by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showStopHint by remember { mutableStateOf(false) }

    fun submit() {
        val target = selected ?: return
        if (generatingOnCurrent) { showStopHint = true; return }
        if (requiresTitle && title.isBlank()) {
            titleError = "请输入新会话标题"
            titleFocus.requestFocus()
            scope.launch { scroll.animateScrollTo(scroll.maxValue) }
            return
        }
        titleError = null
        error = null
        onConfirm(target, title) { ok, err ->
            // 失败时保持页面与已填内容不变，只显示原因
            if (!ok) error = err ?: "创建失败，请重试"
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Canvas) {
        Scaffold(
            bottomBar = {
                Row(modifier = Modifier.fillMaxWidth().background(Canvas).navigationBarsPadding().imePadding().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(
                        onClick = onBack,
                        modifier = Modifier
                            .weight(1f).height(52.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(SoftBg)
                    ) { Text("取消", color = Ink, fontWeight = FontWeight.Bold) }
                    Button(
                        onClick = { submit() },
                        enabled = !switching && selected != null,
                        modifier = Modifier.weight(2f).height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BrandBlue)
                    ) {
                        Text(
                            if (switching) "创建中…" else if (requiresTitle) "创建新会话并继续" else "使用该模型",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            },
            topBar = {
                TopAppBar(
                    title = { Text("换模型续聊", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .verticalScroll(scroll)
                    .padding(horizontal = 20.dp)
            ) {
                Spacer(Modifier.height(12.dp))
                Text("换个模型，继续探索", fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (requiresTitle) "带上当前对话创建新会话，原会话仍会保留。" else "选择一个模型，开启接下来的对话。",
                    fontSize = 13.sp, lineHeight = 21.sp, color = Gray
                )
                Spacer(Modifier.height(26.dp))
                if (error != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(13.dp))
                            .background(ErrorSoft)
                            .border(1.dp, ErrorLine, RoundedCornerShape(13.dp))
                            .padding(horizontal = 13.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = ErrorRed, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(7.dp))
                        Text(error!!, fontSize = 12.5.sp, color = ErrorText, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { submit() }) {
                            Text("重试", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }

                Text("选择模型", fontSize = 12.sp, color = Gray, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(9.dp))
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    targets.forEach { target ->
                        val isCurrent = current != null &&
                            target.configId == current.configId && target.modelId == current.modelId
                        val isSelected = selected == target
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .border(1.5.dp, if (isSelected) BrandBlue else Line, RoundedCornerShape(18.dp))
                                .background(if (isSelected) BlueSel else Color.White)
                                .clickable { selected = target }
                                .padding(horizontal = 16.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(13.dp))
                                    .background(if (isSelected) BrandBlue else BlueSoft),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(target.modelId.take(1).uppercase(), color = if (isSelected) Color.White else BrandBlue, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                            }
                            Spacer(Modifier.width(11.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Column {
                                    Text(target.modelId, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (isCurrent) {
                                        Spacer(Modifier.width(7.dp))
                                        Text(
                                            "当前模型",
                                            fontSize = 10.sp,
                                            lineHeight = 16.sp,
                                            color = BlueDeep,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(99.dp))
                                                .background(BlueSoft)
                                                .padding(horizontal = 8.dp, vertical = 2.5.dp)
                                        )
                                    }
                                }
                                if (target.configName != target.modelId) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(target.configName, fontSize = 12.sp, color = Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .size(19.dp)
                                    .clip(CircleShape)
                                    .border(1.5.dp, if (isSelected) BrandBlue else Color(0xFFCBD0DB), CircleShape)
                                    .background(if (isSelected) BrandBlue else Color.Transparent),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                                }
                            }
                        }
                    }
                }
                if (targets.isEmpty()) {
                    Text("暂无可用模型，请先在设置中添加模型服务", fontSize = 12.5.sp, color = Gray, modifier = Modifier.padding(vertical = 10.dp))
                }

                // 只有需要创建副本时才要求标题；空会话就地换模型没有新会话可命名
                if (requiresTitle) {
                    Spacer(Modifier.height(18.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("新会话标题", fontSize = 12.sp, color = Gray, fontWeight = FontWeight.Bold)
                        // 填好之后不再一直挂着"必填"
                        if (title.isBlank()) {
                            Spacer(Modifier.width(4.dp))
                            Text("必填", fontSize = 11.sp, color = ErrorRed, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(9.dp))
                    OutlinedTextField(
                        value = title,
                        onValueChange = {
                            title = it
                            if (titleError != null && it.isNotBlank()) titleError = null
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(titleFocus),
                        singleLine = true,
                        placeholder = { Text("给这段新对话起个名字", fontSize = 14.sp, color = Gray) },
                        isError = titleError != null,
                        supportingText = titleError?.let { { Text(it, fontSize = 11.5.sp) } },
                        shape = RoundedCornerShape(13.dp)
                    )
                }

                Spacer(Modifier.height(30.dp))
            }
        }
    }

    if (showStopHint) {
        AlertDialog(
            onDismissRequest = { showStopHint = false },
            title = { Text("正在生成回复") },
            text = { Text("生成中的会话需要先停止回复，才能换模型创建副本。") },
            confirmButton = {
                TextButton(onClick = {
                    showStopHint = false
                    onStopAndContinue()
                }) { Text("停止回复") }
            },
            dismissButton = {
                TextButton(onClick = { showStopHint = false }) { Text("取消") }
            }
        )
    }
}
