package com.example.stemflow.model

data class AudioMetadata(
    val uriString: String,
    val fileName: String,
    val mimeType: String,
    val sampleRate: Int,
    val channels: Int,
    val durationMs: Long,
    val sizeBytes: Long
) {
    val isStereo: Boolean get() = channels >= 2
    val formattedDuration: String get() {
        val totalSec = durationMs / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return "%d:%02d".format(min, sec)
    }
}
