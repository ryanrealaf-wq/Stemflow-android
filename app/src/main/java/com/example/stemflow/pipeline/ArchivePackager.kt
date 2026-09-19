package com.example.stemflow.pipeline

import com.example.stemflow.model.FailureCategory
import com.example.stemflow.model.StemFlowException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ArchivePackager {

    /**
     * Bundles all stem WAV files, MIDI files, and analysis JSON into a ZIP file.
     * Uses streaming ZipOutputStream with a small 8KB buffer to respect bounded RAM mandates.
     */
    fun createProductionBundle(
        stemsDir: File,
        midiDir: File,
        analysisJsonFile: File,
        targetZipFile: File,
        onProgress: (Float) -> Unit = {}
    ): File {
        if (targetZipFile.exists()) targetZipFile.delete()

        val filesToZip = mutableListOf<File>()
        stemsDir.listFiles()?.forEach { if (it.isFile && it.name.endsWith(".wav")) filesToZip.add(it) }
        midiDir.listFiles()?.forEach { if (it.isFile && it.name.endsWith(".mid")) filesToZip.add(it) }
        if (analysisJsonFile.exists()) filesToZip.add(analysisJsonFile)

        if (filesToZip.isEmpty()) {
            throw StemFlowException(FailureCategory.ARCHIVE, "No files found to bundle into production ZIP.")
        }

        val buffer = ByteArray(8192)
        var totalBytes = filesToZip.sumOf { it.length() }
        var bytesProcessed = 0L

        try {
            ZipOutputStream(FileOutputStream(targetZipFile)).use { zos ->
                for (file in filesToZip) {
                    val entryName = file.name
                    zos.putNextEntry(ZipEntry(entryName))
                    FileInputStream(file).use { fis ->
                        var length: Int
                        while (fis.read(buffer).also { length = it } > 0) {
                            zos.write(buffer, 0, length)
                            bytesProcessed += length
                            if (totalBytes > 0) {
                                onProgress((bytesProcessed.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f))
                            }
                        }
                    }
                    zos.closeEntry()
                }
            }
            onProgress(1.0f)
            return targetZipFile
        } catch (e: Exception) {
            throw StemFlowException(FailureCategory.ARCHIVE, "Failed to create ZIP bundle: ${e.message}", e)
        }
    }
}
