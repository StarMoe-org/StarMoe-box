package moe.starmoe.box.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.starmoe.box.net.StoredSave
import moe.starmoe.box.save.FoundSave
import moe.starmoe.box.save.GameServer
import moe.starmoe.box.save.SafSaveSource
import moe.starmoe.box.shizuku.ShizukuShell
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val activity = LocalContext.current as android.app.Activity
    val snackbar = remember { SnackbarHostState() }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.dismissMessage()
        }
    }

    // Folder picker opened on a game directory (Android 12 and older): the grant is kept.
    var grantServer by remember { mutableStateOf<GameServer?>(null) }
    val grantPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree: Uri? ->
        val server = grantServer
        if (tree != null && server != null) vm.onGameDirPicked(tree, server)
    }
    var askGrant by remember { mutableStateOf(false) }
    if (askGrant) {
        GrantDialog(
            installed = { pkg -> isInstalled(activity, pkg) },
            onPick = { server, pkg ->
                askGrant = false
                grantServer = server
                grantPicker.launch(SafSaveSource.gameDirInitialUri(pkg))
            },
            onDismiss = { askGrant = false },
        )
    }

    // Any folder or files: the user names the build first, since a copied file does not say which it is.
    var pickServer by remember { mutableStateOf<GameServer?>(null) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree: Uri? ->
        val server = pickServer
        if (tree != null && server != null) vm.importTree(tree, server)
    }
    val filesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        val server = pickServer
        if (uris.isNotEmpty() && server != null) vm.importFiles(uris, server)
    }
    var askPick by remember { mutableStateOf<PickKind?>(null) }
    askPick?.let { kind ->
        ServerPickerDialog(
            kind = kind,
            onPick = { server ->
                askPick = null
                pickServer = server
                when (kind) {
                    PickKind.FOLDER -> folderPicker.launch(null)
                    PickKind.FILES -> filesPicker.launch(arrayOf("*/*"))
                }
            },
            onDismiss = { askPick = null },
        )
    }

    var confirmDelete by remember { mutableStateOf<StoredSave?>(null) }
    confirmDelete?.let { save ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            icon = { Icon(Icons.Outlined.DeleteOutline, null) },
            title = { Text("删除云端存档？") },
            text = { Text("${serverLabel(save.server)} · ${save.accountId}\n只删除服务器上的副本，手机上的游戏数据不受影响。") },
            confirmButton = {
                TextButton(onClick = { vm.deleteStored(save); confirmDelete = null }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消") } },
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("StarMoe Box") },
                actions = {
                    if (state.user != null) {
                        IconButton(onClick = { vm.signOut(activity) }) {
                            Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = "退出登录")
                        }
                    }
                },
                scrollBehavior = scroll,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { AccountCard(state, onSignIn = { vm.signIn(activity) }) }
            // Newest Android first suggests Shizuku; up to Android 12 a granted game directory is simpler.
            val grantFirst = SafSaveSource.canGrantGameDir && state.shizuku != ShizukuShell.Status.READY
            if (grantFirst) item { GameDirCard(state.grants, onGrant = { askGrant = true }, onRevoke = vm::revokeGrant) }
            item { ShizukuCard(state.shizuku, onRequest = vm::requestShizuku, onRefresh = vm::refreshShizuku) }
            if (!grantFirst && SafSaveSource.canGrantGameDir) {
                item { GameDirCard(state.grants, onGrant = { askGrant = true }, onRevoke = vm::revokeGrant) }
            }
            item {
                ScanCard(
                    state = state,
                    onScan = vm::scan,
                    onPickFolder = { askPick = PickKind.FOLDER },
                    onPickFiles = { askPick = PickKind.FILES },
                )
            }
            val found = state.found
            if (found.isNotEmpty()) {
                item {
                    SectionHeader("本机存档", action = if (state.user != null && found.size > 1) "全部上传" else null, onAction = vm::uploadAll)
                }
                items(found, key = { it.key + it.source }) { save ->
                    FoundSaveCard(save, state.uploads[save.key] ?: UploadState.Idle, canUpload = state.user != null) { vm.upload(save) }
                }
            }
            if (state.user != null) {
                item { SectionHeader("云端存档", action = "刷新", onAction = vm::refreshStored) }
                if (state.stored.isEmpty()) {
                    item {
                        Text(
                            "还没有上传过存档。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                } else {
                    items(state.stored, key = { "stored/${it.server}/${it.accountId}" }) { save ->
                        StoredSaveItem(save) { confirmDelete = save }
                    }
                }
            }
            item { Footnote() }
        }
    }
}

@Composable
private fun AccountCard(state: UiState, onSignIn: () -> Unit) {
    val user = state.user
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            leadingContent = { Icon(Icons.Outlined.AccountCircle, null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary) },
            headlineContent = { Text(user?.displayName ?: "StarMoe 通行证") },
            supportingContent = {
                Text(
                    when {
                        user != null -> user.username?.let { "@$it · 已登录" } ?: "已登录"
                        !state.passportConfigured -> "此版本未配置通行证，无法登录"
                        else -> "登录后可把存档上传到 StarMoe，在网站上查看"
                    },
                )
            },
            trailingContent = {
                if (user == null) {
                    Button(onClick = onSignIn, enabled = !state.signingIn && state.passportConfigured) {
                        if (state.signingIn) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text("登录")
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun ShizukuCard(status: ShizukuShell.Status, onRequest: () -> Unit, onRefresh: () -> Unit) {
    val context = LocalContext.current
    val ready = status == ShizukuShell.Status.READY
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (ready) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (ready) Icons.Outlined.CheckCircle else Icons.Outlined.Security,
                    contentDescription = null,
                    tint = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.width(12.dp))
                Text("Shizuku", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, contentDescription = "刷新状态") }
            }
            Text(
                when (status) {
                    ShizukuShell.Status.READY -> "已授权，可以读取游戏存档。"
                    ShizukuShell.Status.NEEDS_PERMISSION -> "Shizuku 正在运行，但还没有授权给本应用。"
                    ShizukuShell.Status.UNSUPPORTED -> "Shizuku 版本过旧，请更新到 v11 以上。"
                    ShizukuShell.Status.NOT_RUNNING -> if (SafSaveSource.canGrantGameDir) {
                        "也可以用 Shizuku 读取：安装后在其中选择「通过无线调试启动」，全程在手机上完成，不需要电脑和 root。"
                    } else {
                        "Android 13 起，只有 adb 身份能读游戏存档目录。请安装 Shizuku，在其中选择「通过无线调试启动」，全程在手机上完成，不需要电脑和 root。不想装的话，可以用下面的「选择文件夹」。"
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            when (status) {
                ShizukuShell.Status.NEEDS_PERMISSION -> FilledTonalButton(onClick = onRequest) { Text("授权") }
                ShizukuShell.Status.NOT_RUNNING, ShizukuShell.Status.UNSUPPORTED -> FilledTonalButton(onClick = {
                    val launch = context.packageManager.getLaunchIntentForPackage(ShizukuShell.MANAGER_PACKAGE)
                    val intent = launch ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
                    context.startActivity(intent)
                }) { Text("打开 Shizuku") }
                ShizukuShell.Status.READY -> Unit
            }
        }
    }
}

@Composable
private fun ScanCard(state: UiState, onScan: () -> Unit, onPickFolder: () -> Unit, onPickFiles: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("读取存档", style = MaterialTheme.typography.titleMedium)
            GameServer.entries.forEach { server -> ServerRow(server, state.scans[server] ?: ServerScan.NotScanned) }
            AnimatedVisibility(state.busy) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    state.progress?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Button(onClick = onScan, enabled = !state.busy && state.canScan, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Search, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        state.shizuku == ShizukuShell.Status.READY -> "通过 Shizuku 扫描本机"
                        state.grants.isNotEmpty() -> "读取已授权的游戏目录"
                        else -> "扫描本机（需要 Shizuku 或目录授权）"
                    },
                )
            }
            HorizontalDivider()
            Text(
                "或者从文件管理器里找：把游戏目录 Android/data/<包名>/files/ 下那个一长串字母数字命名的文件夹" +
                    "（就是账号数据）复制到「下载」等普通位置，再在这里选择它。也支持电脑取包工具生成的 zip。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPickFolder, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.FolderOpen, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("选择文件夹")
                }
                OutlinedButton(onClick = onPickFiles, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.FileOpen, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("选择文件")
                }
            }
        }
    }
}

/** Android 12 and older: grant the game's directory once through the folder picker. */
@Composable
private fun GameDirCard(grants: List<GameGrant>, onGrant: () -> Unit, onRevoke: (GameGrant) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (grants.isEmpty()) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (grants.isEmpty()) Icons.Outlined.FolderOpen else Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = if (grants.isEmpty()) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Text("授权游戏目录", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            Text(
                if (grants.isEmpty()) {
                    "这台手机的系统版本允许直接授权游戏目录，不用装 Shizuku。点下面的按钮，在打开的页面底部点「使用此文件夹」→「允许」即可，只需一次。"
                } else {
                    "已授权，之后每次一键读取。"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            grants.forEach { grant ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(grant.server.label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            grant.packageName, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onRevoke(grant) }) { Text("取消授权") }
                }
            }
            FilledTonalButton(onClick = onGrant) { Text(if (grants.isEmpty()) "授权游戏目录" else "再授权一个") }
        }
    }
}

@Composable
private fun GrantDialog(installed: (String) -> Boolean, onPick: (GameServer, String) -> Unit, onDismiss: () -> Unit) {
    val choices = GameServer.entries.flatMap { server -> server.packageNames.map { server to it } }
    val present = choices.filter { (_, pkg) -> installed(pkg) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("授权哪个游戏？") },
        text = {
            Column {
                Text(
                    if (present.isEmpty()) "没有检测到已安装的 Our Notes。如果确实装了，也可以直接选择。"
                    else "选择后会打开系统的文件夹页面，直接点底部的「使用此文件夹」。",
                )
                Spacer(Modifier.size(8.dp))
                HorizontalDivider()
                present.ifEmpty { choices }.forEach { (server, pkg) ->
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        headlineContent = { Text(server.label) },
                        supportingContent = { Text(pkg, fontFamily = FontFamily.Monospace) },
                        trailingContent = { TextButton(onClick = { onPick(server, pkg) }) { Text("授权") } },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private enum class PickKind { FOLDER, FILES }

private fun isInstalled(context: android.content.Context, pkg: String): Boolean = runCatching {
    context.packageManager.getPackageInfo(pkg, 0)
    true
}.getOrDefault(false)
@Composable
private fun ServerRow(server: GameServer, scan: ServerScan) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(server.label, style = MaterialTheme.typography.bodyLarge)
            Text(server.regions, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val (text, color) = when (scan) {
            ServerScan.NotScanned -> "未扫描" to MaterialTheme.colorScheme.onSurfaceVariant
            ServerScan.Scanning -> "扫描中…" to MaterialTheme.colorScheme.primary
            ServerScan.NotInstalled -> "未安装" to MaterialTheme.colorScheme.onSurfaceVariant
            ServerScan.NoSave -> "没有存档，先进一次游戏" to MaterialTheme.colorScheme.onSurfaceVariant
            ServerScan.NotGranted -> "未授权该游戏目录" to MaterialTheme.colorScheme.onSurfaceVariant
            ServerScan.Unreadable -> "找到存档但读不出账号，可能游戏更新了格式" to MaterialTheme.colorScheme.error
            is ServerScan.Failed -> "读取失败：${scan.message}" to MaterialTheme.colorScheme.error
            is ServerScan.Found -> "找到 ${scan.saves.size} 个账号" to MaterialTheme.colorScheme.primary
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun SectionHeader(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun FoundSaveCard(save: FoundSave, upload: UploadState, canUpload: Boolean, onUpload: () -> Unit) {
    OutlinedCardBox {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            overlineContent = { Text("${save.server.label} · ${formatSize(save.player.size.toLong())}") },
            headlineContent = { Text(save.info.name ?: "（未命名）", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Column {
                    Text(save.info.accountId, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    when (upload) {
                        is UploadState.Done -> Text(
                            if (upload.changed) "已上传" else "云端已是最新",
                            color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall,
                        )
                        is UploadState.Failed -> Text(upload.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        else -> Unit
                    }
                }
            },
            trailingContent = {
                when (upload) {
                    UploadState.Uploading -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    is UploadState.Done -> Icon(Icons.Outlined.CloudDone, contentDescription = "已上传", tint = MaterialTheme.colorScheme.primary)
                    else -> FilledTonalButton(onClick = onUpload, enabled = canUpload) {
                        Icon(
                            if (upload is UploadState.Failed) Icons.Outlined.ErrorOutline else Icons.Outlined.CloudUpload,
                            null, modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (upload is UploadState.Failed) "重试" else "上传")
                    }
                }
            },
        )
    }
}

@Composable
private fun StoredSaveItem(save: StoredSave, onDelete: () -> Unit) {
    OutlinedCardBox {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            overlineContent = { Text("${serverLabel(save.server)} · ${formatSize(save.size)}") },
            headlineContent = { Text(save.accountId, fontFamily = FontFamily.Monospace) },
            supportingContent = {
                Text("更新于 ${formatTime(save.uploadedAt)}，最近同步 ${formatTime(save.checkedAt)}", style = MaterialTheme.typography.bodySmall)
            },
            trailingContent = {
                IconButton(onClick = onDelete) { Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除") }
            },
        )
    }
}

@Composable
private fun OutlinedCardBox(content: @Composable () -> Unit) {
    androidx.compose.material3.OutlinedCard(modifier = Modifier.fillMaxWidth()) { content() }
}

@Composable
private fun ServerPickerDialog(kind: PickKind, onPick: (GameServer) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("这是哪个服的存档？") },
        text = {
            Column {
                Text(
                    when (kind) {
                        PickKind.FOLDER -> "复制出来的文件夹看不出来自哪个服，请先选择，然后选中那个账号文件夹（或它的上一层）。"
                        PickKind.FILES -> "文件本身看不出来自哪个服，请先选择。可以一次多选账号文件夹里的文件，或电脑取包工具生成的 zip。"
                    },
                )
                Spacer(Modifier.size(8.dp))
                HorizontalDivider()
                GameServer.entries.forEach { server ->
                    ListItem(
                        modifier = Modifier.padding(vertical = 2.dp),
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        headlineContent = { Text(server.label) },
                        supportingContent = { Text(server.regions) },
                        trailingContent = { TextButton(onClick = { onPick(server) }) { Text("选择") } },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun Footnote() {
    Text(
        "存档在手机上解密后，只上传其中的玩家数据（_player），原样不做任何改动；只读取游戏自己的存档文件，不会修改或删除任何东西。\n\n开发灵感来自 @禁祀物tab，感谢。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
    )
}

private fun serverLabel(id: String) = GameServer.byId(id)?.label ?: id

private fun formatSize(bytes: Long): String = when {
    bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1 shl 10 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatTime(millis: Long): String =
    if (millis <= 0) "—" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))
