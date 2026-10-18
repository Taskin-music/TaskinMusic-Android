/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.withFrameMillis
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.db.entities.Song
import com.metrolist.music.db.entities.SongAlbumMap
import com.metrolist.music.db.entities.SongArtistMap
import com.metrolist.music.extensions.parseClipId
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.utils.TrendAudioHelper
import com.metrolist.music.utils.makeTimeString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.LocalDateTime
import kotlin.math.roundToInt

@Composable
fun TrimSongDialog(
    song: Song,
    onDismiss: () -> Unit,
    onSuccess: (() -> Unit)? = null,
) {
    TrimSongDialog(
        mediaMetadata = song.toMediaMetadata(),
        onDismiss = onDismiss,
        onSuccess = onSuccess,
    )
}

@Composable
fun TrimSongDialog(
    songItem: SongItem,
    onDismiss: () -> Unit,
    onSuccess: (() -> Unit)? = null,
) {
    TrimSongDialog(
        mediaMetadata = songItem.toMediaMetadata(),
        onDismiss = onDismiss,
        onSuccess = onSuccess,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrimSongDialog(
    mediaMetadata: MediaMetadata,
    onDismiss: () -> Unit,
    onSuccess: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current
    val coroutineScope = rememberCoroutineScope()

    val totalDurationSec = if (mediaMetadata.duration > 0) mediaMetadata.duration else 180
    val maxDuration = totalDurationSec.toFloat().coerceAtLeast(10f)

    var title by remember {
        mutableStateOf(TextFieldValue("${mediaMetadata.title} (Kırpılmış)"))
    }
    var startSec by remember { mutableFloatStateOf(0f) }
    var endSec by remember {
        mutableFloatStateOf(
            if (maxDuration > 60f) 60f else maxDuration
        )
    }

    var isSearchingTrend by remember { mutableStateOf(false) }
    var trendSourceInfo by remember { mutableStateOf<String?>(null) }
    var isPreviewPlaying by remember { mutableStateOf(false) }
    var isLoopEnabled by remember { mutableStateOf(false) }
    var currentPlaybackSec by remember { mutableFloatStateOf(startSec) }

    // Ensure preview stops on dialog dismiss
    DisposableEffect(Unit) {
        onDispose {
            if (isPreviewPlaying && playerConnection != null) {
                try {
                    playerConnection.player.pause()
                } catch (_: Exception) {}
            }
        }
    }

    LaunchedEffect(startSec) {
        if (!isPreviewPlaying) {
            currentPlaybackSec = startSec
        }
    }

    // Monitor preview playback to keep within [startSec, endSec] and stop exactly at endSec
    LaunchedEffect(isPreviewPlaying, startSec, endSec, isLoopEnabled) {
        if (!isPreviewPlaying || playerConnection == null) return@LaunchedEffect
        while (isActive && isPreviewPlaying) {
            withFrameMillis {
                try {
                    val player = playerConnection.player
                    val curMediaId = player.currentMediaItem?.mediaId
                    if (curMediaId == mediaMetadata.id) {
                        val currentPosMs = player.currentPosition
                        val endMs = (endSec * 1000).toLong()
                        val startMs = (startSec * 1000).toLong()

                        currentPlaybackSec = (currentPosMs / 1000f).coerceIn(0f, maxDuration)

                        // Stop immediately upon reaching the selected end position
                        if (currentPosMs >= endMs || player.playbackState == androidx.media3.common.Player.STATE_ENDED) {
                            if (isLoopEnabled) {
                                player.seekTo(startMs)
                                currentPlaybackSec = startSec
                            } else {
                                player.pause()
                                player.seekTo(startMs)
                                currentPlaybackSec = startSec
                                isPreviewPlaying = false
                            }
                        } else if (!player.playWhenReady && player.playbackState != androidx.media3.common.Player.STATE_BUFFERING) {
                            isPreviewPlaying = false
                        }
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Error checking preview position")
                }
            }
        }
    }

    val selectedDurationSec = (endSec - startSec).roundToInt().coerceAtLeast(0)

    DefaultDialog(
        onDismiss = onDismiss,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 640.dp),
        icon = {
            Icon(
                painter = painterResource(R.drawable.content_cut),
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            Text(
                text = stringResource(R.string.trim_dialog_title),
                fontWeight = FontWeight.Bold,
            )
        },
        buttons = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    val sMs = (startSec * 1000).toLong()
                    val eMs = (endSec * 1000).toLong()
                    if (sMs >= eMs) {
                        Toast.makeText(context, R.string.trim_save_error, Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    val baseId = if (mediaMetadata.id.startsWith("clip:")) {
                        parseClipId(mediaMetadata.id)?.originalId ?: mediaMetadata.id
                    } else {
                        mediaMetadata.id
                    }
                    val clipId = "clip:$baseId:$sMs:$eMs"
                    val trimmedMetadata = mediaMetadata.copy(
                        id = clipId,
                        title = title.text.trim().ifEmpty { "${mediaMetadata.title} (Kırpılmış)" },
                        duration = selectedDurationSec,
                    )
                    coroutineScope.launch(Dispatchers.IO) {
                        try {
                            val now = LocalDateTime.now()
                            val isLocalSource = baseId.startsWith("content://") || baseId.startsWith("file://")
                            database.withTransaction {
                                insert(trimmedMetadata) { entity ->
                                    entity.copy(
                                        inLibrary = now,
                                        isLocal = isLocalSource,
                                        isDownloaded = true,
                                        dateDownload = now,
                                    )
                                }
                                val existingArtistMaps = songArtistMap(baseId)
                                existingArtistMaps.forEach { map ->
                                    insert(SongArtistMap(songId = clipId, artistId = map.artistId, position = map.position))
                                }
                                mediaMetadata.album?.id?.let { albumId ->
                                    insert(SongAlbumMap(songId = clipId, albumId = albumId, index = 0))
                                }
                            }
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, R.string.trim_save_success, Toast.LENGTH_SHORT).show()
                                onSuccess?.invoke()
                                onDismiss()
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to save trimmed song copy")
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, R.string.trim_save_error, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                },
                enabled = selectedDurationSec > 0,
            ) {
                Text(text = stringResource(R.string.trim_save_to_library))
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Title Input Field
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(text = stringResource(R.string.trim_title_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // Time Info Display Bar
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.trim_start),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = makeTimeString((startSec * 1000).toLong()),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text(
                            text = stringResource(
                                R.string.trim_selected_duration,
                                makeTimeString((selectedDurationSec * 1000).toLong()),
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = stringResource(R.string.trim_end),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = makeTimeString((endSec * 1000).toLong()),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            // Material 3 Range Slider with dynamic playhead indicator
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                RangeSlider(
                    value = startSec..endSec,
                    onValueChange = { range ->
                        startSec = range.start.coerceIn(0f, maxDuration)
                        endSec = range.endInclusive.coerceIn(startSec + 1f, maxDuration)
                        if (!isPreviewPlaying) {
                            currentPlaybackSec = startSec
                        }
                    },
                    valueRange = 0f..maxDuration,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (isPreviewPlaying || currentPlaybackSec > startSec) {
                    val thumbRadius = 10.dp
                    val availableWidth = (maxWidth - (thumbRadius * 2)).coerceAtLeast(0.dp)
                    val playheadSec = currentPlaybackSec.coerceIn(startSec, endSec)
                    val fraction = if (maxDuration > 0f) (playheadSec / maxDuration).coerceIn(0f, 1f) else 0f
                    val playheadX = thumbRadius + (availableWidth * fraction)

                    Box(
                        modifier = Modifier
                            .offset(x = playheadX - 6.dp)
                            .size(width = 12.dp, height = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        // Playhead needle
                        Box(
                            modifier = Modifier
                                .size(width = 3.dp, height = 36.dp)
                                .clip(RoundedCornerShape(1.5.dp))
                                .background(MaterialTheme.colorScheme.tertiary)
                        )
                        // Playhead top cap indicator
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.tertiary)
                        )
                    }
                }
            }

            // Fine tuning buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(
                        onClick = { startSec = (startSec - 1f).coerceAtLeast(0f) },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(32.dp),
                    ) {
                        Text("-1s", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = { startSec = (startSec + 1f).coerceAtMost(endSec - 1f) },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(32.dp),
                    ) {
                        Text("+1s", fontSize = 12.sp)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(
                        onClick = { endSec = (endSec - 1f).coerceAtLeast(startSec + 1f) },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(32.dp),
                    ) {
                        Text("-1s", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = { endSec = (endSec + 1f).coerceAtMost(maxDuration) },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(32.dp),
                    ) {
                        Text("+1s", fontSize = 12.sp)
                    }
                }
            }

            // 🔥 Trend / Popüler Kısmı Al (TikTok / Shorts highlight)
            FilledTonalButton(
                onClick = {
                    coroutineScope.launch {
                        isSearchingTrend = true
                        try {
                            val result = TrendAudioHelper.findTrendingPart(
                                videoId = mediaMetadata.id,
                                totalDurationSec = totalDurationSec,
                                desiredClipDurationSec = 30,
                            )
                            startSec = result.startSec.toFloat().coerceIn(0f, maxDuration)
                            endSec = result.endSec.toFloat().coerceIn(startSec + 1f, maxDuration)
                            trendSourceInfo = result.sourceDescription
                            Toast.makeText(context, R.string.trim_trend_applied, Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Timber.w(e, "Trend highlight search failed")
                        } finally {
                            isSearchingTrend = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSearchingTrend,
            ) {
                if (isSearchingTrend) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.trim_searching_trend))
                } else {
                    Text(text = stringResource(R.string.trim_get_trend))
                }
            }

            trendSourceInfo?.let { info ->
                Text(
                    text = "✨ $info",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Preview & Loop Controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = {
                        if (playerConnection == null) return@OutlinedButton
                        if (isPreviewPlaying) {
                            playerConnection.player.pause()
                            isPreviewPlaying = false
                        } else {
                            val startMs = (startSec * 1000).toLong()
                            val curId = playerConnection.player.currentMediaItem?.mediaId
                            if (curId == mediaMetadata.id) {
                                playerConnection.player.seekTo(startMs)
                                playerConnection.player.play()
                            } else {
                                playerConnection.playQueue(
                                    ListQueue(
                                        title = mediaMetadata.title,
                                        items = listOf(mediaMetadata.toMediaItem()),
                                        position = startMs,
                                    )
                                )
                            }
                            currentPlaybackSec = startSec
                            isPreviewPlaying = true
                        }
                    },
                ) {
                    Icon(
                        painter = painterResource(if (isPreviewPlaying) R.drawable.pause else R.drawable.play),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(
                            if (isPreviewPlaying) R.string.trim_preview_pause else R.string.trim_preview_play
                        )
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.trim_loop_preview),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = isLoopEnabled,
                        onCheckedChange = { isLoopEnabled = it },
                    )
                }
            }
        }
    }
}
