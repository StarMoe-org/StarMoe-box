package moe.starmoe.box.ui

import android.app.Activity
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.starmoe.box.net.ApiException
import moe.starmoe.box.net.Passport
import moe.starmoe.box.net.PassportUser
import moe.starmoe.box.net.SavesApi
import moe.starmoe.box.net.StoredSave
import moe.starmoe.box.save.FoundSave
import moe.starmoe.box.save.GameServer
import moe.starmoe.box.save.SafSaveSource
import moe.starmoe.box.save.SaveCollector
import moe.starmoe.box.save.ScanResult
import moe.starmoe.box.save.ShizukuSaveSource
import moe.starmoe.box.shizuku.ShizukuShell

/** Per-save upload state, keyed by [FoundSave.key]. */
sealed interface UploadState {
    data object Idle : UploadState
    data object Uploading : UploadState
    class Done(val stored: StoredSave, val changed: Boolean) : UploadState
    class Failed(val message: String) : UploadState
}

sealed interface ServerScan {
    data object NotScanned : ServerScan
    data object Scanning : ServerScan
    data object NotInstalled : ServerScan
    /** Reading through granted directories, and this build's directory has no grant. */
    data object NotGranted : ServerScan
    data object NoSave : ServerScan
    /** Saves decrypted, but no account id could be read from them: the game changed its save format. */
    data object Unreadable : ServerScan
    class Failed(val message: String) : ServerScan
    class Found(val saves: List<FoundSave>) : ServerScan
}

/** A game directory the user granted through the folder picker (Android 12 and older). */
data class GameGrant(val server: GameServer, val packageName: String, val tree: Uri)

data class UiState(
    val passportConfigured: Boolean = true,
    val user: PassportUser? = null,
    val signingIn: Boolean = false,
    val shizuku: ShizukuShell.Status = ShizukuShell.Status.NOT_RUNNING,
    val grants: List<GameGrant> = emptyList(),
    val scans: Map<GameServer, ServerScan> = GameServer.entries.associateWith { ServerScan.NotScanned },
    val importing: Boolean = false,
    val progress: String? = null,
    val uploads: Map<String, UploadState> = emptyMap(),
    val stored: List<StoredSave> = emptyList(),
    /** Saves read from a picked folder or files; the user named their server when picking. */
    val picked: List<FoundSave> = emptyList(),
    val message: String? = null,
) {
    val scanning get() = scans.values.any { it is ServerScan.Scanning }
    val busy get() = scanning || importing
    val canScan get() = shizuku == ShizukuShell.Status.READY || grants.isNotEmpty()
    val found get() = scans.values.filterIsInstance<ServerScan.Found>().flatMap { it.saves }.let { scanned ->
        scanned + picked.filterNot { p -> scanned.any { it.key == p.key } }
    }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val passport = Passport(application)
    private val api = SavesApi()

    private val _state = MutableStateFlow(UiState(passportConfigured = passport.configured))
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refreshShizuku()
        refreshGrants()
        viewModelScope.launch { loadUser() }
    }

    fun refreshShizuku() {
        _state.update { it.copy(shizuku = ShizukuShell.status()) }
    }

    fun requestShizuku() = ShizukuShell.requestPermission()

    fun dismissMessage() = _state.update { it.copy(message = null) }

    private suspend fun loadUser() {
        if (!passport.signedIn) {
            _state.update { it.copy(user = null, stored = emptyList()) }
            return
        }
        val user = passport.user()
        _state.update { it.copy(user = user) }
        refreshStored()
    }

    fun signIn(activity: Activity) {
        if (!passport.configured) {
            _state.update { it.copy(message = "这个版本没有配置 Passport 应用 ID，无法登录。") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(signingIn = true) }
            try {
                passport.signIn(activity)
                loadUser()
            } catch (e: Exception) {
                _state.update { it.copy(message = "登录没有完成：${e.message ?: e.javaClass.simpleName}") }
            } finally {
                _state.update { it.copy(signingIn = false) }
            }
        }
    }

    fun signOut(activity: Activity) {
        viewModelScope.launch {
            runCatching { passport.signOut(activity) }
            _state.update { it.copy(user = null, stored = emptyList(), uploads = emptyMap()) }
        }
    }

    fun refreshStored() {
        if (!passport.signedIn) return
        viewModelScope.launch {
            try {
                val list = api.list(passport.accessToken())
                _state.update { it.copy(stored = list) }
            } catch (e: Exception) {
                handleAuthFailure(e)
            }
        }
    }

    /** Re-reads which game directories this app holds a grant for. */
    fun refreshGrants() {
        val app = getApplication<Application>()
        val granted = SafSaveSource.grantedTrees(app).mapNotNull { uri -> SafSaveSource.serverOfTree(uri)?.let { (server, pkg) -> GameGrant(server, pkg, uri) } }
        _state.update { it.copy(grants = granted) }
    }

    /** True when there is a way to read the game's own directory: Shizuku, or a granted directory. */
    private fun canScan(s: UiState) = s.canScan

    /** Reads both builds' saves: through Shizuku when it is ready, else from the granted game directories. */
    fun scan() {
        if (_state.value.scanning) return
        refreshShizuku()
        refreshGrants()
        val start = _state.value
        if (!canScan(start)) {
            _state.update { it.copy(message = "需要先启动 Shizuku，或授权游戏目录。") }
            return
        }
        val useShizuku = start.shizuku == ShizukuShell.Status.READY
        viewModelScope.launch {
            _state.update { s -> s.copy(scans = GameServer.entries.associateWith { ServerScan.Scanning }, uploads = emptyMap()) }
            for (server in GameServer.entries) {
                val result = withContext(Dispatchers.IO) {
                    try {
                        if (useShizuku) scanShizuku(server) else scanGrants(server, start.grants)
                    } catch (e: Exception) {
                        ServerScan.Failed(e.message ?: e.javaClass.simpleName)
                    }
                }
                _state.update { it.copy(scans = it.scans + (server to result)) }
            }
            _state.update { it.copy(progress = null) }
        }
    }

    private fun progress(text: String) = _state.update { it.copy(progress = text) }

    private fun scanShizuku(server: GameServer): ServerScan =
        when (val r = ShizukuSaveSource.scan(server, ::progress)) {
            ScanResult.NotInstalled -> ServerScan.NotInstalled
            ScanResult.NoSave -> ServerScan.NoSave
            ScanResult.Unreadable -> ServerScan.Unreadable
            is ScanResult.Found -> ServerScan.Found(r.saves)
        }

    private fun scanGrants(server: GameServer, grants: List<GameGrant>): ServerScan {
        val mine = grants.filter { it.server == server }
        if (mine.isEmpty()) return ServerScan.NotGranted
        val resolver = getApplication<Application>().contentResolver
        val collector = SaveCollector(server)
        var readable = false
        for (grant in mine) {
            try {
                SafSaveSource.walkTree(resolver, grant.tree, collector, { progress("${server.label}：$it") })
                readable = true
            } catch (_: SecurityException) {
                // The grant was revoked (app reinstalled, storage reset); it is dropped on the next refresh.
            } catch (_: IllegalArgumentException) {
            }
        }
        return when {
            !readable -> ServerScan.Failed("目录授权已失效，请重新授权")
            collector.isEmpty && collector.unreadable > 0 -> ServerScan.Unreadable
            collector.isEmpty -> ServerScan.NoSave
            else -> ServerScan.Found(collector.saves())
        }
    }

    /**
     * Result of the folder picker opened on a game directory. A tree that is not the game's directory (the user
     * navigated elsewhere) is not kept as a grant, but is still read once like any picked folder.
     */
    fun onGameDirPicked(tree: Uri, expected: GameServer) {
        val app = getApplication<Application>()
        val match = SafSaveSource.serverOfTree(tree)
        if (match == null) {
            _state.update { it.copy(message = "选择的不是游戏目录，已按普通文件夹读取一次。") }
            importTree(tree, expected)
            return
        }
        runCatching { SafSaveSource.persist(app, tree) }
            .onFailure { _state.update { it.copy(message = "系统没有保留这个授权：${it.message}") } }
        refreshGrants()
        scan()
    }

    fun revokeGrant(grant: GameGrant) {
        SafSaveSource.release(getApplication(), grant.tree)
        refreshGrants()
    }

    /** Reads a folder the user picked (e.g. the account folder copied out of Android/data). */
    fun importTree(tree: Uri, server: GameServer) = importWith(server, "这个文件夹里没有能解密的存档。") { resolver, collector ->
        SafSaveSource.walkTree(resolver, tree, collector, ::progress)
    }

    /** Reads files the user picked: save files, or the PC tool's zip. */
    fun importFiles(uris: List<Uri>, server: GameServer) = importWith(server, "选择的文件里没有能解密的存档。") { resolver, collector ->
        SafSaveSource.readDocuments(resolver, uris, collector, ::progress)
    }

    private fun importWith(
        server: GameServer,
        emptyMessage: String,
        read: (android.content.ContentResolver, SaveCollector) -> Unit,
    ) {
        if (_state.value.importing) return
        viewModelScope.launch {
            _state.update { it.copy(importing = true) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val collector = SaveCollector(server)
                    read(getApplication<Application>().contentResolver, collector)
                    collector.saves() to collector.unreadable
                }
            }
            _state.update { it.copy(importing = false, progress = null) }
            result.onSuccess { (found, unreadable) ->
                if (found.isEmpty()) {
                    _state.update { it.copy(message = if (unreadable > 0) UNREADABLE_MESSAGE else emptyMessage) }
                } else {
                    _state.update { s ->
                        val kept = s.picked.filterNot { old -> found.any { it.key == old.key } }
                        s.copy(picked = kept + found, message = "找到 ${found.size} 个账号")
                    }
                }
            }.onFailure { e ->
                _state.update { it.copy(message = "读取失败：${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    fun upload(save: FoundSave) {
        if (!passport.signedIn) {
            _state.update { it.copy(message = "请先登录 StarMoe 通行证。") }
            return
        }
        val key = save.key
        if (_state.value.uploads[key] == UploadState.Uploading) return
        viewModelScope.launch {
            setUpload(key, UploadState.Uploading)
            try {
                val result = api.upload(passport.accessToken(), save.server.id, save.player)
                setUpload(key, UploadState.Done(result.stored(), result.changed))
                refreshStored()
            } catch (e: Exception) {
                setUpload(key, UploadState.Failed(describe(e)))
                handleAuthFailure(e)
            }
        }
    }

    fun uploadAll() = _state.value.found.forEach { upload(it) }

    /** Clears what was read from picked folders or files. */
    fun clearPicked() = _state.update { it.copy(picked = emptyList()) }

    fun deleteStored(save: StoredSave) {
        viewModelScope.launch {
            try {
                api.delete(passport.accessToken(), save.server, save.accountId)
                _state.update { it.copy(stored = it.stored.filterNot { s -> s.server == save.server && s.accountId == save.accountId }) }
            } catch (e: Exception) {
                _state.update { it.copy(message = "删除失败：${describe(e)}") }
                handleAuthFailure(e)
            }
        }
    }

    private fun setUpload(key: String, state: UploadState) = _state.update { it.copy(uploads = it.uploads + (key to state)) }

    private fun handleAuthFailure(e: Exception) {
        if (e is ApiException && e.status == 401) {
            passport.forget()
            _state.update { it.copy(user = null, stored = emptyList(), message = "登录已过期，请重新登录。") }
        }
    }

    private fun describe(e: Exception): String = when {
        e is ApiException -> when (e.code) {
            "too_soon" -> "上传太频繁，${e.retryAfter ?: 30} 秒后再试"
            "invalid_save" -> "服务器认不出这份存档"
            "too_large" -> "存档太大"
            "insufficient_scope" -> "账号没有上传权限（请联系管理员）"
            "invalid_token", "signed_out" -> "登录已过期"
            else -> "服务器错误（${e.status}${e.code?.let { " $it" } ?: ""}）"
        }
        e is java.net.UnknownHostException || e is java.net.ConnectException -> "连不上服务器"
        e is java.net.SocketTimeoutException -> "连接超时"
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun moe.starmoe.box.net.UploadResult.stored() = save
}

/** Shown when files decrypted to a `_player` but no account id could be read: the game changed its save format. */
private const val UNREADABLE_MESSAGE = "找到了存档，但读不出账号 ID，可能是游戏更新改了存档格式。请把情况反馈给我们。"
