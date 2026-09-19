package com.example.stemflow.model

import androidx.compose.ui.graphics.Color

/**
 * The six canonical stems produced by HTDemucs 6s.
 * Ordering strictly matches the 6-source HTDemucs model layout:
 * [VOCALS, DRUMS, BASS, GUITAR, PIANO, OTHER]
 */
enum class StemType(
    val id: String,
    val displayName: String,
    val midiChannel: Int, // 0-indexed: Drums = 9 (Channel 10 in 1-indexed MIDI)
    val color: Color,
    val minFreqHz: Float,
    val maxFreqHz: Float,
    val defaultMidiProgram: Int // General MIDI instrument patch
) {
    VOCALS(
        id = "vocals",
        displayName = "Vocals",
        midiChannel = 0,
        color = Color(0xFFEF4444), // Crimson Flame
        minFreqHz = 65f, // ~C2
        maxFreqHz = 1050f, // ~C6
        defaultMidiProgram = 54 // Synth Voice / Choir
    ),
    DRUMS(
        id = "drums",
        displayName = "Drums",
        midiChannel = 9, // MIDI Channel 10
        color = Color(0xFFE2E8F0), // Brushed Chrome Steel
        minFreqHz = 30f,
        maxFreqHz = 16000f,
        defaultMidiProgram = 0 // Standard Drum Kit
    ),
    BASS(
        id = "bass",
        displayName = "Bass",
        midiChannel = 1,
        color = Color(0xFFBE123C), // Deep Ruby / Scarlet
        minFreqHz = 30f, // ~B0
        maxFreqHz = 260f, // ~C4
        defaultMidiProgram = 33 // Electric Bass (finger)
    ),
    GUITAR(
        id = "guitar",
        displayName = "Guitar",
        midiChannel = 2,
        color = Color(0xFFF59E0B), // Amber Bronze
        minFreqHz = 80f, // ~E2
        maxFreqHz = 1400f, // ~F6
        defaultMidiProgram = 25 // Acoustic Guitar (steel)
    ),
    PIANO(
        id = "piano",
        displayName = "Piano",
        midiChannel = 3,
        color = Color(0xFF38BDF8), // Platinum Ice
        minFreqHz = 27.5f, // A0
        maxFreqHz = 4200f, // C8
        defaultMidiProgram = 0 // Acoustic Grand Piano
    ),
    OTHER(
        id = "other",
        displayName = "Other",
        midiChannel = 4,
        color = Color(0xFFA855F7), // Electric Amethyst
        minFreqHz = 20f,
        maxFreqHz = 20000f,
        defaultMidiProgram = 81 // Lead Synth
    );

    companion object {
        fun fromId(id: String): StemType? = entries.find { it.id.equals(id, ignoreCase = true) }
    }
}
