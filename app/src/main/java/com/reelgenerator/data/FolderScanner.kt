package com.reelgenerator.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.*

/** Inventories document-provider metadata; the visual index performs cached frame analysis separately. */
class FolderScanner(private val context: Context, private val dao: ReelDao) {
    suspend fun scanAll(progress: suspend (String) -> Unit = {}) = withContext(Dispatchers.IO) {
        dao.folders().filter { it.enabled }.forEach { folder ->
            currentCoroutineContext().ensureActive()
            progress("Scanning ${folder.name}…")
            try {
                val tree = Uri.parse(folder.uri)
                require(context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }) {
                    "Folder access was revoked. Remove and select this folder again."
                }
                val queue = ArrayDeque<String>()
                queue.add(DocumentsContract.getTreeDocumentId(tree))
                val visited = mutableSetOf<String>()
                val videos = linkedMapOf<String, SourceVideo>()
                while (queue.isNotEmpty()) {
                    currentCoroutineContext().ensureActive()
                    val directory = queue.removeFirst()
                    if (!visited.add(directory)) continue
                    check(visited.size <= 10_000 && videos.size <= 50_000) { "Folder is too large. Select a smaller subfolder." }
                    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, directory)
                    val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_SIZE)
                    val cursor = context.contentResolver.query(children, columns, null, null, null)
                        ?: error("Cannot read this folder. Select it again.")
                    cursor.use {
                        check(!it.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)) { "Folder is still loading. Download videos locally, then rescan." }
                        while (it.moveToNext()) {
                            currentCoroutineContext().ensureActive()
                            val id = it.getString(0)
                            val mime = it.getString(2).orEmpty()
                            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) queue.add(id)
                            else if (mime.startsWith("video/")) {
                                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString()
                                val key = DocumentsContract.buildDocumentUri(tree.authority, id).toString()
                                videos[uri] = SourceVideo(uri, it.getString(1) ?: "Video", it.getLong(3), it.getLong(4), documentKey = key)
                            }
                        }
                    }
                }
                dao.replaceScan(folder.uri, videos.values.toList())
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                dao.scanError(folder.uri, error.localizedMessage ?: "Could not scan folder.", System.currentTimeMillis())
            }
        }
    }
}
