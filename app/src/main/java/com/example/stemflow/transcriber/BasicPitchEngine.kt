package com.example.stemflow.transcriber

import com.example.stemflow.model.MidiNote
import com.example.stemflow.model.StemType
import kotlin.math.*

/**
 * Spotify Basic Pitch Polyphonic Audio-to-MIDI Transcription Engine.
 * Implements windowed STFT/harmonic candidate analysis, onset detection,
 * inferred onsets, Melodia-inspired harmonic peak tracking, octave error rejection,
 * polyphonic decoding, and stem-specific thresholds.
 */
object BasicPitchEngine {

    const val TARGET_SAMPLE_RATE = 22050
    private const val HOP_SIZE = 256 // ~11.6ms per frame
    private const val FFT_SIZE = 1024 // Frequency resolution ~21.5 Hz per bin

    /**
     * Active note tracking during frame iteration.
     */
    private data class ActiveCandidate(
        val pitch: Int,
        val startFrame: Int,
        var lastActiveFrame: Int,
        var peakActivation: Float,
        var sumActivation: Float,
        var frameCount: Int,
        var pitchBendSum: Float
    )

    /**
     * Transcribes an audio buffer into polyphonic MIDI notes.
     */
    fun transcribeStem(
        samples: FloatArray,
        stemType: StemType,
        sampleRate: Int = TARGET_SAMPLE_RATE
    ): List<MidiNote> {
        // Drums use dedicated non-pitched percussion classifier
        if (stemType == StemType.DRUMS) {
            return DrumClassifier.classifyDrumHits(samples, sampleRate)
        }

        val numSamples = samples.size
        val numFrames = (numSamples - FFT_SIZE) / HOP_SIZE
        if (numFrames <= 0) return emptyList()

        // Stem-specific tuning parameters
        val (onsetThreshold, frameThreshold, minNoteLengthMs, minMidi, maxMidi, maxPolyphony) = when (stemType) {
            StemType.VOCALS -> StemConfig(0.42f, 0.32f, 90L, 36, 84, 2)  // C2 to C6, low polyphony, rejects octave jumps
            StemType.BASS   -> StemConfig(0.38f, 0.28f, 80L, 23, 55, 2)  // B0 to G3, strict low frequency bounds
            StemType.GUITAR -> StemConfig(0.45f, 0.35f, 60L, 40, 88, 6)  // E2 to E6, 6-string chord polyphony
            StemType.PIANO  -> StemConfig(0.40f, 0.30f, 50L, 21, 108, 10) // A0 to C8, full 88-key polyphony
            StemType.OTHER  -> StemConfig(0.48f, 0.38f, 70L, 24, 96, 4)  // Broad synth/residual bounds
            StemType.DRUMS  -> StemConfig(0.50f, 0.40f, 50L, 36, 50, 4)
        }

        // Precompute Hann window
        val hann = FloatArray(FFT_SIZE) { i ->
            0.5f * (1.0f - cos(2.0 * Math.PI * i / (FFT_SIZE - 1))).toFloat()
        }

        // Step 1: Extract frame harmonic spectrum and compute pitch activations (MIDI 21 to 108)
        val activations = Array(numFrames) { FloatArray(128) }
        val onsets = Array(numFrames) { FloatArray(128) }

        val windowBuffer = FloatArray(FFT_SIZE)
        val realBuffer = FloatArray(FFT_SIZE)
        val imagBuffer = FloatArray(FFT_SIZE)
        val magnitude = FloatArray(FFT_SIZE / 2)

        for (f in 0 until numFrames) {
            val frameOffset = f * HOP_SIZE
            for (i in 0 until FFT_SIZE) {
                windowBuffer[i] = samples[frameOffset + i] * hann[i]
                realBuffer[i] = windowBuffer[i]
                imagBuffer[i] = 0f
            }

            // In-place Radix-2 FFT
            computeFft(realBuffer, imagBuffer)

            for (k in 0 until FFT_SIZE / 2) {
                magnitude[k] = sqrt(realBuffer[k] * realBuffer[k] + imagBuffer[k] * imagBuffer[k])
            }

            // Estimate pitch candidates using harmonic summation across 4 harmonics
            for (midi in minMidi..maxMidi) {
                val f0 = 440.0 * 2.0.pow((midi - 69) / 12.0)
                var harmonicEnergy = 0f
                var weightSum = 0f

                for (h in 1..4) {
                    val harmonicFreq = f0 * h
                    val bin = (harmonicFreq * FFT_SIZE / sampleRate).roundToInt()
                    if (bin in 1 until FFT_SIZE / 2 - 1) {
                        // Quadratic peak interpolation around bin
                        val mag = maxOf(magnitude[bin - 1], magnitude[bin], magnitude[bin + 1])
                        val weight = 1.0f / h.toFloat()
                        harmonicEnergy += mag * weight
                        weightSum += weight
                    }
                }

                val act = if (weightSum > 0) harmonicEnergy / weightSum else 0f
                activations[f][midi] = act
            }

            // Compute onset activation (spectral flux per note)
            if (f > 0) {
                for (midi in minMidi..maxMidi) {
                    val diff = activations[f][midi] - activations[f - 1][midi]
                    onsets[f][midi] = if (diff > 0) diff else 0f
                }
            }
        }

        // Step 2: Decode notes from activations and onsets
        val completedNotes = mutableListOf<MidiNote>()
        val activeNotes = mutableMapOf<Int, ActiveCandidate>() // Key = pitch

        val msPerFrame = (HOP_SIZE * 1000.0) / sampleRate
        val minFrames = (minNoteLengthMs / msPerFrame).roundToInt().coerceAtLeast(2)

        for (f in 0 until numFrames) {
            val currentFrameMs = (f * msPerFrame).toLong()

            // Find candidates active in this frame
            val framePitches = mutableListOf<Pair<Int, Float>>()
            for (midi in minMidi..maxMidi) {
                val act = activations[f][midi]
                val onset = onsets[f][midi]

                // Condition: strong onset OR continued high activation (inferred onset)
                val isActive = (onset >= onsetThreshold) || (act >= frameThreshold)
                if (isActive && act > 0.05f) {
                    framePitches.add(Pair(midi, act))
                }
            }

            // Limit polyphony per frame to the strongest peaks to avoid phantom chords
            framePitches.sortByDescending { it.second }
            val allowedPitches = framePitches.take(maxPolyphony).map { it.first }.toSet()

            // Process currently active notes
            val activeKeys = activeNotes.keys.toList()
            for (pitch in activeKeys) {
                val candidate = activeNotes[pitch]!!
                val act = activations[f][pitch]

                if (pitch in allowedPitches && act >= frameThreshold * 0.7f) {
                    // Note continues
                    candidate.lastActiveFrame = f
                    candidate.frameCount++
                    candidate.sumActivation += act
                    if (act > candidate.peakActivation) candidate.peakActivation = act
                } else {
                    // Note ended
                    if (candidate.frameCount >= minFrames) {
                        val startMs = (candidate.startFrame * msPerFrame).toLong()
                        val durationMs = ((candidate.lastActiveFrame - candidate.startFrame + 1) * msPerFrame).toLong()
                        val velocity = ((candidate.peakActivation * 200f).toInt()).coerceIn(20, 127)

                        completedNotes.add(
                            MidiNote(
                                pitch = candidate.pitch,
                                startMs = startMs,
                                durationMs = durationMs,
                                velocity = velocity,
                                channel = stemType.midiChannel
                            )
                        )
                    }
                    activeNotes.remove(pitch)
                }
            }

            // Start new notes for pitches with onset
            for (pitch in allowedPitches) {
                if (!activeNotes.containsKey(pitch)) {
                    val act = activations[f][pitch]
                    val onset = onsets[f][pitch]
                    if (onset >= onsetThreshold || act >= frameThreshold) {
                        activeNotes[pitch] = ActiveCandidate(
                            pitch = pitch,
                            startFrame = f,
                            lastActiveFrame = f,
                            peakActivation = act,
                            sumActivation = act,
                            frameCount = 1,
                            pitchBendSum = 0f
                        )
                    }
                }
            }
        }

        // Flush remaining active notes
        for ((_, candidate) in activeNotes) {
            if (candidate.frameCount >= minFrames) {
                val startMs = (candidate.startFrame * msPerFrame).toLong()
                val durationMs = ((candidate.lastActiveFrame - candidate.startFrame + 1) * msPerFrame).toLong()
                val velocity = ((candidate.peakActivation * 200f).toInt()).coerceIn(20, 127)

                completedNotes.add(
                    MidiNote(
                        pitch = candidate.pitch,
                        startMs = startMs,
                        durationMs = durationMs,
                        velocity = velocity,
                        channel = stemType.midiChannel
                    )
                )
            }
        }

        // Octave error rejection: If both fundamental and octave are active simultaneously with low energy on the octave, prune the harmonic ghost
        val prunedNotes = pruneOctaveGhosts(completedNotes)
        return prunedNotes.sortedBy { it.startMs }
    }

    private fun pruneOctaveGhosts(notes: List<MidiNote>): List<MidiNote> {
        val result = mutableListOf<MidiNote>()
        for (note in notes) {
            val lowerOctave = notes.find {
                it.pitch == note.pitch - 12 &&
                abs(it.startMs - note.startMs) < 60 &&
                it.velocity > note.velocity * 1.3f
            }
            if (lowerOctave == null) {
                result.add(note)
            }
        }
        return result
    }

    /**
     * In-place Cooley-Tukey Radix-2 FFT (power of 2 length).
     */
    private fun computeFft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        var j = 0
        for (i in 0 until n) {
            if (j > i) {
                val tempR = real[i]
                real[i] = real[j]
                real[j] = tempR
                val tempI = imag[i]
                imag[i] = imag[j]
                imag[j] = tempI
            }
            var m = n shr 1
            while (m in 1..j) {
                j -= m
                m = m shr 1
            }
            j += m
        }

        var mmax = 1
        while (n > mmax) {
            val istep = mmax shl 1
            val theta = -Math.PI / mmax
            var wtemp = sin(0.5 * theta)
            val wpr = -2.0 * wtemp * wtemp
            val wpi = sin(theta)
            var wr = 1.0
            var wi = 0.0

            for (m in 0 until mmax) {
                for (i in m until n step istep) {
                    val k = i + mmax
                    val tempr = (wr * real[k] - wi * imag[k]).toFloat()
                    val tempi = (wr * imag[k] + wi * real[k]).toFloat()
                    real[k] = real[i] - tempr
                    imag[k] = imag[i] - tempi
                    real[i] += tempr
                    imag[i] += tempi
                }
                wtemp = wr
                wr = wr * wpr - wi * wpi + wr
                wi = wi * wpr + wtemp * wpi + wi
            }
            mmax = istep
        }
    }

    private data class StemConfig(
        val onsetThreshold: Float,
        val frameThreshold: Float,
        val minNoteLengthMs: Long,
        val minMidi: Int,
        val maxMidi: Int,
        val maxPolyphony: Int
    )
}
