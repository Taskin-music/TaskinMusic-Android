/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalDownloadUtil
import com.metrolist.music.R
import com.metrolist.music.db.entities.Song
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.utils.AudioConverter
import com.metrolist.music.utils.AudioTargetFormat
import kotlinx.coroutines.launch

@Composable
fun FormatConverterDialog(
    song: Song,
    onDismiss: () -> Unit,
) {
    FormatConverterDialog(
        mediaId = song.id,
        title = song.song.title,
        artist = song.artists.joinToString(", ") { it.name },
        onDismiss = onDismiss,
    )
}

@Composable
fun FormatConverterDialog(
    songItem: SongItem,
    onDismiss: () -> Unit,
) {
    FormatConverterDialog(
        mediaId = songItem.id,
        title = songItem.title,
        artist = songItem.artists.joinToString(", ") { it.name },
        onDismiss = onDismiss,
    )
}

@Composable
fun FormatConverterDialog(
    mediaMetadata: MediaMetadata,
    onDismiss: () -> Unit,
) {
    FormatConverterDialog(
        mediaId = mediaMetadata.id,
        title = mediaMetadata.title,
        artist = mediaMetadata.artists.joinToString(", ") { it.name },
        onDismiss = onDismiss,
    )
}

@Composable
fun FormatConverterDialog(
    mediaId: String,
    title: String,
    artist: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val downloadUtil = LocalDownloadUtil.current
    val coroutineScope = rememberCoroutineScope()

    var selectedFormat by remember { mutableStateOf(AudioTargetFormat.FLAC) }
    var addToLibrary by remember { mutableStateOf(true) }
    var isConverting by remember { mutableStateOf(false) }

    DefaultDialog(
        onDismiss = { if (!isConverting) onDismiss() },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 600.dp),
        icon = {
            Icon(
                painter = painterResource(R.drawable.transform),
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            Text(
                text = stringResource(R.string.convert_dialog_title),
                fontWeight = FontWeight.Bold,
            )
        },
        buttons = {
            TextButton(
                onClick = onDismiss,
                enabled = !isConverting,
            ) {
                Text(text = stringResource(android.R.string.cancel))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    coroutineScope.launch {
                        isConverting = true
                        val result = AudioConverter.convertAudio(
                            context = context,
                            mediaId = mediaId,
                            targetFormat = selectedFormat,
                            addToLibrary = addToLibrary,
                            downloadCache = downloadUtil.downloadCache,
                            database = database,
                        )
                        isConverting = false

                        if (result.success) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.convert_success, selectedFormat.extension.uppercase()),
                                Toast.LENGTH_LONG,
                            ).show()
                            onDismiss()
                        } else {
                            Toast.makeText(
                                context,
                                context.getString(R.string.convert_failed, result.errorMessage ?: "Bilinmeyen hata"),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                },
                enabled = !isConverting,
            ) {
                if (isConverting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.convert_in_progress), fontSize = 12.sp)
                } else {
                    Icon(
                        painter = painterResource(R.drawable.transform),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.convert_start_btn),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Track Info Card
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Converting Progress Box with sliding blue loading bar
            if (isConverting) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                    border = BorderStroke(1.dp, Color(0xFF2196F3).copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFF2196F3),
                            )
                            Text(
                                text = stringResource(R.string.convert_in_progress),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }

                        // Mavi renkli soldan sağa kayan yüklenme barı
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = Color(0xFF2196F3),
                            trackColor = Color(0xFF2196F3).copy(alpha = 0.2f),
                        )
                    }
                }
            }

            Text(
                text = stringResource(R.string.convert_target_format),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )

            // Format List
            AudioTargetFormat.entries.forEach { format ->
                val isSelected = selectedFormat == format
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = !isConverting) { selectedFormat = format }
                        .then(
                            if (isSelected) {
                                Modifier.border(
                                    width = 1.5.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = RoundedCornerShape(12.dp),
                                )
                            } else {
                                Modifier
                            }
                        ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { if (!isConverting) selectedFormat = format },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(format.titleRes),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                )
                                if (format.isLossless) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.tertiaryContainer,
                                    ) {
                                        Text(
                                            text = "KAYIPSIZ",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                            }
                            Text(
                                text = stringResource(format.descRes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // Switch to add to Library
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.convert_add_to_library),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(
                    checked = addToLibrary,
                    onCheckedChange = { if (!isConverting) addToLibrary = it },
                )
            }
        }
    }
}
