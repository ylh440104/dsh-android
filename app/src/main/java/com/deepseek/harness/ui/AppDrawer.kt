package com.deepseek.harness.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepseek.harness.model.Conversation
import com.deepseek.harness.model.StoredAccount

@Composable
fun AppDrawer(
    accounts: List<StoredAccount>,
    activeId: String?,
    totalBalanceText: String,
    autoSwitch: Boolean,
    loadingBalance: Boolean,
    conversations: List<Conversation>,
    currentId: String?,
    shizukuInstalled: Boolean,
    shizukuRunning: Boolean,
    shizukuGranted: Boolean,
    storageGranted: Boolean,
    onAddAccount: () -> Unit,
    onSwitchAccount: (String) -> Unit,
    onRemoveAccount: (String) -> Unit,
    onToggleAutoSwitch: () -> Unit,
    onNewConversation: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onRefresh: () -> Unit,
    onRequestShizuku: () -> Unit,
    onInstallShizuku: () -> Unit,
    onRequestStorage: () -> Unit,
    onSignOut: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxHeight()
            .width(310.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.fillMaxHeight()) {
            AccountPanel(
                accounts = accounts,
                activeId = activeId,
                totalBalanceText = totalBalanceText,
                autoSwitch = autoSwitch,
                loadingBalance = loadingBalance,
                onAddAccount = onAddAccount,
                onSwitchAccount = onSwitchAccount,
                onRemoveAccount = onRemoveAccount,
                onToggleAutoSwitch = onToggleAutoSwitch,
                onRefresh = onRefresh
            )

            Divider(color = MaterialTheme.colorScheme.surfaceVariant)

            PermissionPanel(
                shizukuInstalled = shizukuInstalled,
                shizukuRunning = shizukuRunning,
                shizukuGranted = shizukuGranted,
                storageGranted = storageGranted,
                onRequestShizuku = onRequestShizuku,
                onInstallShizuku = onInstallShizuku,
                onRequestStorage = onRequestStorage
            )

            Divider(color = MaterialTheme.colorScheme.surfaceVariant)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "会话",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.Default.Add,
                    contentDescription = "新建会话",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable { onNewConversation() }
                )
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(conversations, key = { it.id }) { conversation ->
                    ConversationRow(
                        conversation = conversation,
                        selected = conversation.id == currentId,
                        onOpen = { onOpenConversation(conversation.id) },
                        onDelete = { onDeleteConversation(conversation.id) }
                    )
                }
            }

            Divider(color = MaterialTheme.colorScheme.surfaceVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSignOut() }
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "退出全部账号",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun AccountPanel(
    accounts: List<StoredAccount>,
    activeId: String?,
    totalBalanceText: String,
    autoSwitch: Boolean,
    loadingBalance: Boolean,
    onAddAccount: () -> Unit,
    onSwitchAccount: (String) -> Unit,
    onRemoveAccount: (String) -> Unit,
    onToggleAutoSwitch: () -> Unit,
    onRefresh: () -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "账号",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (loadingBalance) {
                CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "刷新额度",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(15.dp)
                        .clickable { onRefresh() }
                )
            }
            Spacer(Modifier.width(14.dp))
            Icon(
                Icons.Default.Add,
                contentDescription = "添加账号",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(18.dp)
                    .clickable { onAddAccount() }
            )
        }

        Spacer(Modifier.height(10.dp))

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(
                    "总额度",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    totalBalanceText,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "共 ${accounts.size} 个账号",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        for (account in accounts) {
            AccountRow(
                account = account,
                active = account.localId == activeId,
                canRemove = accounts.size > 1,
                onSwitch = { onSwitchAccount(account.localId) },
                onRemove = { onRemoveAccount(account.localId) }
            )
            Spacer(Modifier.height(6.dp))
        }

        Spacer(Modifier.height(2.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "额度用尽自动切换",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    if (autoSwitch) "用尽后自动使用下一个账号" else "已关闭，用尽后停止",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = autoSwitch, onCheckedChange = { onToggleAutoSwitch() })
        }
    }
}

@Composable
private fun AccountRow(
    account: StoredAccount,
    active: Boolean,
    canRemove: Boolean,
    onSwitch: () -> Unit,
    onRemove: () -> Unit
) {
    val border = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, border),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSwitch() }
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        when {
                            active -> MaterialTheme.colorScheme.primary
                            account.quotaExhausted -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        RoundedCornerShape(4.dp)
                    )
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        account.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (active) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "当前",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (account.quotaExhausted) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "已用尽",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    account.balanceLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (account.lastError != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (account.totalValue > 0) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "合计 ${com.deepseek.harness.model.formatAmount(account.totalValue)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (canRemove) {
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "移除账号",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(
    conversation: Conversation,
    selected: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    val bg = if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable { onOpen() }
            .padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                conversation.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${conversation.messages.size} 条消息",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "删除",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp)
            )
        }
    }
}

@Composable
private fun PermissionPanel(
    shizukuInstalled: Boolean,
    shizukuRunning: Boolean,
    shizukuGranted: Boolean,
    storageGranted: Boolean,
    onRequestShizuku: () -> Unit,
    onInstallShizuku: () -> Unit,
    onRequestStorage: () -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
        Text(
            "权限",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        PermissionRow(
            label = "存储访问",
            detail = if (storageGranted) "已授权，可读写 /sdcard" else "未授权，仅能访问应用私有目录",
            granted = storageGranted,
            actionText = if (storageGranted) null else "去授权",
            onAction = onRequestStorage
        )
        Spacer(Modifier.height(8.dp))
        PermissionRow(
            label = "Shizuku",
            detail = when {
                shizukuGranted -> "已授权，可执行提权命令"
                shizukuRunning -> "服务已运行，等待授权"
                shizukuInstalled -> "已安装，服务未启动"
                else -> "未安装"
            },
            granted = shizukuGranted,
            actionText = when {
                shizukuGranted -> null
                shizukuRunning -> "授权"
                else -> "打开"
            },
            onAction = if (shizukuRunning) onRequestShizuku else onInstallShizuku
        )
    }
}

@Composable
private fun PermissionRow(
    label: String,
    detail: String,
    granted: Boolean,
    actionText: String?,
    onAction: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(
                    if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    RoundedCornerShape(4.dp)
                )
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (actionText != null) {
            Text(
                actionText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onAction() }
            )
        }
    }
}

@Composable
fun EmptyConversationHint() {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                "开始新对话",
                style = MaterialTheme.typography.titleMedium,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                "可以直接提问，也可以让它读写 /sdcard 里的文件",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}