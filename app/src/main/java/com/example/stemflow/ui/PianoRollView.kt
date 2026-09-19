package com.example.stemflow.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.stemflow.model.MidiNote
import com.example.stemflow.model.StemType

@Composable
fun PianoRollView(
    notes: List<MidiNote>,
    stemType: StemType,
    modifier: Modifier = Modifier,
    totalDurationMs: Long = 4000L
) {
    if (notes.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF0F172A)),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            Text(
                text = "No notes detected for ${stemType.displayName}",
                color = Color(0xFF64748B),
                fontSize = 13.sp
            )
        }
        return
    }

    val minPitch = (notes.minOfOrNull { it.pitch } ?: 36).coerceAtLeast(0)
    val maxPitch = (notes.maxOfOrNull { it.pitch } ?: 72).coerceAtMost(127)
    val pitchSpan = maxOf(12, maxPitch - minPitch + 2)
    val duration = maxOf(totalDurationMs, notes.maxOfOrNull { it.endMs } ?: 1000L)

    val timeScalePxPerMs = 0.15f // 150px per second
    val totalWidthDp = (duration * timeScalePxPerMs).dp.coerceAtLeast(340.dp)
    val noteHeightDp = 10.dp
    val totalHeightDp = (pitchSpan * 10).dp.coerceIn(160.dp, 240.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0A0E17))
            .testTag("piano_roll_container")
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            // Piano Roll Canvas scrollable horizontally
            Box(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
            ) {
                Canvas(
                    modifier = Modifier
                        .width(totalWidthDp)
                        .height(totalHeightDp)
                        .testTag("piano_roll_canvas")
                ) {
                    val w = size.width
                    val h = size.height

                    // 1. Draw pitch horizontal grid lines
                    val rowHeight = h / pitchSpan
                    for (p in 0..pitchSpan) {
                        val y = p * rowHeight
                        val midi = maxPitch - p
                        val isBlackKey = isBlackMidiKey(midi)
                        if (isBlackKey) {
                            drawRect(
                                color = Color(0x18FFFFFF),
                                topLeft = Offset(0f, y),
                                size = Size(w, rowHeight)
                            )
                        }
                        drawLine(
                            color = Color(0x22334155),
                            start = Offset(0f, y),
                            end = Offset(w, y),
                            strokeWidth = 1f
                        )
                    }

                    // 2. Draw beat vertical grid lines (every 500ms = 120bpm quarter note)
                    val beatStepMs = 500f
                    var beatTime = 0f
                    while (beatTime <= duration) {
                        val x = (beatTime / duration) * w
                        drawLine(
                            color = Color(0x28475569),
                            start = Offset(x, 0f),
                            end = Offset(x, h),
                            strokeWidth = 1f
                        )
                        beatTime += beatStepMs
                    }

                    // 3. Draw note blocks
                    for (note in notes) {
                        val pitchIndex = maxPitch - note.pitch
                        if (pitchIndex in 0 until pitchSpan) {
                            val startX = (note.startMs.toFloat() / duration) * w
                            val noteW = maxOf(4f, (note.durationMs.toFloat() / duration) * w)
                            val noteY = pitchIndex * rowHeight + 1f
                            val noteH = rowHeight - 2f

                            val alpha = (note.velocity / 127f).coerceIn(0.4f, 1.0f)
                            val noteColor = stemType.color.copy(alpha = alpha)

                            drawRoundRect(
                                color = noteColor,
                                topLeft = Offset(startX, noteY),
                                size = Size(noteW, noteH),
                                cornerRadius = CornerRadius(3f, 3f)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun isBlackMidiKey(pitch: Int): Boolean {
    val noteInOctave = pitch % 12
    return noteInOctave == 1 || noteInOctave == 3 || noteInOctave == 6 || noteInOctave == 8 || noteInOctave == 10
}
