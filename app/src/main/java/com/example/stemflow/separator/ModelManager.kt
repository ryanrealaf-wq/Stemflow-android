package com.example.stemflow.separator

import android.content.Context
import com.example.stemflow.model.FailureCategory
import com.example.stemflow.model.StemFlowException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object ModelManager {

    data class ModelInfo(
        val id: String,
        val displayName: String,
        val targetFileName: String,
        val expectedSizeBytes: Long,
        val isInstalled: Boolean,
        val isVerified: Boolean,
        val localPath: String?
    )

    fun getModelsDirectory(context: Context): File {
        return File(context.filesDir, "models").apply { mkdirs() }
    }

    fun listModels(context: Context): List<ModelInfo> {
        val modelsDir = getModelsDirectory(context)

        val htdemucsFile = File(modelsDir, "htdemucs_6s.onnx")
        val basicPitchFile = File(modelsDir, "basic_pitch.onnx")

        val isHtInstalled = htdemucsFile.exists() && htdemucsFile.length() > 0
        val isBpInstalled = basicPitchFile.exists() && basicPitchFile.length() > 0

        return listOf(
            ModelInfo(
                id = "htdemucs_6s",
                displayName = "HTDemucs 6s Neural Separator",
                targetFileName = "htdemucs_6s.onnx",
                expectedSizeBytes = 48_500_000L,
                isInstalled = isHtInstalled,
                isVerified = isHtInstalled && htdemucsFile.length() > 1024,
                localPath = if (isHtInstalled) htdemucsFile.absolutePath else null
            ),
            ModelInfo(
                id = "basic_pitch",
                displayName = "Spotify Basic Pitch Polyphonic Model",
                targetFileName = "basic_pitch.onnx",
                expectedSizeBytes = 18_200_000L,
                isInstalled = isBpInstalled,
                isVerified = isBpInstalled && basicPitchFile.length() > 1024,
                localPath = if (isBpInstalled) basicPitchFile.absolutePath else null
            )
        )
    }

    suspend fun downloadModel(
        context: Context,
        modelId: String,
        downloadUrl: String,
        onProgress: (Float) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        val modelsDir = getModelsDirectory(context)
        val targetName = if (modelId == "htdemucs_6s") "htdemucs_6s.onnx" else "basic_pitch.onnx"
        val tempFile = File(modelsDir, "$targetName.download")
        val finalFile = File(modelsDir, targetName)

        var connection: HttpURLConnection? = null
        try {
            val url = URL(downloadUrl)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.connect()

            val fileLength = connection.contentLengthLong
            val input = connection.inputStream
            val output = FileOutputStream(tempFile)

            val data = ByteArray(8192)
            var total: Long = 0
            var count: Int
            while (input.read(data).also { count = it } != -1) {
                total += count
                if (fileLength > 0) {
                    onProgress((total.toFloat() / fileLength.toFloat()).coerceIn(0f, 1f))
                }
                output.write(data, 0, count)
            }
            output.flush()
            output.close()
            input.close()

            if (tempFile.renameTo(finalFile)) {
                onProgress(1.0f)
                return@withContext finalFile
            } else {
                throw StemFlowException(FailureCategory.STORAGE, "Failed to move temporary download to model file.")
            }
        } catch (e: Exception) {
            tempFile.delete()
            throw StemFlowException(FailureCategory.MODEL, "Model download failed: ${e.message}", e)
        } finally {
            connection?.disconnect()
        }
    }
}
