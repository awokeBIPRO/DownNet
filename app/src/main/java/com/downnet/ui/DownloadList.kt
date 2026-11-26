package com.downnet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.downnet.core.urlnorm.UrlNormalizer.Platform
import com.downnet.download.DownloadRepository.Status
import com.downnet.download.DownloadRepository.DownloadItem
import com.downnet.extract.VariantPicker

/** The home feed: every card is one download, tinted by its platform. */
@Composable
fun DownloadList(items: List<DownloadItem>) {
    items.forEach { item ->
        Spacer(Modifier.height(8.dp))
        DownloadCard(item)
    }
}

@Composable
private fun DownloadCard(item: DownloadItem) {
    val accent = platformAccent(item.platform)
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(accent),
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    statusLabel(item),
                    style = MaterialTheme.typography.labelMedium,
                    color = when (item.status) {
                        Status.FAILED -> MaterialTheme.colorScheme.error
                        Status.DONE -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            if (item.status == Status.DOWNLOADING || item.status == Status.PUBLISHING) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { item.progress },
                    color = accent,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    downloadingDetail(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (item.status == Status.QUEUED) {
                Spacer(Modifier.height(6.dp))
                Text(
                    item.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (item.status == Status.FAILED) {
                Spacer(Modifier.height(6.dp))
                Text(
                    item.error ?: "Failed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun downloadingDetail(item: DownloadItem): String {
    val bytes = buildString {
        VariantPicker.formatBytes(item.bytesDone).let { append(it) }
        item.bytesTotal?.let { append(" / ${VariantPicker.formatBytes(it)}") }
        item.speed?.let { append("  ·  ${VariantPicker.formatBytes(it)}/s") }
    }
    return when (item.status) {
        Status.PUBLISHING -> "Saving to gallery…"
        else -> bytes.ifEmpty { "Downloading…" }
    }
}

private fun statusLabel(item: DownloadItem): String = when (item.status) {
    Status.QUEUED -> "Queued"
    Status.DOWNLOADING -> "${(item.progress * 100).toInt()}%"
    Status.PUBLISHING -> "Saving"
    Status.DONE -> "Done"
    Status.FAILED -> "Failed"
}

/** Platform brand accents on top of whatever Material You scheme is active. */
fun platformAccent(platform: Platform?): Color = when (platform) {
    Platform.YOUTUBE -> Color(0xFFFF3346)
    Platform.X -> Color(0xFFE7E9EA)
    Platform.INSTAGRAM -> Color(0xFFC86DD7)
    Platform.GENERIC, null -> Color(0xFF6FE0FF)
}
