/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import com.metrolist.music.LocalDownloadUtil
import com.metrolist.music.R
import com.metrolist.music.constants.SongSortType
import com.metrolist.music.db.entities.Song
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.utils.SongMerger
import com.metrolist.music.utils.makeTimeString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@JvmName("MergeSongsDialogFromSongs")
@Composable
fun MergeSongsDialog(
    initialSongs: List<Song>,
    onDismiss: () -> Unit,
    onSuccess: (() -> Unit)? = null,
) {
    MergeSongsDialog(
        initialMetadataList = initialSongs.map { it.toMediaMetadata() },
        onDismiss = onDismiss,
        onSuccess = onSuccess,
    )
}

@Composable
fun MergeSongsDialog(
    singleSong: Song,
    onDismiss: () -> Unit,
    onSuccess: (() -> Unit)? = null,
) {
    MergeSongsDialog(
        initialMetadataList = listOf(singleSong.toMediaMetadata()),
        onDismiss = onDismiss,
        onSuccess = onSuccess,
    )
}

@Composable
fun MergeSongsDialog(
    songItem: SongItem,
    onDismiss: () -> Unit,
    onSuccess: (() -> Unit)? = null,
) {
    MergeSongsDialog(
        initialMetadataList = listOf(songItem.toMediaMetadata()),
        onDismiss = onDismiss,
        onSuccess = onSuccess,
    )
}

@Composable
fun MergeSongsDialog(
    initialMetadataList: List<MediaMetadata>,
    onDismiss: () -> Unit,
    onSuccess: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val downloadUtil = LocalDownloadUtil.current
    val coroutineScope = rememberCoroutineScope()

    val songList = remember {
        mutableStateListOf<MediaMetadata>().apply {
            addAll(initialMetadataList)
        }
    }

    val defaultTitle = remember(songList.toList()) {
        when {
            songList.size >= 2 -> "${songList[0].title} + ${songList[1].title}"
            songList.size == 1 -> "${songList[0].title} (Birleştirilmiş)"
            else -> "Birleştirilmiş Parça"
        }
    }

    var title by remember { mutableStateOf(TextFieldValue(defaultTitle)) }
    var isUserEditedTitle by remember { mutableStateOf(false) }
    var isMerging by remember { mutableStateOf(false) }
    var showSongPicker by remember { mutableStateOf(false) }

    // Update default title if song list changed and user hasn't typed their own title
    if (!isUserEditedTitle && songList.isNotEmpty()) {
        val newDefault = when {
            songList.size >= 2 -> "${songList[0].title} + ${songList[1].title}"
            songList.size == 1 -> "${songList[0].title} (Birleştirilmiş)"
            else -> "Birleştirilmiş Parça"
        }
        if (title.text != newDefault) {
            title = TextFieldValue(newDefault)
        }
    }

    val totalDurationSec = songList.sumOf { it.duration.toLong() }

    DefaultDialog(
        onDismiss = { if (!isMerging) onDismiss() },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 680.dp),
        icon = {
            Icon(
                painter = painterResource(R.drawable.queue_music),
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            Text(
                text = stringResource(R.string.merge_dialog_title),
                fontWeight = FontWeight.Bold,
            )
        },
        buttons = {
            TextButton(
                onClick = onDismiss,
                enabled = !isMerging,
            ) {
                Text(text = stringResource(android.R.string.cancel))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    if (songList.size < 2) {
                        Toast.makeText(context, R.string.merge_min_two_songs, Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    isMerging = true
                    coroutineScope.launch {
                        val result = SongMerger.mergeSongs(
                            context = context,
                            songs = songList.toList(),
                            customTitle = title.text,
                            downloadCache = downloadUtil.downloadCache,
                            database = database,
                        )
                        isMerging = false

                        if (result.success) {
                            Toast.makeText(
                                context,
                                R.string.merge_success,
                                Toast.LENGTH_LONG,
                            ).show()
                            onSuccess?.invoke()
                            onDismiss()
                        } else {
                            Toast.makeText(
                                context,
                                context.getString(R.string.merge_failed, result.errorMessage ?: "Bilinmeyen hata"),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                },
                enabled = !isMerging && songList.size >= 2,
            ) {
                if (isMerging) {
                    Text(
                        text = stringResource(R.string.merge_in_progress),
                        fontSize = 12.sp,
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.queue_music),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.merge_start_btn),
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Track title input field
            OutlinedTextField(
                value = title,
                onValueChange = {
                    title = it
                    isUserEditedTitle = true
                },
                label = { Text(text = stringResource(R.string.merge_title_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isMerging,
            )

            // Section header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.merge_song_order),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(
                        R.string.merge_total_duration,
                        makeTimeString(totalDurationSec * 1000L),
                        songList.size,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Song List with Reordering (Up/Down) & Remove
            songList.forEachIndexed { index, song ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Order badge
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "${index + 1}",
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        // Title & Artist
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = song.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${song.artists.joinToString(", ") { it.name }} • ${makeTimeString(song.duration * 1000L)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        // Reorder controls: Move Up
                        IconButton(
                            onClick = {
                                if (index > 0) {
                                    val item = songList.removeAt(index)
                                    songList.add(index - 1, item)
                                }
                            },
                            enabled = !isMerging && index > 0,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Text(text = "▲", fontSize = 13.sp)
                        }

                        // Reorder controls: Move Down
                        IconButton(
                            onClick = {
                                if (index < songList.size - 1) {
                                    val item = songList.removeAt(index)
                                    songList.add(index + 1, item)
                                }
                            },
                            enabled = !isMerging && index < songList.size - 1,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Text(text = "▼", fontSize = 13.sp)
                        }

                        // Remove button
                        IconButton(
                            onClick = {
                                if (!isMerging) {
                                    songList.removeAt(index)
                                }
                            },
                            enabled = !isMerging,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Text(text = "✕", fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            // "+ Şarkı Ekle" button
            OutlinedButton(
                onClick = { showSongPicker = true },
                enabled = !isMerging,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    painter = painterResource(R.drawable.add),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = stringResource(R.string.merge_add_song))
            }

            // Merging Progress Box with sliding blue loading bar
            if (isMerging) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color(0xFF2196F3).copy(alpha = 0.6f)),
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
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = androidx.compose.ui.graphics.Color(0xFF2196F3),
                            )
                            Text(
                                text = stringResource(R.string.merge_in_progress),
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
                            color = androidx.compose.ui.graphics.Color(0xFF2196F3),
                            trackColor = androidx.compose.ui.graphics.Color(0xFF2196F3).copy(alpha = 0.2f),
                        )
                    }
                }
            }
        }
    }

    // Picker Dialog for adding more songs from library
    if (showSongPicker) {
        MergeSongPickerDialog(
            onDismiss = { showSongPicker = false },
            onSongSelected = { pickedSong ->
                songList.add(pickedSong)
                showSongPicker = false
            },
        )
    }
}

@Composable
fun MergeSongPickerDialog(
    onDismiss: () -> Unit,
    onSongSelected: (MediaMetadata) -> Unit,
) {
    val database = LocalDatabase.current
    val allSongsFlow = remember { database.songs(SongSortType.CREATE_DATE, true) }
    val songs by allSongsFlow.collectAsState(initial = emptyList())

    var searchQuery by remember { mutableStateOf("") }

    val filteredSongs = remember(songs, searchQuery) {
        if (searchQuery.isBlank()) {
            songs
        } else {
            songs.filter {
                it.song.title.contains(searchQuery, ignoreCase = true) ||
                    it.artists.any { artist -> artist.name.contains(searchQuery, ignoreCase = true) }
            }
        }
    }

    DefaultDialog(
        onDismiss = onDismiss,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 550.dp),
        title = {
            Text(
                text = stringResource(R.string.merge_choose_song_to_add),
                fontWeight = FontWeight.Bold,
            )
        },
        buttons = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text(text = stringResource(R.string.search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
            ) {
                itemsIndexed(
                    items = filteredSongs,
                    key = { _, song -> song.id },
                ) { _, song ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSongSelected(song.toMediaMetadata()) }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.music_note),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = song.song.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${song.artists.joinToString(", ") { it.name }} • ${makeTimeString(song.song.duration * 1000L)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
