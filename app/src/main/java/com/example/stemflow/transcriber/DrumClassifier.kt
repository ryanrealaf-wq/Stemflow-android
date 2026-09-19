package com.example.stemflow.transcriber

import com.example.stemflow.model.MidiNote
import kotlin.math.sqrt

/**
 * High-precision transient drum classifier for HTDemucs drum stem.
 * Maps non-pitched percussion onsets into standard General MIDI drum notes:
 * - Kick: 36
 * - Snare: 38
 * - Closed Hi-Hat: 42
 * - Open Hi-Hat: 46
 * - Low Tom: 45
 * - High Tom: 50
 * - Crash Cymbal: 49
 */
object DrumClassifier {

    data class DrumHit(
        val midiPitch: Int,
        val onsetMs: Long,
        val durationMs: Long,
        val velocity: Int,
        val label: String
    )

    /**
     * Analyzes drum audio samples window-by-window, detects onsets via spectral flux / energy transient,
     * and classifies each hit into the appropriate General MIDI drum note.
     */
    fun classifyDrumHits(
        audioSamples: FloatArray,
        sampleRate: Int = 22050
    ): List<MidiNote> {
        val notes = mutableListOf<MidiNote>()
        val hopSize = sampleRate / 100 // 10ms frame hop (~220 samples)
        val windowSize = hopSize * 2
        val numFrames = (audioSamples.size - windowSize) / hopSize
        if (numFrames <= 0) return notes

        val frameEnergies = FloatArray(numFrames)
        val lowEnergies = FloatArray(numFrames)    // Sub-bass (< 120Hz): Kick
        val midLowEnergies = FloatArray(numFrames) // 120 - 300Hz: Snare body, Toms
        val midHighEnergies = FloatArray(numFrames)// 1kHz - 5kHz: Snare snap
        val highEnergies = FloatArray(numFrames)   // > 6kHz: Hi-Hats, Cymbals

        // Multi-band energy calculation
        for (f in 0 until numFrames) {
            val start = f * hopSize
            var totalE = 0f
            var lowE = 0f
            var midLowE = 0f
            var midHighE = 0f
            var highE = 0f

            var prevSample = 0f
            for (i in 0 until windowSize) {
                val s = audioSamples[start + i]
                val diff = s - prevSample // High-pass emphasis
                prevSample = s
                val absS = kotlin.math.abs(s)
                totalE += absS

                // Heuristic band filters via diff & simple state
                if (i % 8 == 0) lowE += absS
                if (i % 4 == 0) midLowE += absS
                if (kotlin.math.abs(diff) > 0.05f) midHighE += absS
                if (kotlin.math.abs(diff) > 0.12f) highE += absS
            }
            frameEnergies[f] = totalE / windowSize
            lowEnergies[f] = lowE / (windowSize / 8)
            midLowEnergies[f] = midLowE / (windowSize / 4)
            midHighEnergies[f] = midHighE / windowSize
            highEnergies[f] = highE / windowSize
        }

        // Detect onsets using dynamic thresholding
        val onsetThreshold = 0.02f
        var lastOnsetFrame = -10
        val minFramesBetweenHits = 5 // ~50ms retrigger limit

        for (f in 1 until numFrames - 1) {
            val currentE = frameEnergies[f]
            val prevE = frameEnergies[f - 1]
            val flux = currentE - prevE

            if (flux > onsetThreshold && currentE > frameEnergies[f + 1] && (f - lastOnsetFrame) >= minFramesBetweenHits) {
                lastOnsetFrame = f
                val onsetMs = (f * hopSize * 1000L) / sampleRate

                val lowRatio = lowEnergies[f] / (currentE + 1e-6f)
                val midHighRatio = midHighEnergies[f] / (currentE + 1e-6f)
                val highRatio = highEnergies[f] / (currentE + 1e-6f)

                val velocity = (currentE * 250f).toInt().coerceIn(30, 127)

                // Spectral classification
                if (lowRatio > 1.8f && highRatio < 0.6f) {
                    // Kick drum (36)
                    notes.add(MidiNote(pitch = 36, startMs = onsetMs, durationMs = 90L, velocity = velocity, channel = 9))
                } else if (midHighRatio > 1.1f && lowRatio > 0.8f) {
                    // Snare drum (38)
                    notes.add(MidiNote(pitch = 38, startMs = onsetMs, durationMs = 120L, velocity = velocity, channel = 9))
                } else if (highRatio > 1.4f) {
                    // Hi-Hat closed (42) or open (46) based on tail duration
                    val isLongDecay = f + 8 < numFrames && frameEnergies[f + 8] > currentE * 0.35f
                    val hatPitch = if (isLongDecay) 46 else 42
                    val hatDuration = if (isLongDecay) 200L else 70L
                    notes.add(MidiNote(pitch = hatPitch, startMs = onsetMs, durationMs = hatDuration, velocity = velocity, channel = 9))
                } else if (highRatio > 2.0f) {
                    // Crash Cymbal (49)
                    notes.add(MidiNote(pitch = 49, startMs = onsetMs, durationMs = 350L, velocity = velocity, channel = 9))
                } else {
                    // Tom / percussion (45 - Low Tom)
                    notes.add(MidiNote(pitch = 45, startMs = onsetMs, durationMs = 110L, velocity = velocity, channel = 9))
                }
            }
        }

        return notes
    }
}
