package com.example.stemflow.midi

import com.example.stemflow.model.MidiNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MidiWriterTest {

    @Test
    fun testWriteStandardMidiFile() {
        val tempMidiFile = File.createTempFile("test_midi", ".mid")
        try {
            val notes = listOf(
                MidiNote(pitch = 60, startMs = 0, durationMs = 500, velocity = 100, channel = 0),
                MidiNote(pitch = 64, startMs = 250, durationMs = 500, velocity = 90, channel = 0),
                MidiNote(pitch = 67, startMs = 500, durationMs = 500, velocity = 95, channel = 0)
            )

            MidiWriter.writeMidiFile(
                notes = notes,
                outputFile = tempMidiFile,
                trackName = "Test Piano Track",
                bpm = 120.0,
                ppq = 480
            )

            assertTrue(tempMidiFile.exists())
            assertTrue(tempMidiFile.length() > 30)

            val bytes = tempMidiFile.readBytes()
            // Verify SMF Header chunk "MThd"
            assertEquals('M'.code.toByte(), bytes[0])
            assertEquals('T'.code.toByte(), bytes[1])
            assertEquals('h'.code.toByte(), bytes[2])
            assertEquals('d'.code.toByte(), bytes[3])

            // Verify Track 0 "MTrk"
            val track0Index = 14
            assertEquals('M'.code.toByte(), bytes[track0Index])
            assertEquals('T'.code.toByte(), bytes[track0Index + 1])
            assertEquals('r'.code.toByte(), bytes[track0Index + 2])
            assertEquals('k'.code.toByte(), bytes[track0Index + 3])
        } finally {
            tempMidiFile.delete()
        }
    }
}
