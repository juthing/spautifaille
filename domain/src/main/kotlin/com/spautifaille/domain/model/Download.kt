package com.spautifaille.domain.model

enum class DownloadState { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED }

data class Download(
    val track: Track,
    val state: DownloadState,
    /** 0f..1f, ou null si inconnue. */
    val progress: Float?,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val filePath: String?,
    val error: String?,
    val createdAt: Long,
)

data class StorageUsage(
    val downloadsBytes: Long,
    val cacheBytes: Long,
    val downloadCount: Int,
)
