package com.sumia.legacycam.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ConnectedDevice(
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_label") val deviceLabel: String,
)

@Serializable
data class GalleryItemPayload(
    @SerialName("media_id") val mediaId: String,
    @SerialName("media_type") val mediaType: String,
    val title: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
    @SerialName("bucket_name") val bucketName: String? = null,
    @SerialName("size_bytes") val sizeBytes: Long? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("taken_at_ms") val takenAtMs: Long? = null,
    @SerialName("thumbnail_data_url") val thumbnailDataUrl: String? = null,
)

@Serializable
data class GalleryFolderPayload(
    @SerialName("folder_name") val folderName: String,
    @SerialName("item_count") val itemCount: Int,
    @SerialName("cover_thumbnail_data_url") val coverThumbnailDataUrl: String? = null,
    @SerialName("latest_taken_at_ms") val latestTakenAtMs: Long? = null,
)

@Serializable
data class SignalingMessage(
    val type: String,
    val token: String? = null,
    val role: String? = null,
    @SerialName("viewer_auth") val viewerAuth: String? = null,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("device_label") val deviceLabel: String? = null,
    @SerialName("target_device_id") val targetDeviceId: String? = null,
    val sdp: String? = null,
    @SerialName("sdp_type") val sdpType: String? = null,
    val candidate: String? = null,
    @SerialName("sdp_mid") val sdpMid: String? = null,
    @SerialName("sdp_mline_index") val sdpMLineIndex: Int? = null,
    @SerialName("request_id") val requestId: String? = null,
    @SerialName("media_id") val mediaId: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
    @SerialName("folder_name") val folderName: String? = null,
    val title: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
    @SerialName("size_bytes") val sizeBytes: Long? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("chunk_index") val chunkIndex: Int? = null,
    @SerialName("chunk_count") val chunkCount: Int? = null,
    @SerialName("batch_index") val batchIndex: Int? = null,
    @SerialName("batch_count") val batchCount: Int? = null,
    @SerialName("payload_base64") val payloadBase64: String? = null,
    val reason: String? = null,
    val devices: List<ConnectedDevice> = emptyList(),
    @SerialName("gallery_folders") val galleryFolders: List<GalleryFolderPayload> = emptyList(),
    @SerialName("gallery_items") val galleryItems: List<GalleryItemPayload> = emptyList(),
    @SerialName("gallery_item") val galleryItem: GalleryItemPayload? = null,
)
