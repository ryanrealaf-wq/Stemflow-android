package com.example.stemflow.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.stemflow.model.StemFlowAnalysis
import com.example.stemflow.model.StemType

@Composable
fun AnalyticsDashboard(
    analysis: StemFlowAnalysis,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Metric 1: Rhythmic Lock Score
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF15151E)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Circular Gauge
                Box(
                    modifier = Modifier.size(80.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val stroke = 8.dp.toPx()
                        drawArc(
                            color = Color(0xFF262635),
                            startAngle = 135f,
                            sweepAngle = 270f,
                            useCenter = false,
                            style = Stroke(stroke, cap = StrokeCap.Round)
                        )
                        val sweep = 270f * analysis.rhythmicLockScore
                        drawArc(
                            color = Color(0xFFEF4444),
                            startAngle = 135f,
                            sweepAngle = sweep,
                            useCenter = false,
                            style = Stroke(stroke, cap = StrokeCap.Round)
                        )
                    }
                    Text(
                        text = "%.1f%%".format(analysis.rhythmicLockScore * 100f),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column {
                    Text(
                        text = "Rhythmic Lock Score",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Correlates onset precision between Bass fundamentals and Kick transients. Score > 80% indicates tight groove coherence.",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
            }
        }

        // Metric 2: Vocal Masking Profile (% energy masking the vocal formants)
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF15151E)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Vocal Masking Profile",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                Text(
                    text = "Percentage of frequency overlap masking the Vocal formant band (300Hz - 3.5kHz)",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                val masking = analysis.vocalMaskingPct
                val stemKeys = listOf("drums", "bass", "guitar", "piano", "other")

                for (key in stemKeys) {
                    val pct = masking[key] ?: 15f
                    val stem = StemType.fromId(key) ?: StemType.OTHER

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stem.displayName,
                            color = stem.color,
                            fontSize = 12.sp,
                            modifier = Modifier.width(60.dp)
                        )

                        // Bar background
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF262635))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth((pct / 50f).coerceIn(0.05f, 1f))
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(stem.color)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Text(
                            text = "%.1f%%".format(pct),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.width(44.dp)
                        )
                    }
                }
            }
        }

        // Metric 3: Harmonic Clash Profile (12-TET Chromatic Distribution)
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF15151E)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Harmonic Clash Profile (12-TET)",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                Text(
                    text = "Normalized chromatic dissonance profile across pitch classes",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                val clash = analysis.harmonicClashProfile
                val notes = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                ) {
                    val barWidth = size.width / (notes.size * 1.5f)
                    val step = size.width / notes.size

                    for (i in notes.indices) {
                        val note = notes[i]
                        val score = clash[note] ?: 0.1f
                        val barHeight = size.height * score.coerceIn(0.05f, 1f)
                        val x = i * step + (step - barWidth) / 2f
                        val y = size.height - barHeight

                        val color = if (score > 0.4f) Color(0xFFEF4444) else Color(0xFFCBD5E1)

                        drawRoundRect(
                            color = color,
                            topLeft = Offset(x, y),
                            size = Size(barWidth, barHeight),
                            cornerRadius = CornerRadius(2f, 2f)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    for (note in notes) {
                        Text(
                            text = note,
                            color = Color(0xFF64748B),
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }
}
