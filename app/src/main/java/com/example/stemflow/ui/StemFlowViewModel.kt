package com.example.stemflow.ui

import android.app.Application
import android.media.MediaPlayer
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.stemflow.audio.AudioDecoder
import com.example.stemflow.data.AppDatabase
import com.example.stemflow.data.JobEntity
import com.example.stemflow.data.JobRepository
import com.example.stemflow.model.AudioMetadata
import com.example.stemflow.model.BenchmarkResult
import com.example.stemflow.model.MidiNote
import com.example.stemflow.model.StemFlowAnalysis
import com.example.stemflow.model.StemType
import com.example.stemflow.pipeline.BenchmarkSuite
import com.example.stemflow.pipeline.StemFlowPipeline
import com.example.stemflow.separator.ModelManager
import com.example.stemflow.transcriber.BasicPitchEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

class StemFlowViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    val repository = JobRepository(application, db.jobDao())

    val allJobs: StateFlow<List<JobEntity>> = repository.allJobs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedMetadata = MutableStateFlow<AudioMetadata?>(null)
    val selectedMetadata: StateFlow<AudioMetadata?> = _selectedMetadata.asStateFlow()

    private val _activeJob = MutableStateFlow<JobEntity?>(null)
    val activeJob: StateFlow<JobEntity?> = _activeJob.asStateFlow()

    private val _currentPhase = MutableStateFlow("IDLE")
    val currentPhase: StateFlow<String> = _currentPhase.asStateFlow()

    private val _currentProgress = MutableStateFlow(0f)
    val currentProgress: StateFlow<Float> = _currentProgress.asStateFlow()

    private val _livePeakRamMb = MutableStateFlow(0f)
    val livePeakRamMb: StateFlow<Float> = _livePeakRamMb.asStateFlow()

    private val _selectedStem = MutableStateFlow(StemType.VOCALS)
    val selectedStem: StateFlow<StemType> = _selectedStem.asStateFlow()

    private val _activeTab = MutableStateFlow(0)
    val activeTab: StateFlow<Int> = _activeTab.asStateFlow()

    private val _benchmarkResult = MutableStateFlow<BenchmarkResult?>(null)
    val benchmarkResult: StateFlow<BenchmarkResult?> = _benchmarkResult.asStateFlow()

    private val _isBenchmarkRunning = MutableStateFlow(false)
    val isBenchmarkRunning: StateFlow<Boolean> = _isBenchmarkRunning.asStateFlow()

    private val _analysisData = MutableStateFlow<StemFlowAnalysis?>(null)
    val analysisData: StateFlow<StemFlowAnalysis?> = _analysisData.asStateFlow()

    private val _modelsList = MutableStateFlow<List<ModelManager.ModelInfo>>(emptyList())
    val modelsList: StateFlow<List<ModelManager.ModelInfo>> = _modelsList.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playingStem = MutableStateFlow<StemType?>(null)
    val playingStem: StateFlow<StemType?> = _playingStem.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var pipelineJob: Job? = null

    init {
        refreshModels()
        // Check if there is an active job in database on launch
        viewModelScope.launch {
            val existing = repository.getActiveJob()
            if (existing != null) {
                _activeJob.value = existing
                _currentPhase.value = existing.phase
                _currentProgress.value = existing.progress
            }
        }
    }

    fun setTab(tab: Int) {
        _activeTab.value = tab
    }

    fun selectStem(stem: StemType) {
        _selectedStem.value = stem
    }

    fun refreshModels() {
        _modelsList.value = ModelManager.listModels(getApplication())
    }

    fun onAudioSelected(uri: Uri) {
        viewModelScope.launch {
            try {
                val meta = AudioDecoder.extractMetadata(getApplication(), uri)
                _selectedMetadata.value = meta
            } catch (e: Exception) {
                _selectedMetadata.value = AudioMetadata(
                    uriString = uri.toString(),
                    fileName = "selected_track.wav",
                    mimeType = "audio/wav",
                    sampleRate = 44100,
                    channels = 2,
                    durationMs = 30000,
                    sizeBytes = 1024 * 1024
                )
            }
        }
    }

    fun loadBuiltinFixture() {
        _selectedMetadata.value = AudioMetadata(
            uriString = "builtin://demo_track.wav",
            fileName = "StemFlow_Demo_Composite.wav",
            mimeType = "audio/wav",
            sampleRate = 44100,
            channels = 2,
            durationMs = 4000,
            sizeBytes = 705600
        )
    }

    fun startPipeline() {
        val meta = _selectedMetadata.value ?: return
        pipelineJob?.cancel()

        pipelineJob = viewModelScope.launch {
            try {
                _currentPhase.value = "INITIALIZING"
                _currentProgress.value = 0.02f

                val newJob = repository.createJob(
                    name = meta.fileName,
                    sourceUri = meta.uriString,
                    sampleRate = meta.sampleRate,
                    channels = meta.channels,
                    durationMs = meta.durationMs
                )
                _activeJob.value = newJob

                val completed = StemFlowPipeline.executeJob(
                    context = getApplication(),
                    jobRepository = repository,
                    jobId = newJob.id
                ) { phase, progress, peakRam ->
                    _currentPhase.value = phase
                    _currentProgress.value = progress
                    _livePeakRamMb.value = peakRam
                }

                _activeJob.value = completed
                _currentPhase.value = "COMPLETE"
                _currentProgress.value = 1.0f

                // Parse analysis JSON if available
                if (completed.analysisJson != null) {
                    parseAnalysis(completed.analysisJson, completed.name, completed.durationMs)
                }

                _activeTab.value = 1 // Switch to Stems view
            } catch (e: Exception) {
                _currentPhase.value = "FAILED: ${e.message}"
            }
        }
    }

    fun cancelPipeline() {
        pipelineJob?.cancel()
        viewModelScope.launch {
            repository.cancelJobs()
            _currentPhase.value = "CANCELLED"
            _activeJob.value = _activeJob.value?.copy(phase = "CANCELLED")
        }
    }

    fun runAccuracyBenchmark() {
        viewModelScope.launch {
            _isBenchmarkRunning.value = true
            try {
                val res = BenchmarkSuite.runAccuracyBenchmark(getApplication())
                _benchmarkResult.value = res
            } catch (e: Exception) {
                // handle gracefully
            } finally {
                _isBenchmarkRunning.value = false
            }
        }
    }

    fun togglePlayback(stem: StemType? = null) {
        val job = _activeJob.value ?: return
        val targetFile = if (stem != null) {
            repository.getStemWavFile(job.jobDir, stem)
        } else {
            File(File(job.jobDir), "source_decoded.wav")
        }

        if (!targetFile.exists()) return

        if (_isPlaying.value && _playingStem.value == stem) {
            stopPlayback()
            return
        }

        stopPlayback()
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(targetFile.absolutePath)
                prepare()
                start()
                setOnCompletionListener {
                    stopPlayback()
                }
            }
            _isPlaying.value = true
            _playingStem.value = stem
        } catch (e: Exception) {
            stopPlayback()
        }
    }

    fun stopPlayback() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
        _isPlaying.value = false
        _playingStem.value = null
    }

    private fun parseAnalysis(jsonStr: String, fileName: String, durationMs: Long) {
        try {
            val root = JSONObject(jsonStr)
            val analytics = root.optJSONObject("analytics")?.optJSONObject("interplay_analytics")
            if (analytics != null) {
                val lockScore = analytics.optDouble("rhythmic_lock_score", 0.89).toFloat()
                val vocalMaskingObj = analytics.optJSONObject("vocal_masking_pct")
                val vocalMasking = mutableMapOf<String, Float>()
                vocalMaskingObj?.keys()?.forEach { k ->
                    vocalMasking[k] = vocalMaskingObj.optDouble(k).toFloat()
                }

                val clashObj = analytics.optJSONObject("harmonic_clash_profile")
                val clash = mutableMapOf<String, Float>()
                clashObj?.keys()?.forEach { k ->
                    clash[k] = clashObj.optDouble(k).toFloat()
                }

                _analysisData.value = StemFlowAnalysis(
                    rhythmicLockScore = lockScore,
                    vocalMaskingPct = vocalMasking,
                    harmonicClashProfile = clash,
                    stemMetrics = emptyMap(),
                    sourceFileName = fileName,
                    totalDurationMs = durationMs
                )
            }
        } catch (_: Exception) {}
    }

    override fun onCleared() {
        super.onCleared()
        stopPlayback()
    }
}
