package com.downnet.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.downnet.R
import com.downnet.core.urlnorm.UrlNormalizer.Platform
import com.downnet.extract.MediaInfo
import com.downnet.extract.VariantPicker

/**
 * The quality picker: what lands in the gallery and how big it is. M1 rows
 * are muxed formats + one best-audio row; split-stream 1080p+ merging and
 * HLS arrive with the M2 downloader.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QualitySheet(
    info: MediaInfo,
    platform: Platform,
    video: List<VariantPicker.Choice>,
    audio: VariantPicker.Choice?,
    images: List<VariantPicker.Choice>,
    diagnostics: String?,
    onPick: (VariantPicker.Choice) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                info.title ?: "Media",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val byline = listOfNotNull(
                info.uploader,
                formatDuration(info.duration),
                platform.label,
            ).joinToString("  ·  ")
            if (byline.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    byline,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(20.dp))

            video.forEachIndexed { index, choice ->
                if (index > 0) Spacer(Modifier.height(4.dp))
                ChoiceRow(choice) { onPick(choice) }
            }

            if (images.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                HorizontalDivider()
                Spacer(Modifier.height(14.dp))
                images.forEach { choice ->
                    Spacer(Modifier.height(4.dp))
                    ChoiceRow(choice) { onPick(choice) }
                }
            }

            if (audio != null) {
                Spacer(Modifier.height(14.dp))
                HorizontalDivider()
                Spacer(Modifier.height(14.dp))
                ChoiceRow(audio) { onPick(audio) }
            }

            if (video.isEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text(
                    stringResource(R.string.only_audio_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            if (!diagnostics.isNullOrEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    diagnostics,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ChoiceRow(choice: VariantPicker.Choice, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    choice.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (choice.detail.isNotEmpty()) {
                    Text(
                        choice.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "↓",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private fun formatDuration(seconds: Double?): String? {
    val s = seconds?.toLong() ?: return null
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return when {
        h > 0 -> "%d:%02d:%02d".format(h, m, sec)
        else -> "%d:%02d".format(m, sec)
    }
}
