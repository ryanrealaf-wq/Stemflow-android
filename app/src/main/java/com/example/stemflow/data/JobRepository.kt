package com.example.stemflow.data

import android.content.Context
import com.example.stemflow.model.StemType
import kotlinx.coroutines.flow.Flow
import java.io.File

class JobRepository(
    private val context: Context,
    private val jobDao: JobDao
) {
    val allJobs: Flow<List<JobEntity>> = jobDao.getAllJobs()

    suspend fun getJobById(id: Long): JobEntity? = jobDao.getJobById(id)

    suspend fun getActiveJob(): JobEntity? = jobDao.getActiveJob()

    suspend fun createJob(
        name: String,
        sourceUri: String,
        sampleRate: Int,
        channels: Int,
        durationMs: Long
    ): JobEntity {
        val jobDir = File(context.filesDir, "job_${System.currentTimeMillis()}").apply { mkdirs() }
        val entity = JobEntity(
            name = name,
            sourceUri = sourceUri,
            sampleRate = sampleRate,
            channels = channels,
            durationMs = durationMs,
            phase = "QUEUED",
            progress = 0f,
            jobDir = jobDir.absolutePath
        )
        val id = jobDao.insertJob(entity)
        return entity.copy(id = id)
    }

    suspend fun updateJob(job: JobEntity) = jobDao.updateJob(job)

    suspend fun cancelJobs() {
        jobDao.cancelActiveJobs()
    }

    suspend fun deleteJob(id: Long) {
        val job = jobDao.getJobById(id)
        if (job != null) {
            File(job.jobDir).deleteRecursively()
            jobDao.deleteJobById(id)
        }
    }

    fun getStemWavFile(jobDir: String, stemType: StemType): File {
        return File(File(jobDir, "stems"), "${stemType.id}.wav")
    }

    fun getStemMidiFile(jobDir: String, stemType: StemType): File {
        return File(File(jobDir, "midi"), "${stemType.id}.mid")
    }
}
