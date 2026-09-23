package com.reelgenerator.planning

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.reelgenerator.data.SourceVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Metadata only, deliberately separate from the future frame/semantic ClipAnalyzer. */
class SourceClipReader(private val context: Context) {
    suspend fun read(video: SourceVideo): SourceClip = read(video.uri, video.documentKey)
    suspend fun read(uri: String, id: String = uri): SourceClip = withContext(Dispatchers.IO) {
        MediaMetadataRetriever().use { reader ->
            reader.setDataSource(context, Uri.parse(uri))
            require(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes") { "Source does not contain a readable video." }
            val duration = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            val width = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            require(duration >= 1_000 && width > 0 && height > 0) { "Choose a readable video at least one second long." }
            SourceClip(id, uri, duration, width, height)
        }
    }
}
