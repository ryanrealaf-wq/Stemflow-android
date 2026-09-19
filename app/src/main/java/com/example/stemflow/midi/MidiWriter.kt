package com.example.stemflow.midi

import com.example.stemflow.model.MidiNote
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Standard MIDI File (SMF Format 1) Writer.
 * Generates standards-compliant .mid binary files compatible with external DAWs
 * (Ableton, FL Studio, Logic, Reaper, GarageBand).
 */
object MidiWriter {

    const val DEFAULT_PPQ = 480
    const val DEFAULT_BPM = 120.0

    /**
     * Internal raw MIDI event with absolute tick timing.
     */
    private data class TimedEvent(
        val tick: Long,
        val status: Int,
        val data1: Int,
        val data2: Int = 0,
        val isMeta: Boolean = false,
        val metaData: ByteArray? = null
    )

    /**
     * Converts notes list to a standard Format 1 MIDI file written directly to [outputFile].
     * Preserves exact millisecond timing without destructive quantization.
     */
    fun writeMidiFile(
        notes: List<MidiNote>,
        outputFile: File,
        trackName: String = "StemFlow Track",
        bpm: Double = DEFAULT_BPM,
        ppq: Int = DEFAULT_PPQ,
        channel: Int = 0
    ) {
        val msPerQuarter = (60000.0 / bpm)
        val ticksPerMs = ppq / msPerQuarter

        val events = mutableListOf<TimedEvent>()

        // Convert note onsets and offsets to absolute ticks
        for (note in notes) {
            val noteChannel = if (note.channel >= 0) note.channel else channel
            val startTick = (note.startMs * ticksPerMs).toLong().coerceAtLeast(0L)
            val durationTicks = (note.durationMs * ticksPerMs).toLong().coerceAtLeast(1L)
            val endTick = startTick + durationTicks

            val velocity = note.velocity.coerceIn(1, 127)
            val pitch = note.pitch.coerceIn(0, 127)

            // Note On
            events.add(TimedEvent(tick = startTick, status = 0x90 or (noteChannel and 0x0F), data1 = pitch, data2 = velocity))
            // Note Off
            events.add(TimedEvent(tick = endTick, status = 0x80 or (noteChannel and 0x0F), data1 = pitch, data2 = 0))
        }

        // Sort events chronologically. For identical ticks, sort Note Off before Note On.
        events.sortWith { a, b ->
            if (a.tick != b.tick) a.tick.compareTo(b.tick)
            else {
                // Note-Off (0x80) preferred before Note-On (0x90) on identical tick
                val typeA = a.status and 0xF0
                val typeB = b.status and 0xF0
                typeA.compareTo(typeB)
            }
        }

        FileOutputStream(outputFile).use { fos ->
            // --- HEADER CHUNK (MThd) ---
            // Format 1 (multi-track synchronous), 2 tracks (Conductor + Instrument), PPQ
            fos.write("MThd".toByteArray(Charsets.US_ASCII))
            fos.write(intToBytes(6)) // Header length = 6
            fos.write(shortToBytes(1)) // Format 1
            fos.write(shortToBytes(2)) // 2 Tracks
            fos.write(shortToBytes(ppq)) // Time division (PPQ)

            // --- TRACK 0: CONDUCTOR TRACK (Tempo & Time Signature) ---
            val track0Bytes = ByteArrayOutputStream()
            // Track Name
            writeMetaEvent(track0Bytes, 0, 0x03, "StemFlow Conductor".toByteArray(Charsets.UTF_8))
            // Time Signature: 4/4, 24 MIDI clocks/click, 8 32nd-notes/quarter
            writeMetaEvent(track0Bytes, 0, 0x58, byteArrayOf(0x04, 0x02, 0x18, 0x08))
            // Set Tempo: Microseconds per quarter note = (60,000,000 / BPM)
            val usPerQuarter = (60_000_000.0 / bpm).toInt()
            val tempoBytes = byteArrayOf(
                ((usPerQuarter shr 16) and 0xFF).toByte(),
                ((usPerQuarter shr 8) and 0xFF).toByte(),
                (usPerQuarter and 0xFF).toByte()
            )
            writeMetaEvent(track0Bytes, 0, 0x51, tempoBytes)
            // End of Track
            writeMetaEvent(track0Bytes, 0, 0x2F, ByteArray(0))

            val t0Data = track0Bytes.toByteArray()
            fos.write("MTrk".toByteArray(Charsets.US_ASCII))
            fos.write(intToBytes(t0Data.size))
            fos.write(t0Data)

            // --- TRACK 1: INSTRUMENT NOTES TRACK ---
            val track1Bytes = ByteArrayOutputStream()
            // Instrument Track Name
            writeMetaEvent(track1Bytes, 0, 0x03, trackName.toByteArray(Charsets.UTF_8))

            var lastTick = 0L
            for (ev in events) {
                val deltaTicks = (ev.tick - lastTick).coerceAtLeast(0L)
                lastTick = ev.tick

                writeVarLen(track1Bytes, deltaTicks)
                track1Bytes.write(ev.status)
                track1Bytes.write(ev.data1)
                track1Bytes.write(ev.data2)
            }

            // End of Track event with 0 delta
            writeMetaEvent(track1Bytes, 0, 0x2F, ByteArray(0))

            val t1Data = track1Bytes.toByteArray()
            fos.write("MTrk".toByteArray(Charsets.US_ASCII))
            fos.write(intToBytes(t1Data.size))
            fos.write(t1Data)
        }
    }

    private fun writeMetaEvent(out: ByteArrayOutputStream, deltaTicks: Long, metaType: Int, data: ByteArray) {
        writeVarLen(out, deltaTicks)
        out.write(0xFF)
        out.write(metaType)
        writeVarLen(out, data.size.toLong())
        out.write(data)
    }

    /**
     * Writes MIDI variable-length quantity (7 bits per byte, MSB set on all except last).
     */
    private fun writeVarLen(out: ByteArrayOutputStream, value: Long) {
        var v = value
        val buffer = ByteArray(8)
        var count = 0
        buffer[count++] = (v and 0x7F).toByte()
        v = v shr 7
        while (v > 0) {
            buffer[count++] = ((v and 0x7F) or 0x80).toByte()
            v = v shr 7
        }
        for (i in count - 1 downTo 0) {
            out.write(buffer[i].toInt())
        }
    }

    private fun intToBytes(value: Int): ByteArray {
        return ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array()
    }

    private fun shortToBytes(value: Int): ByteArray {
        return ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(value.toShort()).array()
    }
}
