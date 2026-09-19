package com.example.stemflow.audio

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri

data class TrackAudioMetadata(
    val mimeType: String,
    val sampleRate: Int,
    val channelCount: Int,
    val durationUs: Long,
    val trackIndex: Int
) {
    val durationSeconds: Float get() = durationUs / 1_000_000f
}

interface AudioTrackFormat {
    fun getString(key: String): String?
    fun getInteger(key: String): Int?
    fun getLong(key: String): Long?
    fun containsKey(key: String): Boolean
}

class SystemAudioTrackFormat(private val format: MediaFormat) : AudioTrackFormat {
    override fun getString(key: String): String? = if (format.containsKey(key)) format.getString(key) else null
    override fun getInteger(key: String): Int? = if (format.containsKey(key)) format.getInteger(key) else null
    override fun getLong(key: String): Long? = if (format.containsKey(key)) format.getLong(key) else null
    override fun containsKey(key: String): Boolean = format.containsKey(key)
}

interface TrackFormatProvider {
    fun getTrackCount(): Int
    fun getTrackFormat(index: Int): AudioTrackFormat
}

class AudioInspector {
    fun inspect(context: Context, uri: Uri): TrackAudioMetadata {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            return inspectExtractor(extractor)
        } finally {
            extractor.release()
        }
    }

    fun inspectExtractor(extractor: MediaExtractor): TrackAudioMetadata {
        return inspectFormatProvider(object : TrackFormatProvider {
            override fun getTrackCount(): Int = extractor.trackCount
            override fun getTrackFormat(index: Int): AudioTrackFormat =
                SystemAudioTrackFormat(extractor.getTrackFormat(index))
        })
    }

    fun inspectFormatProvider(provider: TrackFormatProvider): TrackAudioMetadata {
        val trackCount = provider.getTrackCount()
        require(trackCount > 0) { "No tracks found in audio file" }

        var audioTrackIndex = -1
        var audioFormat: AudioTrackFormat? = null

        for (i in 0 until trackCount) {
            val format = provider.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                audioTrackIndex = i
                audioFormat = format
                break
            }
        }

        val format = requireNotNull(audioFormat) { "No valid audio track found" }
        val mime = format.getString(MediaFormat.KEY_MIME)
            ?: throw IllegalArgumentException("Missing MIME type")
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            ?: throw IllegalArgumentException("Missing sample rate")
        val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            ?: throw IllegalArgumentException("Missing channel count")
        val durationUs = format.getLong(MediaFormat.KEY_DURATION) ?: 0L

        require(sampleRate in 8000..192000) { "Unsupported sample rate: $sampleRate" }
        require(channelCount in 1..8) { "Unsupported channel count: $channelCount" }

        return TrackAudioMetadata(
            mimeType = mime,
            sampleRate = sampleRate,
            channelCount = channelCount,
            durationUs = durationUs,
            trackIndex = audioTrackIndex
        )
    }
}
