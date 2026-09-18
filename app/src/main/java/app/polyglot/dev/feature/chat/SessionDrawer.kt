package app.polyglot.dev.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.polyglot.dev.data.local.SessionEntity
import app.polyglot.dev.ui.theme.BlueSoft
import app.polyglot.dev.ui.theme.BrandBlue
import app.polyglot.dev.ui.theme.Gray
import app.polyglot.dev.ui.theme.GrayLight
import app.polyglot.dev.ui.theme.Ink
import app.polyglot.dev.ui.theme.SoftBg
import java.util.Calendar

/** 历史会话抽屉：新建、搜索、按时间分组、重命名/删除、模型设置入口 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionDrawer(
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
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Ink),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Language, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
            }
            Spacer(Modifier.width(9.dp))
            Text(
                "Polyglot",
                fontSize = 16.5.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Button(
                onClick = onNewChat,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Ink)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("新建会话", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearch,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索会话", fontSize = 12.5.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
        }
        Spacer(Modifier.height(14.dp))

        val groups = remember(sessions) { groupSessions(sessions) }
        groups.forEach { (label, items) ->
            Text(
                label,
                fontSize = 11.sp,
                color = GrayLight,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp)
            )
            items.forEach { session ->
                SessionRow(
                    session = session,
                    active = session.id == currentSessionId,
                    onClick = { onOpenSession(session.id) },
                    onRename = { t -> onRename(session.id, t) },
                    onDelete = { onDelete(session.id) }
                )
            }
        }
        if (sessions.isEmpty()) {
            Text(
                "暂无会话",
                fontSize = 12.5.sp,
                color = GrayLight,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 18.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    session: SessionEntity,
    active: Boolean,
    onClick: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var renameText by remember(session.id) { mutableStateOf(session.title) }

    Box(
        modifier = Modifier
            .padding(horizontal = 10.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(if (active) BlueSoft else Color.Transparent)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
                .padding(horizontal = 10.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.ChatBubbleOutline, contentDescription = null, tint = GrayLight, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(10.dp))
            // 标题一行、模型名一行：模型名再长也不会把标题挤掉
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    session.title.ifBlank { "新会话" },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    session.modelNameSnapshot.substringAfterLast("·").trim().ifBlank { session.modelNameSnapshot },
                    fontSize = 11.sp,
                    color = Gray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("重命名") }, onClick = { menuOpen = false; renameOpen = true })
                DropdownMenuItem(text = { Text("删除", color = app.polyglot.dev.ui.theme.ErrorRed) }, onClick = {
                    menuOpen = false
                    onDelete()
                })
            }
        }
    }

    if (renameOpen) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renameOpen = false },
            title = { Text("重命名会话") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    if (renameText.isNotBlank()) onRename(renameText.trim())
                    renameOpen = false
                }) { Text("保存") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { renameOpen = false }) { Text("取消") }
            }
        )
    }
}

private fun groupSessions(sessions: List<SessionEntity>): List<Pair<String, List<SessionEntity>>> {
    val startOfToday = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val startOfYesterday = startOfToday - 24L * 3600 * 1000

    val today = sessions.filter { it.updatedAt >= startOfToday }
    val yesterday = sessions.filter { it.updatedAt in startOfYesterday until startOfToday }
    val earlier = sessions.filter { it.updatedAt < startOfYesterday }

    return buildList {
        if (today.isNotEmpty()) add("今天" to today)
        if (yesterday.isNotEmpty()) add("昨天" to yesterday)
        if (earlier.isNotEmpty()) add("更早" to earlier)
    }
}

/** 抽屉底部的设置入口（独立成行，便于底部固定） */
@Composable
fun DrawerFooter(onOpenConfig: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenConfig)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Settings, contentDescription = null, tint = Gray, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(9.dp))
        Text(
            "设置 · 模型服务",
            fontSize = 12.5.sp, color = Gray, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}
