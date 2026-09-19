package com.example.stemflow.audio

import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AudioInspectorTest {

    private class MapAudioTrackFormat(private val map: Map<String, Any>) : AudioTrackFormat {
        override fun getString(key: String): String? = map[key] as? String
        override fun getInteger(key: String): Int? = map[key] as? Int
        override fun getLong(key: String): Long? = map[key] as? Long
        override fun containsKey(key: String): Boolean = map.containsKey(key)
    }

    private class StubTrackFormatProvider(private val formats: List<AudioTrackFormat>) : TrackFormatProvider {
        override fun getTrackCount(): Int = formats.size
        override fun getTrackFormat(index: Int): AudioTrackFormat = formats[index]
    }

    @Test
    fun testValidAudioFormatParsing() {
        val format = MapAudioTrackFormat(
            mapOf(
                MediaFormat.KEY_MIME to "audio/mp3",
                MediaFormat.KEY_SAMPLE_RATE to 44100,
                MediaFormat.KEY_CHANNEL_COUNT to 2,
                MediaFormat.KEY_DURATION to 10_000_000L
            )
        )
        val provider = StubTrackFormatProvider(listOf(format))
        val inspector = AudioInspector()
        val metadata = inspector.inspectFormatProvider(provider)

        assertEquals("audio/mp3", metadata.mimeType)
        assertEquals(44100, metadata.sampleRate)
        assertEquals(2, metadata.channelCount)
        assertEquals(10_000_000L, metadata.durationUs)
        assertEquals(10.0f, metadata.durationSeconds, 0.001f)
    }

    @Test
    fun testMissingAudioTrackThrows() {
        val format = MapAudioTrackFormat(
            mapOf(
                MediaFormat.KEY_MIME to "video/mp4"
            )
        )
        val provider = StubTrackFormatProvider(listOf(format))
        val inspector = AudioInspector()

        assertThrows(IllegalArgumentException::class.java) {
            inspector.inspectFormatProvider(provider)
        }
    }

    @Test
    fun testUnsupportedSampleRateThrows() {
        val format = MapAudioTrackFormat(
            mapOf(
                MediaFormat.KEY_MIME to "audio/wav",
                MediaFormat.KEY_SAMPLE_RATE to 100,
                MediaFormat.KEY_CHANNEL_COUNT to 1
            )
        )
        val provider = StubTrackFormatProvider(listOf(format))
        val inspector = AudioInspector()

        assertThrows(IllegalArgumentException::class.java) {
            inspector.inspectFormatProvider(provider)
        }
    }
}
