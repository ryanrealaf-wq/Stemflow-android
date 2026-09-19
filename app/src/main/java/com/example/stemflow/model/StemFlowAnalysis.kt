package com.example.stemflow.model

data class StemFlowAnalysis(
    val rhythmicLockScore: Float,
    val vocalMaskingPct: Map<String, Float>,
    val harmonicClashProfile: Map<String, Float>,
    val stemMetrics: Map<String, StemMetric>,
    val sourceFileName: String,
    val totalDurationMs: Long,
    val processedTimestamp: Long = System.currentTimeMillis()
)

data class StemMetric(
    val rmsLevelDb: Float,
    val snrDb: Float,
    val estimatedBleedPct: Float,
    val noteCount: Int,
    val averageVelocity: Float,
    val pitchRange: String
)

data class BenchmarkResult(
    val testName: String,
    val timestamp: Long = System.currentTimeMillis(),
    // Separation accuracy metrics
    val sourceReconstructionErrorRms: Float,
    val signalToNoiseRatioDb: Float,
    val residualBleedPct: Float,
    val timingAlignmentMs: Float,
    // MIDI transcription metrics
    val notePrecision: Float,
    val noteRecall: Float,
    val noteF1: Float,
    val onsetTimingErrorMs: Float,
    val pitchAccuracyPct: Float,
    val velocityCorrelation: Float,
    // Device metrics
    val peakRamMb: Float,
    val processingTimeMs: Long,
    val modelLoadTimeMs: Long,
    val memoryLeakDetected: Boolean
)
