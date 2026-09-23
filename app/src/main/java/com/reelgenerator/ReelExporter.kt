package com.reelgenerator

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.*
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID
import com.reelgenerator.planning.*
import com.reelgenerator.rendering.ReelCompositionFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@UnstableApi
class ReelExporter(private val context: Context) {
    suspend fun recover(category: ReelCategory, id: String): Uri? = withContext(Dispatchers.IO) { findPublished(category, id) }
    suspend fun export(
        source: Uri,
        category: ReelCategory,
        caption: String = category.caption,
        id: String = UUID.randomUUID().toString(),
        onPublished: suspend (Uri) -> Unit = {},
        status: suspend (String) -> Unit
    ): Uri {
        val clip = SourceClipReader(context).read(source.toString())
        return export(LocalReelPlanner.legacy(id, category, clip, caption), onPublished, status)
    }

    suspend fun export(plan: ReelPlan, onPublished: suspend (Uri) -> Unit = {}, status: suspend (String) -> Unit): Uri {
        plan.validated()
        val id = plan.id
        val category = plan.category
        // Stable per-reel names recover a published output after process death without duplicating it.
        val recovered = withContext(Dispatchers.IO) { findPublished(category, id) }
        if (recovered != null) {
            withContext(NonCancellable) { onPublished(recovered) }
            return recovered
        }
        val temporary = File(context.cacheDir, "reel-$id.mp4")
        withContext(Dispatchers.IO) { if (temporary.exists()) check(temporary.delete()) { "Could not clear interrupted export." } }
        try {
            status("Preparing reel timeline…")
            withContext(Dispatchers.IO) {
                check(StatFs(context.cacheDir.path).availableBytes > 300L * 1024 * 1024) {
                    "Free at least 300 MB of storage and try again."
                }
            }
            val composition = ReelCompositionFactory.create(plan)
            withContext(Dispatchers.Main.immediate) {
                coroutineScope {
                    val transformer = Transformer.Builder(context)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setEncoderFactory(DefaultEncoderFactory.Builder(context).setEnableFallback(false).build())
                        .build()
                    val poller = launch {
                        val progress = ProgressHolder()
                        while (isActive) {
                            status(if (transformer.getProgress(progress) == Transformer.PROGRESS_STATE_AVAILABLE)
                                "Rendering video… ${progress.progress}%" else "Rendering video…")
                            delay(400)
                        }
                    }
                    try {
                        suspendCancellableCoroutine { continuation ->
                            transformer.addListener(object : Transformer.Listener {
                                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                    if (continuation.isActive) continuation.resume(Unit)
                                }
                                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                    if (continuation.isActive) continuation.resumeWithException(exportException)
                                }
                            })
                            continuation.invokeOnCancellation { Handler(Looper.getMainLooper()).post { transformer.cancel() } }
                            try { transformer.start(composition, temporary.absolutePath) }
                            catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
                        }
                    } finally { poller.cancel(); transformer.cancel() }
                }
            }
            currentCoroutineContext().ensureActive()
            status("Saving to Upload Reels…")
            // Once publishing begins, finish the short transaction even if the screen closes.
            return withContext(NonCancellable + Dispatchers.IO) {
                publish(temporary, category, id).also { onPublished(it) }
            }
        } finally {
            temporary.delete()

        }
    }

    private fun findPublished(category: ReelCategory, id: String): Uri? {
        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        resolver.query(collection, arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.IS_PENDING, MediaStore.Video.Media.SIZE),
            "${MediaStore.Video.Media.DISPLAY_NAME}=? AND ${MediaStore.Video.Media.RELATIVE_PATH}=?",
            arrayOf(ExportPolicy.fileName(category, id), ExportPolicy.RELATIVE_PATH), null)?.use {
            while (it.moveToNext()) {
                val uri = android.content.ContentUris.withAppendedId(collection, it.getLong(0))
                if (it.getInt(1) == 0 && it.getLong(2) > 0) return uri
                resolver.delete(uri, null, null)
            }
        }
        return null
    }

    private fun publish(file: File, category: ReelCategory, id: String): Uri {
        check(file.length() > 0) { "The export produced an empty file." }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, ExportPolicy.fileName(category, id))
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, ExportPolicy.RELATIVE_PATH)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)) {
            "Could not create the Gallery entry."
        }
        try {
            checkNotNull(resolver.openOutputStream(uri, "w")).use { output -> file.inputStream().use { it.copyTo(output) } }
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null) == 1)
            resolver.query(uri, arrayOf(MediaStore.Video.Media.SIZE), null, null, null)?.use {
                check(it.moveToFirst() && it.getLong(0) > 0) { "The saved video could not be verified." }
            } ?: error("The saved video could not be verified.")
            return uri
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

}
