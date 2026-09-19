package com.example.stemflow.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.example.stemflow.model.AudioMetadata
import com.example.stemflow.model.FailureCategory
import com.example.stemflow.model.StemFlowException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream

object AudioDecoder {

    /**
     * Extracts and validates audio metadata from a content Uri or File.
     */
    fun extractMetadata(context: Context, uri: Uri): AudioMetadata {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
        } catch (e: Exception) {
            throw StemFlowException(
                FailureCategory.INPUT,
                "Failed to read audio source. File may be corrupted or inaccessible: ${e.message}",
                e
            )
        }

        val mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            ?: "audio/unknown"
        val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        val durationMs = durationStr?.toLongOrNull() ?: 0L

        // Validate MIME type
        if (!mimeType.startsWith("audio/") && !mimeType.contains("audio") && !mimeType.contains("ogg") && !mimeType.contains("mp4")) {
            throw StemFlowException(
                FailureCategory.INPUT,
                "Unsupported file format '$mimeType'. Please select a standard audio file (WAV, MP3, FLAC, AAC, OGG)."
            )
        }

        // Get file name and size
        var fileName = "audio_track"
        var fileSize = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (nameIndex != -1) fileName = cursor.getString(nameIndex) ?: fileName
                if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex)
            }
        }

        // Use MediaExtractor to inspect actual track parameters
        val extractor = MediaExtractor()
        var sampleRate = 44100
        var channels = 2
        var hasAudioTrack = false

        try {
            extractor.setDataSource(context, uri, null)
            val numTracks = extractor.trackCount
            for (i in 0 until numTracks) {
                val format = extractor.getTrackFormat(i)
                val trackMime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (trackMime.startsWith("audio/")) {
                    hasAudioTrack = true
                    if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    break
                }
            }
        } catch (e: Exception) {
            throw StemFlowException(
                FailureCategory.DECODER,
                "Failed to inspect audio tracks: ${e.message}",
                e
            )
        } finally {
            extractor.release()
            retriever.release()
        }

        if (!hasAudioTrack) {
            throw StemFlowException(
                FailureCategory.INPUT,
                "No valid audio track detected inside the selected file."
            )
        }

        return AudioMetadata(
            uriString = uri.toString(),
            fileName = fileName,
            mimeType = mimeType,
            sampleRate = sampleRate,
            channels = channels,
            durationMs = durationMs,
            sizeBytes = fileSize
        )
    }

    /**
     * Decodes audio from [uri] using MediaCodec directly to a disk-backed 16-bit PCM WAV file.
     * Guarantees bounded RAM consumption by streaming output chunks straight to [destWavFile].
     * Supports coroutine cancellation.
     */
    suspend fun decodeToDiskWav(
        context: Context,
        uri: Uri,
        destWavFile: File,
        onProgress: (Float) -> Unit = {}
    ): AudioMetadata {
        val metadata = extractMetadata(context, uri)
        val extractor = MediaExtractor()

        try {
            extractor.setDataSource(context, uri, null)
        } catch (e: Exception) {
            throw StemFlowException(FailureCategory.DECODER, "Could not open audio extractor: ${e.message}", e)
        }

        var audioTrackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val trackFormat = extractor.getTrackFormat(i)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                audioTrackIndex = i
                format = trackFormat
                break
            }
        }

        if (audioTrackIndex == -1 || format == null) {
            extractor.release()
            throw StemFlowException(FailureCategory.DECODER, "No decodable audio track found in file.")
        }

        extractor.selectTrack(audioTrackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
        val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
        val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else metadata.durationMs * 1000L

        var codec: MediaCodec? = null
        var wavWriter: WavStreamWriter? = null

        try {
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            wavWriter = WavStreamWriter(destWavFile, sampleRate = sampleRate, numChannels = channels)

            val info = MediaCodec.BufferInfo()
            var isInputEOS = false
            var isOutputEOS = false
            val kTimeOutUs = 10000L

            while (!isOutputEOS) {
                // Check coroutine cancellation
                currentCoroutineContext().ensureActive()

                if (!isInputEOS) {
                    val inputBufIndex = codec.dequeueInputBuffer(kTimeOutUs)
                    if (inputBufIndex >= 0) {
                        val inputBuf = codec.getInputBuffer(inputBufIndex)
                        if (inputBuf != null) {
                            val sampleSize = extractor.readSampleData(inputBuf, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inputBufIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isInputEOS = true
                            } else {
                                val sampleTime = extractor.sampleTime
                                codec.queueInputBuffer(inputBufIndex, 0, sampleSize, sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outputBufIndex = codec.dequeueOutputBuffer(info, kTimeOutUs)
                if (outputBufIndex >= 0) {
                    val outputBuf = codec.getOutputBuffer(outputBufIndex)
                    if (outputBuf != null && info.size > 0) {
                        outputBuf.position(info.offset)
                        outputBuf.limit(info.offset + info.size)
                        val chunkBytes = ByteArray(info.size)
                        outputBuf.get(chunkBytes)
                        wavWriter.writePcmBytes(chunkBytes)
                    }
                    codec.releaseOutputBuffer(outputBufIndex, false)

                    if (durationUs > 0) {
                        val progress = (info.presentationTimeUs.toFloat() / durationUs.toFloat()).coerceIn(0f, 1f)
                        onProgress(progress)
                    }

                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isOutputEOS = true
                    }
                }
            }

            onProgress(1.0f)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw StemFlowException(FailureCategory.DECODER, "MediaCodec decode failure: ${e.message}", e)
        } finally {
            wavWriter?.close()
            codec?.stop()
            codec?.release()
            extractor.release()
        }

        return metadata.copy(sampleRate = sampleRate, channels = channels)
    }
}
