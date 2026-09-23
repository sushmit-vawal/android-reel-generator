package com.reelgenerator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reelgenerator.planning.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Synthetic local fixtures; executes real codecs/GL/MediaStore on a device or emulator. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class ReelExportDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val published = mutableListOf<Uri>()
    private lateinit var red: SourceClip
    private lateinit var blue: SourceClip
    @Before fun fixtures() = runBlocking {
        red = fixture("red", Color.RED, 640, 360)
        blue = fixture("blue", Color.BLUE, 360, 640)
    }
    @After fun removeTestGalleryEntries() { published.forEach { context.contentResolver.delete(it, null, null) } }

    @Test fun singleClipSingleTextRetainsPhaseOneExport() = runBlocking {
        val uri = withTimeout(180_000) {
            ReelExporter(context).export(Uri.parse(red.uri), ReelCategory.TRAVEL, "FIRST", "test_${UUID.randomUUID()}", onPublished = { published.add(it) }) {}
        }
        inspect(uri) { reader ->
            assertOutput(reader, 9_000)
            val frame = requireNotNull(reader.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST))
            assertRed(frame); assertTrue(whitePixels(frame) > 100); frame.recycle()
        }
    }
    @Test fun multipleTextBeatsOverOneTrimmedClip() = runBlocking {
        val plan = makePlan(listOf(ReelClipSegment(red, 2_000, 8_000, 0)), listOf(TextBeat("FIRST", 0, 2_000), TextBeat("SECOND", 2_000, 6_000)))
        inspect(export(plan)) { reader ->
            assertOutput(reader, 6_000)
            val first = requireNotNull(reader.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST))
            val second = requireNotNull(reader.getFrameAtTime(3_500_000, MediaMetadataRetriever.OPTION_CLOSEST))
            assertRed(first); assertRed(second)
            assertTrue(whitePixels(first) > 100); assertTrue(whitePixels(second) > 100)
            assertNotEquals(whitePixels(first), whitePixels(second))
            first.recycle(); second.recycle()
        }
    }
    @Test fun oneCaptionContinuesAcrossDifferentSourceClips() = runBlocking {
        val plan = makePlan(listOf(ReelClipSegment(red, 1_000, 4_000, 0), ReelClipSegment(blue, 2_000, 5_000, 3_000)), listOf(TextBeat("CONTINUOUS", 0, 6_000)))
        inspect(export(plan)) { reader ->
            assertOutput(reader, 6_000)
            val first = requireNotNull(reader.getFrameAtTime(1_500_000, MediaMetadataRetriever.OPTION_CLOSEST))
            val second = requireNotNull(reader.getFrameAtTime(4_500_000, MediaMetadataRetriever.OPTION_CLOSEST))
            assertRed(first); assertBlue(second)
            assertTrue(whitePixels(first) > 100); assertTrue(whitePixels(second) > 100)
            first.recycle(); second.recycle()
        }
    }
    @Test fun textAndCutsHaveIndependentTimelines() = runBlocking {
        val plan = makePlan(listOf(ReelClipSegment(red, 1_000, 3_000, 0), ReelClipSegment(blue, 2_000, 4_000, 2_000), ReelClipSegment(red, 4_000, 6_000, 4_000)),
            listOf(TextBeat("FIRST", 0, 3_000), TextBeat("SECOND", 3_000, 6_000)))
        inspect(export(plan)) { reader ->
            assertOutput(reader, 6_000)
            val before = requireNotNull(reader.getFrameAtTime(2_500_000, MediaMetadataRetriever.OPTION_CLOSEST))
            val after = requireNotNull(reader.getFrameAtTime(3_500_000, MediaMetadataRetriever.OPTION_CLOSEST))
            val ending = requireNotNull(reader.getFrameAtTime(5_000_000, MediaMetadataRetriever.OPTION_CLOSEST))
            assertBlue(before); assertBlue(after); assertRed(ending)
            assertTrue(whitePixels(before) > 100); assertTrue(whitePixels(after) > 100)
            assertNotEquals(whitePixels(before), whitePixels(after))
            assertTrue(whitePixels(ending) > 100)
            before.recycle(); after.recycle(); ending.recycle()
        }
    }
    @Test fun cancellationLeavesNoPublishedOrTemporaryFile() = runBlocking {
        val plan = makePlan(listOf(ReelClipSegment(red, 0, 9_000, 0)), listOf(TextBeat("CANCEL", 0, 9_000)))
        val started = CompletableDeferred<Unit>()
        val job = launch { ReelExporter(context).export(plan) { if (it.startsWith("Rendering")) started.complete(Unit) } }
        withTimeout(30_000) { started.await() }
        job.cancelAndJoin()
        assertNull(ReelExporter(context).recover(plan.category, plan.id))
        assertFalse(File(context.cacheDir, "reel-${plan.id}.mp4").exists())
    }
    @Test fun fiveReelBatchStillRendersSequentially() = runBlocking {
        var active = 0
        var maximum = 0
        val result = runBatch(listOf(red.uri, blue.uri)) { slot, source ->
            active++; maximum = maxOf(maximum, active)
            val clip = if (source == red.uri) red else blue
            val uri = export(makePlan(listOf(ReelClipSegment(clip, 1_000, 7_000, 0)), listOf(TextBeat("REEL ${slot + 1}", 0, 6_000))))
            inspect(uri) { assertOutput(it, 6_000) }
            active--
            uri.toString()
        }
        assertEquals(result.errors.toString(), 5, result.outputs.size)
        assertEquals(1, maximum)
        assertEquals(5, result.outputs.map { it.uri }.distinct().size)
    }

    private fun makePlan(clips: List<ReelClipSegment>, beats: List<TextBeat>) = ReelPlan("test_${UUID.randomUUID()}", ReelCategory.TRAVEL, HumorStyle.AUTO,
        "Synthetic rendering test", "Test", beats.first().text, clips, beats, payoff = beats.last().text,
        metadata = GenerationMetadata("instrumentation", 0, 0)).validated()
    private suspend fun export(plan: ReelPlan): Uri = withTimeout(180_000) {
        ReelExporter(context).export(plan, onPublished = { published.add(it) }) {}.also { uri ->
            val directory = File(context.getExternalFilesDir(null), "render-tests").apply { mkdirs() }
            context.contentResolver.openInputStream(uri)!!.use { input -> File(directory, "${plan.id}.mp4").outputStream().use { input.copyTo(it) } }
        }
    }
    private fun inspect(uri: Uri, action: (MediaMetadataRetriever) -> Unit) {
        MediaMetadataRetriever().use { it.setDataSource(context, uri); action(it) }
    }
    private fun assertOutput(reader: MediaMetadataRetriever, duration: Long) {
        val width = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
        val height = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
        val rotation = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
        assertEquals(1080, if (rotation % 180 == 0) width else height)
        assertEquals(1920, if (rotation % 180 == 0) height else width)
        assertTrue(kotlin.math.abs(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() - duration) < 250)
        assertNotEquals("yes", reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
    }
    private fun assertRed(bitmap: Bitmap) { val pixel = bitmap.getPixel(100, 100); assertTrue(Color.red(pixel) > 180 && Color.blue(pixel) < 80) }
    private fun assertBlue(bitmap: Bitmap) { val pixel = bitmap.getPixel(100, 100); assertTrue(Color.blue(pixel) > 180 && Color.red(pixel) < 80) }
    private fun whitePixels(bitmap: Bitmap): Int {
        var count = 0
        for (y in 700 until minOf(1220, bitmap.height) step 2) for (x in 180 until minOf(900, bitmap.width) step 2) {
            val color = bitmap.getPixel(x, y)
            if (Color.red(color) > 220 && Color.green(color) > 220 && Color.blue(color) > 220) count++
        }
        return count
    }
    private suspend fun fixture(name: String, color: Int, width: Int, height: Int): SourceClip {
        val video = File(context.cacheDir, "fixture-$name.mp4")
        if (!video.exists()) {
            val png = File(context.cacheDir, "fixture-$name.png")
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            png.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            withContext(Dispatchers.Main) {
                val transformer = Transformer.Builder(context).setVideoMimeType(MimeTypes.VIDEO_H264).build()
                try {
                    withTimeout(60_000) {
                        suspendCancellableCoroutine<Unit> { continuation ->
                            transformer.addListener(object : Transformer.Listener {
                                override fun onCompleted(composition: Composition, exportResult: ExportResult) { if (continuation.isActive) continuation.resume(Unit) }
                                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) { if (continuation.isActive) continuation.resumeWithException(exportException) }
                            })
                            transformer.start(EditedMediaItem.Builder(MediaItem.Builder().setUri(Uri.fromFile(png)).setImageDurationMs(9_000).build()).setFrameRate(30).setRemoveAudio(true).build(), video.absolutePath)
                        }
                    }
                } catch (error: Exception) { video.delete(); throw error }
                finally { transformer.cancel(); png.delete() }
            }
        }
        return SourceClipReader(context).read(Uri.fromFile(video).toString(), name)
    }
}
