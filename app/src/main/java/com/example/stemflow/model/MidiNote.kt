package com.example.stemflow.model

/**
 * Represents a transcribed musical note event with exact timing and velocity.
 */
data class MidiNote(
    val pitch: Int,             // MIDI note number (0 - 127)
    val startMs: Long,          // Note onset time in milliseconds
    val durationMs: Long,       // Duration in milliseconds (minimum enforced per stem)
    val velocity: Int,          // MIDI velocity (1 - 127)
    val channel: Int = 0,       // MIDI channel (0 - 15; 9 for drums)
    val pitchBendCents: Float = 0f // Inferred pitch deviation in cents (-100 to +100)
) {
    val endMs: Long get() = startMs + durationMs

    val noteName: String get() {
        val names = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        val octave = (pitch / 12) - 1
        val note = names[pitch % 12]
        return "$note$octave"
    }
}
