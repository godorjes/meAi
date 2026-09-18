package app.polyglot.dev.feature.config

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.polyglot.dev.data.repo.ModelOrigin
import app.polyglot.dev.data.repo.ProviderPresets
import app.polyglot.dev.ui.theme.BrandBlue
import app.polyglot.dev.ui.theme.ErrorRed
import app.polyglot.dev.ui.theme.FormSection
import app.polyglot.dev.ui.theme.Gray
import app.polyglot.dev.ui.theme.GrayLight
import app.polyglot.dev.ui.theme.Ink
import app.polyglot.dev.ui.theme.Line
import app.polyglot.dev.ui.theme.SoftBg
import app.polyglot.dev.ui.theme.SuccessGreen

/** 一个字段的焦点与滚动定位器，保证聚焦后该字段可见 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private class FieldLocator {
    val focus = FocusRequester()
    val bring = BringIntoViewRequester()
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ConfigEditScreen(
    container: app.polyglot.dev.AppContainer,
    configId: String?,
    onBack: () -> Unit,
    onSavedStartChat: () -> Unit
) {
    val context = LocalContext.current
    val vm: ConfigEditViewModel = viewModel(factory = viewModelFactory {
        initializer { ConfigEditViewModel(context.applicationContext as android.app.Application) }
    })
    val form by vm.form.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    val catalogState by vm.catalogState.collectAsStateWithLifecycle()
    val primaryChoices by vm.modelChoices.collectAsStateWithLifecycle()
    val testState by vm.test.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()
    val focusRequest by vm.focusRequest.collectAsStateWithLifecycle()
    val listUpdatedAt by vm.listUpdatedAt.collectAsStateWithLifecycle()

    val fields = ConfigField.entries.map { remember { FieldLocator() } }

    LaunchedEffect(focusRequest) {
        val request = focusRequest ?: return@LaunchedEffect
        val locator = fields[request.field.ordinal]
        locator.focus.requestFocus()
        locator.bring.bringIntoView()
    }

    val snackbar = remember { SnackbarHostState() }
    var keyVisible by remember { mutableStateOf(false) }
    var providerMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    // 选择模型时可输入关键字缩小列表，不强迫输入
    var modelFilter by remember(form.providerType) { mutableStateOf("") }

    LaunchedEffect(configId) { vm.load(configId) }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }
    LaunchedEffect(saved) { if (saved) onSavedStartChat() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (form.isNew) "添加模型服务" else "编辑模型服务",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(12.dp))
            Text(
                "连接你的模型，让灵感随时开始。",
                fontSize = 14.sp, color = Gray
            )
            Spacer(Modifier.height(16.dp))

            FormSection("连接服务", "选择模型厂家，填写对应的 API Key") {
            // ---------- 1 厂家 ----------
            FieldLabel(
                "厂家",
                required = true,
                filled = form.providerType.isNotBlank()
            )
            ExposedDropdownMenuBox(
                expanded = providerMenu,
                onExpandedChange = { providerMenu = it }
            ) {
                OutlinedTextField(
                    value = ProviderPresets.byType(form.providerType).label,
                    onValueChange = {},
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true)
                        .focusRequester(fields[ConfigField.PROVIDER_TYPE.ordinal].focus)
                        .bringIntoViewRequester(fields[ConfigField.PROVIDER_TYPE.ordinal].bring)
                        .fillMaxWidth(),
                    readOnly = true,
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    isError = errors[ConfigField.PROVIDER_TYPE] != null,
                    supportingText = errors[ConfigField.PROVIDER_TYPE]?.let { { FieldError(it) } },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerMenu) }
                )
                ExposedDropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
                    ProviderPresets.selectable.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(preset.label) },
                            onClick = {
                                vm.updateProviderType(preset.type)
                                providerMenu = false
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))

            // ---------- 2 API Key ----------
            FieldLabel("API Key", required = true, filled = form.apiKey.isNotBlank())
            OutlinedTextField(
                value = form.apiKey,
                onValueChange = vm::updateApiKey,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(fields[ConfigField.API_KEY.ordinal].focus)
                    .bringIntoViewRequester(fields[ConfigField.API_KEY.ordinal].bring),
                singleLine = true,
                visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                shape = RoundedCornerShape(12.dp),
                isError = errors[ConfigField.API_KEY] != null,
                supportingText = errors[ConfigField.API_KEY]?.let { { FieldError(it) } },
                trailingIcon = {
                    IconButton(onClick = { keyVisible = !keyVisible }) {
                        Icon(
                            if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (keyVisible) "隐藏 API Key" else "显示 API Key"
                        )
                    }
                }
            )
            val helpUrl = ProviderPresets.byType(form.providerType).keyHelpUrl
            if (helpUrl.isNotBlank()) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(helpUrl)))
                    }) {
                        Text("获取 API Key", fontSize = 12.5.sp)
                        Spacer(Modifier.size(4.dp))
                        Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(13.dp))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))

            }
            Spacer(Modifier.height(16.dp))
            FormSection("模型与名称", "选择模型，为这项服务取一个容易识别的名字") {
            // ---------- 3 模型 ----------
            FieldLabel("模型", required = true, filled = form.selectedModelId.isNotBlank())
            if (form.manualModelEntry) {
                OutlinedTextField(
                    value = form.selectedModelId,
                    onValueChange = vm::updateSelectedModel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(fields[ConfigField.MODEL.ordinal].focus)
                        .bringIntoViewRequester(fields[ConfigField.MODEL.ordinal].bring),
                    placeholder = { Text("模型 ID，如 deepseek-chat") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    isError = errors[ConfigField.MODEL] != null,
                    supportingText = errors[ConfigField.MODEL]?.let { { FieldError(it) } }
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { vm.setManualModelEntry(false) }) {
                        Text("从列表选择", fontSize = 12.sp)
                    }
                }
            } else {
                ExposedDropdownMenuBox(
                    expanded = modelMenu,
                    onExpandedChange = { modelMenu = it }
                ) {
                    OutlinedTextField(
                        value = form.selectedModelId,
                        onValueChange = {},
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true)
                            .focusRequester(fields[ConfigField.MODEL.ordinal].focus)
                            .bringIntoViewRequester(fields[ConfigField.MODEL.ordinal].bring)
                            .fillMaxWidth(),
                        readOnly = true,
                        singleLine = true,
                        placeholder = { Text("从列表中选择模型") },
                        shape = RoundedCornerShape(12.dp),
                        isError = errors[ConfigField.MODEL] != null,
                        supportingText = errors[ConfigField.MODEL]?.let { { FieldError(it) } },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenu) }
                    )
                    ExposedDropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                        OutlinedTextField(
                            value = modelFilter,
                            onValueChange = { modelFilter = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            placeholder = { Text("可选：输入关键字缩小列表", fontSize = 12.5.sp) },
                            singleLine = true,
                            shape = RoundedCornerShape(11.dp)
                        )
                        val filter = modelFilter.trim()
                        val shown = primaryChoices.filter {
                            filter.isEmpty() || it.id.contains(filter, ignoreCase = true)
                        }
                        if (shown.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("暂无模型，可手动填写模型 ID", color = Gray, fontSize = 12.5.sp) },
                                onClick = { }
                            )
                        }
                        shown.forEach { choice ->
                            DropdownMenuItem(
                                text = { ModelRow(choice) },
                                onClick = {
                                    vm.updateSelectedModel(choice.id)
                                    modelMenu = false
                                    modelFilter = ""
                                }
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (catalogState == CatalogUiState.FRESH && listUpdatedAt != null) {
                            "更新于 ${timeText(listUpdatedAt)}"
                        } else {
                            "预置模型列表"
                        },
                        fontSize = 11.sp, color = GrayLight
                    )
                    TextButton(onClick = { vm.setManualModelEntry(true) }) {
                        Text("手动填写", fontSize = 12.sp)
                    }
                }
            }

            // 参考模型已合进上面的模型列表，各条目用行内标注区分来源
            Spacer(Modifier.height(16.dp))

            // ---------- 4 测试连接 ----------
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { vm.testConnection() },
                    enabled = testState != TestUiState.Testing,
                    modifier = Modifier.heightIn(min = 48.dp),
                    shape = CircleShape
                ) {
                    if (testState == TestUiState.Testing) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(6.dp))
                    } else {
                        Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.size(4.dp))
                    }
                    Text(
                        if (testState == TestUiState.Testing) "测试中…" else "测试连接",
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.size(14.dp))
                when (testState) {
                    TestUiState.Testing -> Text("测试中…", color = Gray, fontSize = 12.5.sp)
                    is TestUiState.Success -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(5.dp))
                        Text("连接成功", color = SuccessGreen, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                    }
                    is TestUiState.Fail -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = ErrorRed, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(5.dp))
                        Text("连接失败", color = ErrorRed, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                    }
                    else -> Text("未测试", color = Gray, fontSize = 12.5.sp)
                }
            }
            Text(
                "只测试当前选定的模型；测试失败也可以保存。",
                fontSize = 11.sp, color = GrayLight, modifier = Modifier.padding(top = 8.dp)
            )

            // ---------- 5 模型名称 ----------
            Spacer(Modifier.height(20.dp))
            FieldLabel("模型名称", required = true, filled = form.modelName.isNotBlank())
            OutlinedTextField(
                value = form.modelName,
                onValueChange = vm::updateModelName,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(fields[ConfigField.MODEL_NAME.ordinal].focus)
                    .bringIntoViewRequester(fields[ConfigField.MODEL_NAME.ordinal].bring),
                placeholder = { Text("默认为当前选中的模型", fontSize = 12.sp) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                isError = errors[ConfigField.MODEL_NAME] != null,
                supportingText = errors[ConfigField.MODEL_NAME]?.let { { FieldError(it) } }
            )
            Text(
                "这个名字只用在本机区分不同的模型服务，不会发给模型。",
                fontSize = 11.sp, color = GrayLight, modifier = Modifier.padding(top = 6.dp)
            )

            }
            // ---------- 6 保存 ----------
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { vm.save() },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Ink)
            ) {
                Text(if (form.isNew) "保存并开始对话" else "保存", fontWeight = FontWeight.Bold)
            }

            // ---------- 7 高级设置（默认折叠） ----------
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { vm.toggleAdvanced() }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("高级设置", fontSize = 12.5.sp, color = Gray, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(4.dp))
                Icon(
                    if (form.advancedOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (form.advancedOpen) "收起高级设置" else "展开高级设置",
                    tint = Gray, modifier = Modifier.size(18.dp)
                )
            }
            if (form.advancedOpen) {
                Spacer(Modifier.height(8.dp))
                FieldLabel("API 地址")
                OutlinedTextField(
                    value = form.baseUrl,
                    onValueChange = vm::updateBaseUrl,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(fields[ConfigField.BASE_URL.ordinal].focus)
                        .bringIntoViewRequester(fields[ConfigField.BASE_URL.ordinal].bring),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    shape = RoundedCornerShape(12.dp),
                    isError = errors[ConfigField.BASE_URL] != null,
                    supportingText = errors[ConfigField.BASE_URL]?.let { { FieldError(it) } }
                )
                Text(
                    "预设厂家已自动填好地址；只有自定义服务才需要改。",
                    fontSize = 11.sp, color = GrayLight, modifier = Modifier.padding(top = 6.dp)
                )
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

/** 一行模型选项：模型 ID + 描述 + 来源状态 */
@Composable
private fun ModelRow(choice: ModelChoice) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(choice.id, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            originBadgeOf(choice.origin)?.let { (label, color) ->
                Spacer(Modifier.width(6.dp))
                OriginBadge(label, color)
            }
        }
        if (choice.desc.isNotBlank()) {
            Text(choice.desc, fontSize = 11.sp, color = Gray, maxLines = 1)
        }
    }
}

/**
 * 来源角标的文案与颜色；返回 null 表示这条不显示角标。
 * 普通模型不显示来源角标，仅提示已保存但本次列表未返回的模型。
 */
private fun originBadgeOf(origin: ModelOrigin): Pair<String, Color>? = when (origin) {
    ModelOrigin.SERVICE_RETURNED -> null
    ModelOrigin.NOT_RETURNED_THIS_TIME -> "本次列表未返回" to Gray
    ModelOrigin.PUBLIC_REFERENCE -> null
}

@Composable
private fun OriginBadge(label: String, color: Color) {
    Text(
        label,
        fontSize = 9.5.sp,
        color = color,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(SoftBg, RoundedCornerShape(99.dp))
            .border(1.dp, Line, RoundedCornerShape(99.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    )
}

/**
 * 字段标题。[required] 表示该字段必填，[filled] 表示当前已有值。
 * "必填"只在还没填的时候出现：已经填好的字段不必一直挂着红字。
 */
@Composable
private fun FieldLabel(text: String, required: Boolean = false, filled: Boolean = false) {
    Row(modifier = Modifier.padding(bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 13.sp, color = Ink, fontWeight = FontWeight.Medium)
        if (required && !filled) {
            Spacer(Modifier.size(3.dp))
            Text("必填", fontSize = 10.sp, color = ErrorRed, fontWeight = FontWeight.Bold)
        }
    }
}

/** 字段下方的具体错误提示 */
@Composable
private fun FieldError(text: String) {
    Text(text, fontSize = 11.5.sp, color = ErrorRed)
}

/** 缓存更新时间，只显示到分钟 */
private fun timeText(millis: Long?): String =
    if (millis == null) "" else java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(millis)
