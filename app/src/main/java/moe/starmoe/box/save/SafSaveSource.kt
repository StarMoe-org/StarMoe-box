package moe.starmoe.box.save

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document

/**
 * Reads saves through the Storage Access Framework, with no special permission:
 *
 * - **Granted game directory** (Android 8–12): the picker is opened right on `Android/data/<package>`, and the
 *   user confirms "use this folder". The grant is kept, so later reads are one tap. Android 11 and 12 refuse
 *   `Android/data` as a picker root but still accept a pick that starts inside a package directory; Android 13
 *   closed that too, so there it is not offered.
 * - **Any folder or files the user picks** (every version): e.g. the account directory copied out with the
 *   phone's own file manager or a PC. The walk enters the folder and its subfolders (skipping the game's asset
 *   caches) and offers every small file.
 */
object SafSaveSource {
    private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

    /** Android 13 blocks picking anything under `Android/data`; before it the direct pick works. */
    val canGrantGameDir: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU

    /** `primary:Android/data/<package>` on the device's shared storage. */
    private fun gameDocId(packageName: String) = "primary:Android/data/$packageName"

    /** Where to open the folder picker so a single tap grants the game's directory. */
    fun gameDirInitialUri(packageName: String): Uri =
        DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE, gameDocId(packageName))

    /** Whether a picked tree is the game directory of [packageName] (or inside it). */
    fun isGameTree(tree: Uri, packageName: String): Boolean {
        if (tree.authority != EXTERNAL_STORAGE) return false
        val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return false
        val want = gameDocId(packageName)
        return id == want || id.startsWith("$want/")
    }

    /** Which build a picked tree belongs to, from its path; null when it is not a game directory. */
    fun serverOfTree(tree: Uri): Pair<GameServer, String>? {
        for (server in GameServer.entries) for (pkg in server.packageNames) {
            if (isGameTree(tree, pkg)) return server to pkg
        }
        return null
    }

    fun persist(context: Context, tree: Uri) {
        context.contentResolver.takePersistableUriPermission(
            tree, Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }

    fun release(context: Context, tree: Uri) {
        runCatching { context.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    /** Granted trees that still hold a read permission. */
    fun grantedTrees(context: Context): List<Uri> =
        context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri }

    /**
     * Walks a picked tree and offers every small file. Directories named like the game's asset caches are
     * skipped; inside a game directory only hex-named account directories are entered.
     */
    fun walkTree(
        resolver: ContentResolver,
        tree: Uri,
        collector: SaveCollector,
        progress: (String) -> Unit,
        maxDepth: Int = 4,
        maxFiles: Int = 400,
    ) {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val inGameDir = rootId.startsWith("primary:Android/data/")
        var files = 0
        fun walk(docId: String, depth: Int) {
            if (files >= maxFiles) return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val columns = arrayOf(
                Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
                Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
            )
            val rows = ArrayList<Child>()
            resolver.query(children, columns, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    rows += Child(
                        id = c.getString(0),
                        name = c.getString(1) ?: "",
                        isDir = c.getString(2) == Document.MIME_TYPE_DIR,
                        size = if (c.isNull(3)) -1 else c.getLong(3),
                        modified = if (c.isNull(4)) 0 else c.getLong(4),
                    )
                }
            }
            for (child in rows) {
                if (files >= maxFiles) return
                if (child.isDir) {
                    if (depth >= maxDepth || child.name in SaveCollector.SKIP_DIRS) continue
                    // In the game's own tree the only place with saves is files/<hex>; elsewhere (a copied
                    // folder) any directory may hold them.
                    if (inGameDir && child.name != "files" && !SaveCollector.isAccountDir(child.name)) continue
                    walk(child.id, depth + 1)
                } else {
                    if (child.size == 0L || child.size > SaveCollector.MAX_FILE) continue
                    files++
                    progress("读取 ${child.name.take(16)}")
                    val bytes = readDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, child.id)) ?: continue
                    FileSaveSource.offer(collector, bytes, child.name.take(16), child.modified)
                }
            }
        }
        walk(rootId, 0)
    }

    /** Reads files the user picked one by one (multi-select). */
    fun readDocuments(resolver: ContentResolver, uris: List<Uri>, collector: SaveCollector, progress: (String) -> Unit) {
        for (uri in uris) {
            val name = displayName(resolver, uri) ?: uri.lastPathSegment ?: "file"
            progress("读取 ${name.take(16)}")
            val bytes = readDocument(resolver, uri, limit = 64L shl 20) ?: continue
            FileSaveSource.offer(collector, bytes, name.take(16), 0)
        }
    }

    private class Child(val id: String, val name: String, val isDir: Boolean, val size: Long, val modified: Long)

    private fun readDocument(resolver: ContentResolver, uri: Uri, limit: Long = SaveCollector.MAX_FILE): ByteArray? =
        runCatching { resolver.openInputStream(uri)?.use { FileSaveSource.readLimited(it, limit) } }.getOrNull()

    private fun displayName(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}
