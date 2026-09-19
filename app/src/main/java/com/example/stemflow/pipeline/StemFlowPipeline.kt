package com.example.stemflow.pipeline

import android.content.Context
import android.net.Uri
import com.example.stemflow.audio.AudioDecoder
import com.example.stemflow.audio.WavStreamReader
import com.example.stemflow.audio.WavStreamWriter
import com.example.stemflow.data.JobEntity
import com.example.stemflow.data.JobRepository
import com.example.stemflow.midi.MidiWriter
import com.example.stemflow.model.FailureCategory
import com.example.stemflow.model.MidiNote
import com.example.stemflow.model.StemFlowException
import com.example.stemflow.model.StemType
import com.example.stemflow.separator.NeuralDspSeparator
import com.example.stemflow.transcriber.BasicPitchEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

object StemFlowPipeline {

    suspend fun executeJob(
        context: Context,
        jobRepository: JobRepository,
        jobId: Long,
        onStatusUpdate: (phase: String, progress: Float, peakRam: Float) -> Unit = { _, _, _ -> }
    ): JobEntity = withContext(Dispatchers.Default) {
        var job = jobRepository.getJobById(jobId)
            ?: throw StemFlowException(FailureCategory.STORAGE, "Job $jobId not found in database.")

        val jobStartTime = System.currentTimeMillis()
        var maxPeakRam = 0f
        val jobDir = File(job.jobDir)
        val stemsDir = File(jobDir, "stems").apply { mkdirs() }
        val midiDir = File(jobDir, "midi").apply { mkdirs() }
        val decodedSourceWav = File(jobDir, "source_decoded.wav")

        fun updateRam() {
            val runtime = Runtime.getRuntime()
            val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024f * 1024f)
            if (usedMb > maxPeakRam) maxPeakRam = usedMb
        }

        try {
            // --- 1. VALIDATING & METADATA ---
            currentCoroutineContext().ensureActive()
            job = job.copy(phase = "VALIDATING", progress = 0.05f, updatedAt = System.currentTimeMillis())
            jobRepository.updateJob(job)
            onStatusUpdate("VALIDATING", 0.05f, maxPeakRam)

            val sourceUri = Uri.parse(job.sourceUri)

            // --- 2. DECODING TO DISK-BACKED WAV ---
            currentCoroutineContext().ensureActive()
            job = job.copy(phase = "DECODING", progress = 0.10f, updatedAt = System.currentTimeMillis())
            jobRepository.updateJob(job)
            onStatusUpdate("DECODING", 0.10f, maxPeakRam)

            if (sourceUri.scheme == "builtin" || job.sourceUri.startsWith("builtin://")) {
                // Generate clean synthetic composite source for test execution
                generateBuiltinSource(decodedSourceWav)
            } else {
                AudioDecoder.decodeToDiskWav(context, sourceUri, decodedSourceWav) { decProgress ->
                    updateRam()
                    val p = 0.10f + (decProgress * 0.15f)
                    onStatusUpdate("DECODING", p, maxPeakRam)
                }
            }
            updateRam()

            // --- 3. NEURAL 6-STEM SEPARATION (Bounded Window Streaming) ---
            currentCoroutineContext().ensureActive()
            job = job.copy(phase = "SEPARATING", progress = 0.25f, updatedAt = System.currentTimeMillis())
            jobRepository.updateJob(job)
            onStatusUpdate("SEPARATING", 0.25f, maxPeakRam)

            val stemFiles = NeuralDspSeparator.separate(decodedSourceWav, stemsDir) { sepProgress, peakRam ->
                updateRam()
                val p = 0.25f + (sepProgress * 0.35f)
                onStatusUpdate("SEPARATING", p, maxPeakRam)
            }
            updateRam()

            // --- 4. STEM-SPECIFIC TRANSCRIPTION & MIDI GENERATION ---
            currentCoroutineContext().ensureActive()
            job = job.copy(phase = "TRANSCRIBING", progress = 0.60f, updatedAt = System.currentTimeMillis())
            jobRepository.updateJob(job)
            onStatusUpdate("TRANSCRIBING", 0.60f, maxPeakRam)

            val allStemNotes = mutableMapOf<StemType, List<MidiNote>>()
            val stemList = StemType.entries
            for (i in stemList.indices) {
                currentCoroutineContext().ensureActive()
                val stemType = stemList[i]
                val stemWav = stemFiles[stemType] ?: File(stemsDir, "${stemType.id}.wav")

                job = job.copy(currentStem = stemType.displayName)
                jobRepository.updateJob(job)

                // Read stem audio from disk into bounded memory
                val stemSamples = readFullStemSamples(stemWav, maxSeconds = 120)
                val notes = BasicPitchEngine.transcribeStem(stemSamples, stemType)
                allStemNotes[stemType] = notes

                // Write standard MIDI file
                val midiFile = File(midiDir, "${stemType.id}.mid")
                MidiWriter.writeMidiFile(
                    notes = notes,
                    outputFile = midiFile,
                    trackName = "StemFlow ${stemType.displayName}",
                    channel = stemType.midiChannel
                )

                updateRam()
                val stemProgress = 0.60f + ((i + 1).toFloat() / stemList.size.toFloat()) * 0.20f
                onStatusUpdate("TRANSCRIBING: ${stemType.displayName}", stemProgress, maxPeakRam)
            }

            // --- 5. INTERPLAY ANALYTICS & ANALYSIS JSON ---
            currentCoroutineContext().ensureActive()
            job = job.copy(phase = "ANALYZING", progress = 0.82f, updatedAt = System.currentTimeMillis())
            jobRepository.updateJob(job)
            onStatusUpdate("ANALYZING", 0.82f, maxPeakRam)

            val analysis = AnalyticsEngine.analyzeStems(
                stemFiles = stemFiles,
                stemNotes = allStemNotes,
                sourceFileName = job.name,
                totalDurationMs = job.durationMs
            )

            val analysisJson = JSONObject().apply {
                put("status", "success")
                put("source_file", job.name)
                put("duration_ms", job.durationMs)
                put("peak_ram_mb", maxPeakRam)
                put("analytics", JSONObject().apply {
                    put("interplay_analytics", JSONObject().apply {
                        put("rhythmic_lock_score", analysis.rhythmicLockScore)
                        put("vocal_masking_pct", JSONObject(analysis.vocalMaskingPct as Map<*, *>))
                        put("harmonic_clash_profile", JSONObject(analysis.harmonicClashProfile as Map<*, *>))
                    })
                })
            }

            val analysisFile = File(jobDir, "stemflow_analysis.json")
            analysisFile.writeText(analysisJson.toString(2))

            // --- 6. ARCHIVE & PACKAGING ---
            currentCoroutineContext().ensureActive()
            job = job.copy(phase = "PACKAGING", progress = 0.90f, updatedAt = System.currentTimeMillis())
            jobRepository.updateJob(job)
            onStatusUpdate("PACKAGING", 0.90f, maxPeakRam)

            val bundleZip = File(jobDir, "stemflow_bundle.zip")
            ArchivePackager.createProductionBundle(stemsDir, midiDir, analysisFile, bundleZip) { zipProgress ->
                val p = 0.90f + (zipProgress * 0.10f)
                onStatusUpdate("PACKAGING", p, maxPeakRam)
            }

            // --- 7. MARK COMPLETE ---
            val totalProcessingTime = System.currentTimeMillis() - jobStartTime
            job = job.copy(
                phase = "COMPLETE",
                progress = 1.0f,
                currentStem = null,
                peakRamMb = maxPeakRam,
                processingTimeMs = totalProcessingTime,
                zipPath = bundleZip.absolutePath,
                analysisJson = analysisJson.toString(),
                updatedAt = System.currentTimeMillis()
            )
            jobRepository.updateJob(job)
            onStatusUpdate("COMPLETE", 1.0f, maxPeakRam)

            return@withContext job

        } catch (e: CancellationException) {
            job = job.copy(
                phase = "CANCELLED",
                errorCategory = FailureCategory.CANCELLATION.name,
                errorMessage = "Job was cancelled by user.",
                updatedAt = System.currentTimeMillis()
            )
            jobRepository.updateJob(job)
            throw e
        } catch (e: Exception) {
            val (category, message) = if (e is StemFlowException) {
                Pair(e.category.name, e.message ?: "Pipeline failure")
            } else {
                Pair(FailureCategory.DEVICE_RESOURCE.name, e.message ?: "Unknown internal failure")
            }

            job = job.copy(
                phase = "FAILED",
                errorCategory = category,
                errorMessage = message,
                updatedAt = System.currentTimeMillis()
            )
            jobRepository.updateJob(job)
            throw e
        }
    }

    private fun readFullStemSamples(wavFile: File, maxSeconds: Int): FloatArray {
        WavStreamReader(wavFile).use { reader ->
            val totalSamples = minOf(reader.totalSamplesPerChannel, (reader.sampleRate * maxSeconds).toLong()).toInt()
            val interleaved = FloatArray(totalSamples * reader.numChannels)
            val read = reader.readWindow(0, totalSamples, interleaved)
            val mono = FloatArray(read)
            for (i in 0 until read) {
                mono[i] = if (reader.numChannels >= 2) {
                    0.5f * (interleaved[i * 2] + interleaved[i * 2 + 1])
                } else {
                    interleaved[i]
                }
            }
            return mono
        }
    }

    private fun generateBuiltinSource(destFile: File) {
        val sampleRate = 44100
        val durationSec = 4.0
        val totalSamples = (sampleRate * durationSec).toInt()
        val interleaved = FloatArray(totalSamples * 2)

        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate
            // Melody + Bass + Hi-hat
            val v = (kotlin.math.sin(2.0 * Math.PI * 440.0 * t) * 0.3 +
                    kotlin.math.sin(2.0 * Math.PI * 110.0 * t) * 0.4 +
                    ((Math.random() - 0.5) * 0.1)).toFloat()
            interleaved[i * 2] = v
            interleaved[i * 2 + 1] = v
        }

        WavStreamWriter(destFile, sampleRate = sampleRate, numChannels = 2).use {
            it.writeSamples(interleaved)
        }
    }
}
