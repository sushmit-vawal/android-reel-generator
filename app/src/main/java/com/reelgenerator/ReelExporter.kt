package com.reelgenerator

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.StatFs
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.*
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@UnstableApi
class ReelExporter(private val context: Context) {
    suspend fun export(source: Uri, category: ReelCategory, status: (String) -> Unit): Uri {
        val id = UUID.randomUUID().toString()
        val temporary = File(context.cacheDir, "reel-$id.mp4")
        var overlay: Bitmap? = null
        try {
            status("Reading video…")
            val duration = withContext(Dispatchers.IO) {
                check(StatFs(context.cacheDir.path).availableBytes > 300L * 1024 * 1024) {
                    "Free at least 300 MB of storage and try again."
                }
                MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(context, source)
                    require(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes") {
                        "The selected file does not contain a readable video."
                    }
                    ExportPolicy.duration(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0)
                }
            }
            overlay = captionBitmap(category.caption)
            val item = EditedMediaItem.Builder(
                MediaItem.Builder().setUri(source).setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder().setEndPositionMs(duration).build()
                ).build()
            ).setRemoveAudio(true).setFrameRate(30).setEffects(
                Effects(emptyList(), listOf(
                    Presentation.createForWidthAndHeight(ExportPolicy.WIDTH, ExportPolicy.HEIGHT, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP),
                    OverlayEffect(listOf(BitmapOverlay.createStaticBitmapOverlay(overlay)))
                ))
            ).build()
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
                            continuation.invokeOnCancellation { transformer.cancel() }
                            try { transformer.start(item, temporary.absolutePath) }
                            catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
                        }
                    } finally { poller.cancel(); transformer.cancel() }
                }
            }
            currentCoroutineContext().ensureActive()
            status("Saving to Upload Reels…")
            // Once publishing begins, finish the short transaction even if the screen closes.
            return withContext(NonCancellable + Dispatchers.IO) { publish(temporary, category, id) }
        } finally {
            temporary.delete()
            overlay?.recycle()
        }
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

    private fun captionBitmap(text: String): Bitmap {
        val bitmap = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 64f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setShadowLayer(5f, 0f, 3f, Color.BLACK)
        }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, 840)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(12f, 1f).setIncludePad(false).build()
        val top = (1920 - layout.height) / 2f
        canvas.drawRoundRect(88f, top - 32f, 992f, top + layout.height + 32f, 24f, 24f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(175, 12, 15, 22) })
        canvas.save()
        canvas.translate(120f, top)
        layout.draw(canvas)
        canvas.restore()
        return bitmap
    }
}
