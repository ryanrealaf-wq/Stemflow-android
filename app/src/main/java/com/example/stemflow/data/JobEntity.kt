package com.example.stemflow.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "stemflow_jobs")
data class JobEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val sourceUri: String,
    val sampleRate: Int,
    val channels: Int,
    val durationMs: Long,
    val phase: String, // QUEUED, DECODING, SEPARATING, TRANSCRIBING, PACKAGING, COMPLETE, FAILED, CANCELLED
    val progress: Float, // 0.0f to 1.0f
    val currentStem: String? = null,
    val peakRamMb: Float = 0f,
    val processingTimeMs: Long = 0,
    val errorCategory: String? = null,
    val errorMessage: String? = null,
    val jobDir: String,
    val zipPath: String? = null,
    val analysisJson: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
