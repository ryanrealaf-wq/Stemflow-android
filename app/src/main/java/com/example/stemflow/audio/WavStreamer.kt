package com.example.stemflow.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * High-performance streaming WAV writer.
 * Writes a placeholder 44-byte RIFF/WAVE header, streams PCM 16-bit samples directly to disk,
 * and fixes header sizes when closed. Bounded memory guarantee: buffers only small chunks (e.g. 4KB-16KB).
 */
class WavStreamWriter(
    private val targetFile: File,
    private val sampleRate: Int = 44100,
    private val numChannels: Int = 2
) : AutoCloseable {

    private val raf: RandomAccessFile = RandomAccessFile(targetFile, "rw")
    private var totalPcmBytesWritten: Long = 0L
    private val byteBuffer = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)

    init {
        // Prepare file and reserve 44-byte canonical WAV header
        raf.setLength(0)
        val dummyHeader = ByteArray(44)
        raf.write(dummyHeader)
    }

    /**
     * Writes an array of normalized float audio samples (-1.0f .. 1.0f) to disk.
     * Samples are interleaved if multi-channel.
     */
    fun writeSamples(floatSamples: FloatArray, offset: Int = 0, length: Int = floatSamples.size) {
        var i = offset
        val end = offset + length
        while (i < end) {
            byteBuffer.clear()
            val chunkLimit = minOf(end - i, byteBuffer.capacity() / 2)
            for (c in 0 until chunkLimit) {
                val clamped = floatSamples[i++].coerceIn(-1.0f, 1.0f)
                val pcm16 = (clamped * 32767f).toInt().toShort()
                byteBuffer.putShort(pcm16)
            }
            byteBuffer.flip()
            val bytesToWrite = byteBuffer.remaining()
            raf.write(byteBuffer.array(), 0, bytesToWrite)
            totalPcmBytesWritten += bytesToWrite
        }
    }

    /**
     * Writes raw 16-bit PCM byte chunk directly to disk.
     */
    fun writePcmBytes(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        raf.write(bytes, offset, length)
        totalPcmBytesWritten += length
    }

    override fun close() {
        try {
            // Rewind to 0 and write the canonical 44-byte RIFF/WAVE header
            raf.seek(0)
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            val totalDataLen = totalPcmBytesWritten
            val totalChunkLen = totalDataLen + 36
            val byteRate = sampleRate * numChannels * 2
            val blockAlign = numChannels * 2

            // RIFF chunk descriptor
            header.put('R'.code.toByte()).put('I'.code.toByte()).put('F'.code.toByte()).put('F'.code.toByte())
            header.putInt(totalChunkLen.toInt())
            header.put('W'.code.toByte()).put('A'.code.toByte()).put('V'.code.toByte()).put('E'.code.toByte())

            // "fmt " sub-chunk
            header.put('f'.code.toByte()).put('m'.code.toByte()).put('t'.code.toByte()).put(' '.code.toByte())
            header.putInt(16) // SubChunk1Size (16 for PCM)
            header.putShort(1.toShort()) // AudioFormat (1 for PCM)
            header.putShort(numChannels.toShort())
            header.putInt(sampleRate)
            header.putInt(byteRate)
            header.putShort(blockAlign.toShort())
            header.putShort(16.toShort()) // BitsPerSample

            // "data" sub-chunk
            header.put('d'.code.toByte()).put('a'.code.toByte()).put('t'.code.toByte()).put('a'.code.toByte())
            header.putInt(totalDataLen.toInt())

            raf.write(header.array())
        } finally {
            raf.close()
        }
    }
}

/**
 * Streaming WAV reader for bounded-memory processing.
 * Reads small windows of audio without materializing the whole file in RAM.
 */
class WavStreamReader(private val wavFile: File) : AutoCloseable {
    private val raf: RandomAccessFile = RandomAccessFile(wavFile, "r")
    val sampleRate: Int
    val numChannels: Int
    val bitsPerSample: Int
    val totalSamplesPerChannel: Long
    private val dataOffset: Long

    init {
        val header = ByteArray(44)
        raf.readFully(header)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        // Validate RIFF header
        val riff = String(header, 0, 4)
        if (riff != "RIFF") {
            throw IllegalArgumentException("Not a valid RIFF file: $riff")
        }
        numChannels = buf.getShort(22).toInt()
        sampleRate = buf.getInt(24)
        bitsPerSample = buf.getShort(34).toInt()

        // Locate "data" chunk
        dataOffset = 44L
        val dataLength = buf.getInt(40).toLong() and 0xFFFFFFFFL
        val bytesPerSample = (bitsPerSample / 8) * numChannels
        totalSamplesPerChannel = if (bytesPerSample > 0) dataLength / bytesPerSample else 0L
    }

    /**
     * Reads up to [maxSamplesPerChannel] into [outBuffer] starting from [sampleOffset].
     * Returns the actual number of samples read per channel.
     * Output buffer format is mono or interleaved stereo normalized float (-1.0f .. 1.0f).
     */
    fun readWindow(sampleOffset: Long, maxSamplesPerChannel: Int, outBuffer: FloatArray): Int {
        val bytesPerSample = (bitsPerSample / 8) * numChannels
        val seekPos = dataOffset + sampleOffset * bytesPerSample
        if (seekPos >= raf.length()) return 0

        raf.seek(seekPos)
        val samplesToRead = minOf(maxSamplesPerChannel.toLong(), totalSamplesPerChannel - sampleOffset).toInt()
        if (samplesToRead <= 0) return 0

        val byteCount = samplesToRead * bytesPerSample
        val rawBytes = ByteArray(byteCount)
        val bytesActuallyRead = raf.read(rawBytes)
        if (bytesActuallyRead <= 0) return 0

        val bb = ByteBuffer.wrap(rawBytes, 0, bytesActuallyRead).order(ByteOrder.LITTLE_ENDIAN)
        val totalFloats = (bytesActuallyRead / (bitsPerSample / 8))
        for (i in 0 until minOf(totalFloats, outBuffer.size)) {
            val sampleShort = bb.short
            outBuffer[i] = sampleShort / 32768.0f
        }
        return samplesToRead
    }

    override fun close() {
        raf.close()
    }
}
