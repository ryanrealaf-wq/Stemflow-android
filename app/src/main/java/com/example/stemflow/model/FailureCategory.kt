package com.example.stemflow.model

/**
 * Diagnostic failure categories required by Section 12: Failure Handling.
 * Every failure MUST identify its layer.
 */
enum class FailureCategory(val displayName: String) {
    INPUT("Input File Error"),
    DECODER("Audio Decoder Error"),
    RESAMPLER("Resampling Error"),
    SEPARATOR("Separation Engine Error"),
    MODEL("Model Validation Error"),
    ONNX_RUNTIME("ONNX Runtime Error"),
    TRANSCRIBER("Transcription Engine Error"),
    MIDI("MIDI Generation Error"),
    STORAGE("Storage & Disk Error"),
    ARCHIVE("Archive & Packaging Error"),
    DEVICE_RESOURCE("Device Resource Exceeded"),
    CANCELLATION("Process Cancelled")
}

class StemFlowException(
    val category: FailureCategory,
    message: String,
    cause: Throwable? = null
) : Exception("[$category] $message", cause)
