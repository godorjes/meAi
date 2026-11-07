package app.polyglot.dev.feature.config

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.size
import app.polyglot.dev.ui.theme.BrandMark
import app.polyglot.dev.ui.theme.BlueSoft
import app.polyglot.dev.ui.theme.BrandBlue
import app.polyglot.dev.ui.theme.Line
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.polyglot.dev.PolyglotApp
import app.polyglot.dev.data.local.ProviderEntity
import app.polyglot.dev.ui.theme.Gray
import app.polyglot.dev.ui.theme.Ink
import app.polyglot.dev.ui.theme.SuccessGreen
import app.polyglot.dev.ui.theme.ErrorRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigListScreen(
    container: app.polyglot.dev.AppContainer,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit
) {
    val context = LocalContext.current
    val vm: ConfigListViewModel = viewModel(factory = viewModelFactory {
        initializer { ConfigListViewModel(context.applicationContext as android.app.Application) }
    })
    val providers by vm.providers.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }

    ConfigListContent(
        providers = providers,
        snackbar = snackbar,
        modelCount = vm::modelCount,
        onBack = onBack,
        onAdd = onAdd,
        onEdit = onEdit,
        onDelete = vm::deleteProvider
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConfigListContent(
    providers: List<ProviderEntity>,
    snackbar: SnackbarHostState,
    modelCount: (ProviderEntity) -> Int,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    var pendingDelete by remember { mutableStateOf<ProviderEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("模型服务", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onAdd) {
                        Icon(Icons.Default.Add, contentDescription = "添加模型服务")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        if (providers.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                BrandMark()
                Spacer(Modifier.height(24.dp))
                Text("连接你的第一个模型", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text("把喜欢的 AI，放在同一个地方。", color = Gray, fontSize = 14.sp)
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = onAdd,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Ink)
                ) { Text("添加模型服务", fontWeight = FontWeight.Bold) }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Column(Modifier.padding(top = 12.dp, bottom = 16.dp)) {
                        Text("你的 AI 工具箱", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                        Spacer(Modifier.height(8.dp))
                        Text("管理连接，随时切换不同的思考方式。", fontSize = 13.sp, color = Gray)
                    }
                }
                items(providers, key = { it.id }) { p ->
                    Card(
                        modifier = Modifier.border(1.dp, Line, RoundedCornerShape(20.dp)),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onEdit(p.id) }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(BlueSoft), contentAlignment = Alignment.Center) {
                                Text(p.name.take(1).uppercase(), fontSize = 18.sp, color = BrandBlue, fontWeight = FontWeight.SemiBold)
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(p.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val (label, color) = when (p.testState) {
                                        "success" -> "上次测试成功" to SuccessGreen
                                        "fail" -> "上次测试失败" to ErrorRed
                                        else -> "未测试" to Gray
                                    }
                                    Text(label, fontSize = 11.5.sp, color = color)
                                    Text(" · ${modelCount(p)} 个模型", fontSize = 11.5.sp, color = Gray)
                                }
                            }
                            IconButton(onClick = { pendingDelete = p }) {
                                Icon(Icons.Default.Delete, contentDescription = "删除 ${p.name}", tint = Gray)
                            }
                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Gray)
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }

    pendingDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除模型服务？") },
            text = { Text("将删除「${p.name}」及其密钥；历史聊天保留模型名称快照，仍可阅读。") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(p.id)
                    pendingDelete = null
                }) { Text("删除", color = ErrorRed) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}
