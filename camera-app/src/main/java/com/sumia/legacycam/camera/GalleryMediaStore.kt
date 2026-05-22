package com.sumia.legacycam.camera

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.Base64
import com.sumia.legacycam.core.GalleryFolderPayload
import com.sumia.legacycam.core.GalleryItemPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.max

object GalleryMediaStore {
    private const val ThumbnailSize = 220
    private const val PreviewMaxDimension = 1600
    private const val ChunkSizeBytes = 96 * 1024
    private const val FolderScanLimit = 500
    private const val FolderItemLimit = 120
    private const val EmptyFolderLabel = "Tanpa Folder"

    private data class MediaEntry(
        val mediaId: String,
        val mediaType: String,
        val title: String,
        val mimeType: String,
        val bucketName: String?,
        val sizeBytes: Long,
        val durationMs: Long?,
        val takenAtMs: Long,
        val uri: Uri,
    )

    suspend fun loadFolders(context: Context): List<GalleryFolderPayload> = withContext(Dispatchers.IO) {
        val grouped = queryMedia(context, limit = FolderScanLimit)
            .groupBy { normalizeFolderName(it.bucketName) }

        grouped.entries
            .map { (folderName, entries) ->
                val newestEntry = entries.maxByOrNull { it.takenAtMs }
                GalleryFolderPayload(
                    folderName = folderName,
                    itemCount = entries.size,
                    coverThumbnailDataUrl = newestEntry?.let { loadThumbnailDataUrl(context, it) },
                    latestTakenAtMs = newestEntry?.takenAtMs,
                )
            }
            .sortedWith(
                compareByDescending<GalleryFolderPayload> { it.latestTakenAtMs ?: 0L }
                    .thenBy { it.folderName.lowercase() },
            )
    }

    suspend fun loadMediaByFolder(context: Context, folderName: String): List<GalleryItemPayload> = withContext(Dispatchers.IO) {
        queryMedia(context, limit = FolderScanLimit, folderName = folderName).map { entry ->
            GalleryItemPayload(
                mediaId = entry.mediaId,
                mediaType = entry.mediaType,
                title = entry.title,
                mimeType = entry.mimeType,
                bucketName = entry.bucketName,
                sizeBytes = entry.sizeBytes,
                durationMs = entry.durationMs,
                takenAtMs = entry.takenAtMs,
                thumbnailDataUrl = loadThumbnailDataUrl(context, entry),
            )
        }
    }

    suspend fun streamMedia(
        context: Context,
        mediaId: String,
        requestId: String,
        onMeta: (GalleryItemPayload, Int) -> Unit,
        onChunk: (Int, Int, String) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val entry = findMediaById(context, mediaId)
        if (entry == null) {
            onError("Media perangkat tidak ditemukan.")
            return@withContext
        }

        if (entry.mediaType == "image") {
            val bytes = loadScaledImageBytes(context, entry.uri)
            if (bytes == null || bytes.isEmpty()) {
                onError("Gagal memuat gambar dari perangkat.")
                return@withContext
            }

            val chunkCount = max(1, ceil(bytes.size / ChunkSizeBytes.toDouble()).toInt())
            onMeta(
                GalleryItemPayload(
                    mediaId = entry.mediaId,
                    mediaType = "image",
                    title = entry.title,
                    mimeType = "image/jpeg",
                    bucketName = entry.bucketName,
                    sizeBytes = bytes.size.toLong(),
                    durationMs = entry.durationMs,
                    takenAtMs = entry.takenAtMs,
                ),
                chunkCount,
            )
            bytes.asSequenceChunks(ChunkSizeBytes).forEachIndexed { index, chunk ->
                onChunk(index, chunkCount, Base64.encodeToString(chunk, Base64.NO_WRAP))
            }
            onComplete()
            return@withContext
        }

        val sizeBytes = entry.sizeBytes.takeIf { it > 0 }
        val bytes = context.contentResolver.openInputStream(entry.uri)?.use { input ->
            input.readBytes()
        }

        if (bytes == null || bytes.isEmpty()) {
            onError("Gagal membaca video dari perangkat.")
            return@withContext
        }

        val videoBytes = bytes
        val chunkCount = max(1, ceil(videoBytes.size / ChunkSizeBytes.toDouble()).toInt())
        onMeta(
            GalleryItemPayload(
                mediaId = entry.mediaId,
                mediaType = "video",
                title = entry.title,
                mimeType = entry.mimeType.ifBlank { "video/mp4" },
                bucketName = entry.bucketName,
                sizeBytes = sizeBytes ?: videoBytes.size.toLong(),
                durationMs = entry.durationMs,
                takenAtMs = entry.takenAtMs,
            ),
            chunkCount,
        )
        videoBytes.asSequenceChunks(ChunkSizeBytes).forEachIndexed { index, chunk ->
            onChunk(index, chunkCount, Base64.encodeToString(chunk, Base64.NO_WRAP))
        }
        onComplete()
    }

    private fun queryMedia(context: Context, limit: Int, folderName: String? = null): List<MediaEntry> {
        val uri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Video.VideoColumns.DURATION,
        )
        val selectionParts = mutableListOf("${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)")
        val selectionArgs = mutableListOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
        )
        folderName?.takeIf { it.isNotBlank() }?.let { normalized ->
            if (normalized == EmptyFolderLabel) {
                selectionParts += "(${MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME} IS NULL OR ${MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME} = '')"
            } else {
                selectionParts += "${MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME} = ?"
                selectionArgs += normalized
            }
        }
        val sortOrder = "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"

        val items = mutableListOf<MediaEntry>()
        context.contentResolver.query(
            uri,
            projection,
            selectionParts.joinToString(" AND "),
            selectionArgs.toTypedArray(),
            sortOrder,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val typeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val bucketColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val takenAtColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
            val durationColumn = cursor.getColumnIndex(MediaStore.Video.VideoColumns.DURATION)

            while (cursor.moveToNext() && items.size < limit) {
                val rawType = cursor.getInt(typeColumn)
                val mediaType = when (rawType) {
                    MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> "image"
                    MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> "video"
                    else -> continue
                }
                val id = cursor.getLong(idColumn)
                val title = cursor.getString(nameColumn)?.takeIf { it.isNotBlank() } ?: "Media $id"
                val mimeType = cursor.getString(mimeColumn)?.takeIf { it.isNotBlank() }
                    ?: if (mediaType == "image") "image/jpeg" else "video/mp4"
                val bucketName = normalizeFolderName(cursor.getString(bucketColumn))
                val sizeBytes = cursor.getLong(sizeColumn)
                val takenAtMs = cursor.getLong(takenAtColumn) * 1000L
                val durationMs = durationColumn.takeIf { it >= 0 && !cursor.isNull(it) }?.let { cursor.getLong(it) }
                val contentUri = buildMediaUri(id, mediaType)

                items += MediaEntry(
                    mediaId = id.toString(),
                    mediaType = mediaType,
                    title = title,
                    mimeType = mimeType,
                    bucketName = bucketName,
                    sizeBytes = sizeBytes,
                    durationMs = durationMs,
                    takenAtMs = takenAtMs,
                    uri = contentUri,
                )
            }
        }
        return items
    }

    private fun findMediaById(context: Context, mediaId: String): MediaEntry? {
        return queryMedia(context, limit = FolderScanLimit).firstOrNull { it.mediaId == mediaId }
    }

    private fun normalizeFolderName(folderName: String?): String {
        return folderName?.trim()?.takeIf { it.isNotBlank() } ?: EmptyFolderLabel
    }

    private fun buildMediaUri(id: Long, mediaType: String): Uri {
        val baseUri = if (mediaType == "video") {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        return ContentUris.withAppendedId(baseUri, id)
    }

    private fun loadThumbnailDataUrl(context: Context, entry: MediaEntry): String? {
        val bitmap = if (entry.mediaType == "video") {
            loadVideoFrame(context, entry.uri)
        } else {
            loadImageBitmap(context, entry.uri, ThumbnailSize)
        } ?: return null

        return bitmap.useAsJpegDataUrl(quality = 72)
    }

    private fun loadScaledImageBytes(context: Context, uri: Uri): ByteArray? {
        val bitmap = loadImageBitmap(context, uri, PreviewMaxDimension) ?: return null
        return bitmap.useAsJpegBytes(quality = 86).also {
            bitmap.recycle()
        }
    }

    private fun loadImageBitmap(context: Context, uri: Uri, maxDimension: Int): Bitmap? {
        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, boundsOptions)
        }

        val sourceWidth = boundsOptions.outWidth.takeIf { it > 0 } ?: return null
        val sourceHeight = boundsOptions.outHeight.takeIf { it > 0 } ?: return null
        val largestEdge = max(sourceWidth, sourceHeight).coerceAtLeast(1)
        val sampleSize = max(1, Integer.highestOneBit(largestEdge / maxDimension).coerceAtLeast(1))

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        }

        return context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, decodeOptions)
        }
    }

    private fun loadVideoFrame(context: Context, uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return runCatching {
            retriever.setDataSource(context, uri)
            retriever.frameAtTime ?: retriever.getFrameAtTime(0L)
        }.getOrNull().also {
            retriever.release()
        }
    }

    private fun Bitmap.useAsJpegDataUrl(quality: Int): String {
        val bytes = useAsJpegBytes(quality)
        recycle()
        return "data:image/jpeg;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
    }

    private fun Bitmap.useAsJpegBytes(quality: Int): ByteArray {
        val output = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, quality, output)
        return output.toByteArray()
    }

    private fun ByteArray.asSequenceChunks(chunkSize: Int): Sequence<ByteArray> = sequence {
        var offset = 0
        while (offset < size) {
            val nextOffset = (offset + chunkSize).coerceAtMost(size)
            yield(copyOfRange(offset, nextOffset))
            offset = nextOffset
        }
    }
}
