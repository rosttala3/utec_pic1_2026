package uy.edu.utec.pic1.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun formatDuration(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0)
    val d = s / 86_400; val h = (s % 86_400) / 3600; val m = (s % 3600) / 60
    return when {
        d > 0 -> "$d d ${h} h ${m} min"
        h > 0 -> "$h h $m min"
        m > 0 -> "$m min ${s % 60} s"
        else -> "$s s"
    }
}

private val dateTimeFmt = DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm", Locale("es", "UY"))
private val timeFmt = DateTimeFormatter.ofPattern("HH:mm", Locale("es", "UY"))
fun formatDateTime(ms: Long): String = dateTimeFmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
fun formatTime(ms: Long): String = timeFmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

fun fmt(v: Double, decimals: Int = 1): String =
    if (v.isNaN()) "—" else String.format(Locale("es", "UY"), "%.${decimals}f", v)

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = modifier)

/** Gráfico de línea simple. [points] son pares (x, y); se submuestrea para dibujar. */
@Composable
fun LineChart(
    title: String,
    unit: String,
    points: List<Pair<Double, Double>>,
    color: Color,
    modifier: Modifier = Modifier,
    decimals: Int = 1,
) {
    val data = if (points.size > 500) points.filterIndexed { i, _ -> i % (points.size / 500 + 1) == 0 } else points
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            if (data.isNotEmpty()) {
                Text(
                    "${fmt(data.minOf { it.second }, decimals)} – ${fmt(data.maxOf { it.second }, decimals)} $unit",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val grid = MaterialTheme.colorScheme.outline.copy(alpha = .35f)
        Box(Modifier.fillMaxWidth().height(110.dp)) {
            Canvas(Modifier.fillMaxWidth().height(110.dp)) {
                drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height))
                drawLine(grid, Offset(0f, 0f), Offset(0f, size.height))
                if (data.size < 2) return@Canvas
                val x0 = data.first().first; val x1 = data.last().first
                val lo = data.minOf { it.second }; val hi = data.maxOf { it.second }
                val span = (hi - lo).takeIf { it > 1e-9 } ?: 1.0
                val path = Path()
                data.forEachIndexed { i, (x, y) ->
                    val px = ((x - x0) / (x1 - x0).coerceAtLeast(1e-9) * size.width).toFloat()
                    val py = (size.height - 6f - ((y - lo) / span * (size.height - 12f))).toFloat()
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                drawPath(path, color, style = Stroke(width = 3f))
            }
            if (data.size < 2) Text(
                "Sin datos todavía", Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
