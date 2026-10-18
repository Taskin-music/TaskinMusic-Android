/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.MediaInfo
import com.metrolist.music.LocalArtistNameAliases
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.LoudnessLevel
import com.metrolist.music.constants.LoudnessLevelKey
import com.metrolist.music.db.entities.FormatEntity
import com.metrolist.music.db.entities.Song
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.component.shimmer.ShimmerHost
import com.metrolist.music.ui.component.shimmer.TextPlaceholder
import com.metrolist.music.utils.ArtistNameAliases
import com.metrolist.music.utils.rememberEnumPreference
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.metrolist.music.utils.SongExporter
import com.metrolist.music.utils.makeTimeString
import androidx.compose.ui.platform.LocalLocale

import android.os.Build
import androidx.media3.exoplayer.offline.Download
import com.metrolist.music.LocalDownloadUtil

@Composable
fun getLoudnessLevelLabel(loudnessLevel: LoudnessLevel): String {
    return when (loudnessLevel) {
        LoudnessLevel.AGGRESSIVE -> stringResource(R.string.loudness_level_aggressive)
        LoudnessLevel.LOUD -> stringResource(R.string.loudness_level_loud)
        LoudnessLevel.BALANCED -> stringResource(R.string.loudness_level_balanced)
        LoudnessLevel.QUIET -> stringResource(R.string.loudness_level_quiet)
    }
}

@Composable
fun ShowMediaInfo(videoId: String) {
    if (videoId.isBlank() || videoId.isEmpty()) return

    val windowInsets = WindowInsets.systemBars

    var info by remember {
        mutableStateOf<MediaInfo?>(null)
    }

    val database = LocalDatabase.current
    var song by remember { mutableStateOf<Song?>(null) }

    var currentFormat by remember { mutableStateOf<FormatEntity?>(null) }

    val playerConnection = LocalPlayerConnection.current
    val currentStreamClient by playerConnection?.currentStreamClient?.collectAsState() ?: remember { mutableStateOf(null) }
    val context = LocalContext.current
    val artistNameAliases = LocalArtistNameAliases.current
    val downloadUtil = LocalDownloadUtil.current

    val loudnessLevel by rememberEnumPreference(
        LoudnessLevelKey,
        defaultValue = LoudnessLevel.BALANCED
    )

    val targetLufs: Float = loudnessLevel.targetLufs

    var localFilePath by remember { mutableStateOf<String?>(null) }
    var localFileSize by remember { mutableStateOf<Long?>(null) }
    var localMimeType by remember { mutableStateOf<String?>(null) }
    var localBitrate by remember { mutableStateOf<String?>(null) }
    var localSampleRate by remember { mutableStateOf<String?>(null) }
    var storageLocationType by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit, videoId) {
        info = YouTube.getMediaInfo(videoId).getOrNull()
    }

    LaunchedEffect(Unit, videoId) {
        database.song(videoId).collect {
            song = it
        }
    }

    LaunchedEffect(Unit, videoId) {
        database.format(videoId).collect {
            currentFormat = it
        }
    }

    LaunchedEffect(song, currentFormat, videoId) {
        withContext(Dispatchers.IO) {
            val currentSong = song?.song
            val isLocal = currentSong?.isLocal == true
            if (isLocal) {
                storageLocationType = context.getString(R.string.local_storage)
                val uriStr = currentSong.id
                localFilePath = uriStr
                try {
                    if (uriStr.startsWith("content://")) {
                        val uri = Uri.parse(uriStr)
                        context.contentResolver.query(
                            uri,
                            arrayOf(
                                MediaStore.Audio.Media.DATA,
                                MediaStore.Audio.Media.SIZE,
                                MediaStore.Audio.Media.MIME_TYPE,
                            ),
                            null,
                            null,
                            null,
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val dataIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                                if (dataIdx != -1) {
                                    val path = cursor.getString(dataIdx)
                                    if (!path.isNullOrBlank()) localFilePath = path
                                }
                                val sizeIdx = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
                                if (sizeIdx != -1) {
                                    localFileSize = cursor.getLong(sizeIdx)
                                }
                                val mimeIdx = cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
                                if (mimeIdx != -1) {
                                    localMimeType = cursor.getString(mimeIdx)
                                }
                            }
                        }
                    } else if (uriStr.startsWith("file://") || uriStr.startsWith("/")) {
                        val file = File(uriStr.removePrefix("file://"))
                        localFilePath = file.absolutePath
                        if (file.exists()) {
                            localFileSize = file.length()
                        }
                    }
                } catch (e: Exception) {
                    // Ignore
                }

                val retriever = MediaMetadataRetriever()
                try {
                    if (uriStr.startsWith("content://")) {
                        retriever.setDataSource(context, Uri.parse(uriStr))
                    } else {
                        retriever.setDataSource(uriStr.removePrefix("file://"))
                    }
                    val br = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                    if (!br.isNullOrBlank()) {
                        val brInt = br.toIntOrNull()
                        localBitrate = if (brInt != null) "${brInt / 1000} Kbps" else "$br bps"
                    }
                    val sr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
                    if (!sr.isNullOrBlank()) {
                        localSampleRate = "$sr Hz"
                    }
                    val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                    if (!mime.isNullOrBlank() && localMimeType.isNullOrBlank()) {
                        localMimeType = mime
                    }
                } catch (e: Exception) {
                    // Ignore
                } finally {
                    try { retriever.release() } catch (e: Exception) {}
                }
            } else {
                // 1. Check if exported into Music public directory or MediaStore
                val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                val cleanTitle = currentSong?.title?.replace(Regex("[\\\\/:*?\"<>|]"), "_")?.trim()
                var exportedFile: File? = null

                if (musicDir.exists() && musicDir.isDirectory && !cleanTitle.isNullOrBlank()) {
                    val files = musicDir.listFiles()
                    if (files != null) {
                        exportedFile = files.firstOrNull { f ->
                            f.isFile && f.nameWithoutExtension.contains(cleanTitle, ignoreCase = true)
                        }
                    }
                }

                if (exportedFile == null && !cleanTitle.isNullOrBlank()) {
                    val targetSubDir = SongExporter.getTargetSubdirectory()
                    val taskinMusicDir = File(musicDir, targetSubDir)
                    if (taskinMusicDir.exists() && taskinMusicDir.isDirectory) {
                        exportedFile = taskinMusicDir.listFiles()?.firstOrNull { f ->
                            f.isFile && f.nameWithoutExtension.contains(cleanTitle, ignoreCase = true)
                        }
                    }
                }

                if (exportedFile == null && !cleanTitle.isNullOrBlank()) {
                    val legacyDir = File(musicDir, SongExporter.LEGACY_SUBDIRECTORY)
                    if (legacyDir.exists() && legacyDir.isDirectory) {
                        exportedFile = legacyDir.listFiles()?.firstOrNull { f ->
                            f.isFile && f.nameWithoutExtension.contains(cleanTitle, ignoreCase = true)
                        }
                    }
                }

                if (exportedFile == null && !cleanTitle.isNullOrBlank() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                        val selection = "(${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?) AND (${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ? OR ${MediaStore.Audio.Media.TITLE} LIKE ?)"
                        val selectionArgs = arrayOf("%${Environment.DIRECTORY_MUSIC}%", "%${SongExporter.SUBDIRECTORY}%", "%${SongExporter.LEGACY_SUBDIRECTORY}%", "%$cleanTitle%", "%$cleanTitle%")
                        context.contentResolver.query(
                            collection,
                            arrayOf(MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.MIME_TYPE),
                            selection,
                            selectionArgs,
                            null
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val dataIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                                if (dataIdx != -1) {
                                    val path = cursor.getString(dataIdx)
                                    if (!path.isNullOrBlank()) {
                                        val f = File(path)
                                        if (f.exists()) exportedFile = f
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }

                // 2. Check if downloaded in ExoPlayer download cache or database marked downloaded
                val downloadSpans = downloadUtil.downloadCache.getCachedSpans(videoId)
                val isDownloadedInDb = currentSong?.isDownloaded == true
                val isCompletedDownload = downloadUtil.downloads.value[videoId]?.state == Download.STATE_COMPLETED
                val isDownloaded = exportedFile != null || downloadSpans.isNotEmpty() || isDownloadedInDb || isCompletedDownload

                // 3. Check if cached in ExoPlayer streaming player cache
                val playerSpans = if (!isDownloaded) downloadUtil.playerCache.getCachedSpans(videoId) else emptySet()

                val targetExportedFile = exportedFile
                if (targetExportedFile != null && targetExportedFile.exists()) {
                    storageLocationType = context.getString(R.string.downloaded_storage)
                    localFilePath = targetExportedFile.absolutePath
                    localFileSize = targetExportedFile.length()
                    localMimeType = when (targetExportedFile.extension.lowercase()) {
                        "mp3" -> "audio/mpeg (MP3)"
                        "m4a" -> "audio/mp4 (M4A)"
                        "opus" -> "audio/opus"
                        else -> currentFormat?.mimeType ?: "audio/mp4"
                    }
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(targetExportedFile.absolutePath)
                        val br = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                        if (!br.isNullOrBlank()) {
                            val brInt = br.toIntOrNull()
                            localBitrate = if (brInt != null) "${brInt / 1000} Kbps" else "$br bps"
                        }
                        val sr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
                        if (!sr.isNullOrBlank()) {
                            localSampleRate = "$sr Hz"
                        }
                    } catch (_: Exception) {
                    } finally {
                        try { retriever.release() } catch (_: Exception) {}
                    }
                } else if (isDownloaded) {
                    storageLocationType = context.getString(R.string.downloaded_storage)
                    val firstSpanFile = downloadSpans.firstOrNull { it.file != null && it.file!!.exists() }?.file
                    localFilePath = firstSpanFile?.absolutePath ?: "${context.filesDir.resolve("download").absolutePath}/$videoId"
                    val spanSize = downloadSpans.sumOf { it.length }
                    localFileSize = if (spanSize > 0) spanSize else (firstSpanFile?.length() ?: currentFormat?.contentLength)
                    localMimeType = currentFormat?.mimeType?.takeIf { it.isNotBlank() } ?: "audio/mp4"
                    localBitrate = currentFormat?.bitrate?.let { "${it / 1000} Kbps" }
                    localSampleRate = currentFormat?.sampleRate?.let { "$it Hz" }

                    if (firstSpanFile != null && firstSpanFile.exists()) {
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(firstSpanFile.absolutePath)
                            val br = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                            if (!br.isNullOrBlank()) {
                                val brInt = br.toIntOrNull()
                                localBitrate = if (brInt != null) "${brInt / 1000} Kbps" else "$br bps"
                            }
                            val sr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
                            if (!sr.isNullOrBlank()) {
                                localSampleRate = "$sr Hz"
                            }
                        } catch (_: Exception) {
                        } finally {
                            try { retriever.release() } catch (_: Exception) {}
                        }
                    }
                } else if (playerSpans.isNotEmpty()) {
                    storageLocationType = "Çevrim Dışı Önbellek (Taşkın Music)"
                    val firstPlayerFile = playerSpans.firstOrNull { it.file != null && it.file!!.exists() }?.file
                    localFilePath = firstPlayerFile?.absolutePath ?: "${context.filesDir.resolve("exoplayer").absolutePath}/$videoId"
                    val pSize = playerSpans.sumOf { it.length }
                    localFileSize = if (pSize > 0) pSize else currentFormat?.contentLength
                    localMimeType = currentFormat?.mimeType?.takeIf { it.isNotBlank() } ?: "audio/mp4"
                    localBitrate = currentFormat?.bitrate?.let { "${it / 1000} Kbps" }
                    localSampleRate = currentFormat?.sampleRate?.let { "$it Hz" }
                } else {
                    storageLocationType = context.getString(R.string.online_stream)
                    localFilePath = "https://music.youtube.com/watch?v=$videoId"
                }
            }
        }
    }

    LazyColumn(
        state = rememberLazyListState(),
        modifier = Modifier
            .padding(
                windowInsets
                    .asPaddingValues()
            )
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (info != null || song != null) {
            item(contentType = "MediaDetails") {
                Column {
                    val baseList = listOf(
                        stringResource(R.string.song_title) to (info?.title ?: song?.title),
                        stringResource(R.string.song_artists) to (
                            song?.artists?.joinToString {
                                ArtistNameAliases.resolve(artistNameAliases, it.id, it.name)
                            } ?: info?.author?.let {
                                ArtistNameAliases.resolve(artistNameAliases, null, it)
                            }
                        ),
                        stringResource(R.string.media_id) to (song?.id ?: info?.videoId)
                    )

                    val baseIconsList = listOf(
                        R.drawable.music_note,
                        R.drawable.person,
                        R.drawable.media3_icon_bookmark_filled,
                    )

                    val iconsList = listOf(
                        R.drawable.media3_icon_feed,
                        R.drawable.media3_icon_thumb_up_unfilled,
                        R.drawable.media3_icon_thumb_down_unfilled,
                        R.drawable.key,
                        R.drawable.play,
                        R.drawable.info,
                        R.drawable.radio,
                        R.drawable.gradient,
                        R.drawable.contrast,
                        R.drawable.volume_up,
                        R.drawable.volume_up,
                        R.drawable.volume_mute,
                        R.drawable.content_copy
                    )

                    val measuredLufs: Double? = currentFormat?.perceptualLoudnessDb ?: currentFormat?.loudnessDb?.let { it + LoudnessLevel.AGGRESSIVE.targetLufs }

                    val extendedList = if (currentFormat != null) {
                        listOf(
                            stringResource(R.string.views) to info?.viewCount?.let(::numberFormatter).orEmpty(),
                            stringResource(R.string.likes) to info?.like?.let(::numberFormatter).orEmpty(),
                            stringResource(R.string.dislikes) to info?.dislike?.let(::numberFormatter).orEmpty(),
                            "Itag" to currentFormat?.itag?.toString(),
                            stringResource(R.string.stream_client) to currentStreamClient,
                            stringResource(R.string.mime_type) to currentFormat?.mimeType,
                            stringResource(R.string.codecs) to currentFormat?.codecs,
                            stringResource(R.string.bitrate) to currentFormat?.bitrate?.let { "${it / 1000} Kbps" },
                            stringResource(R.string.sample_rate) to currentFormat?.sampleRate?.let { "$it Hz" },
                            stringResource(R.string.loudness) to measuredLufs?.let {
                                String.format(LocalLocale.current.platformLocale, "%.2f dB", it - targetLufs)
                            },
                            stringResource(R.string.loudness_level) to getLoudnessLevelLabel(loudnessLevel),
                            stringResource(R.string.volume) to if (playerConnection != null) "${(playerConnection.player.volume * 100).toInt()}%" else null,
                            stringResource(R.string.file_size) to
                                    currentFormat?.contentLength?.let {
                                        Formatter.formatShortFileSize(
                                            context,
                                            it
                                        )
                                    },
                        )
                    } else {
                        emptyList()
                    }

                    val fileDetailsItems = listOfNotNull(
                        stringResource(R.string.file_location) to (storageLocationType ?: stringResource(R.string.online_stream)),
                        stringResource(R.string.file_path) to localFilePath,
                        stringResource(R.string.file_format) to (localMimeType ?: currentFormat?.mimeType ?: currentFormat?.codecs),
                        stringResource(R.string.bitrate) to (localBitrate ?: currentFormat?.bitrate?.let { "${it / 1000} Kbps" }),
                        stringResource(R.string.sample_rate) to (localSampleRate ?: currentFormat?.sampleRate?.let { "$it Hz" }),
                        stringResource(R.string.file_size) to (
                            localFileSize?.let { Formatter.formatShortFileSize(context, it) }
                                ?: currentFormat?.contentLength?.let { Formatter.formatShortFileSize(context, it) }
                        ),
                        stringResource(R.string.duration) to (song?.song?.duration?.let { makeTimeString(it.toLong()) } ?: stringResource(R.string.unknown)),
                    )

                    val fileIconsList = listOf(
                        R.drawable.storage,
                        R.drawable.location_on,
                        R.drawable.music_note,
                        R.drawable.speed,
                        R.drawable.radio,
                        R.drawable.download,
                        R.drawable.timer,
                    )

                    val cardsBaseList = mutableListOf<Material3SettingsItem>()
                    val cardsFileDetailsList = mutableListOf<Material3SettingsItem>()
                    val cardsExtendedList = mutableListOf<Material3SettingsItem>()
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

                    baseList.forEachIndexed { index, (label, text) ->
                        val displayText = text ?: stringResource(R.string.unknown)
                        cardsBaseList += Material3SettingsItem(
                            title = { Text(label) },
                            description = { Text(displayText) },
                            icon = painterResource(baseIconsList[index]),
                            onClick = {
                                cm.setPrimaryClip(ClipData.newPlainText("text", displayText))
                                Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
                            },
                        )
                    }

                    fileDetailsItems.forEachIndexed { index, (label, text) ->
                        val displayText = text ?: stringResource(R.string.unknown)
                        cardsFileDetailsList += Material3SettingsItem(
                            title = { Text(label) },
                            description = { Text(displayText) },
                            icon = painterResource(fileIconsList.getOrElse(index) { R.drawable.info }),
                            onClick = {
                                cm.setPrimaryClip(ClipData.newPlainText("text", displayText))
                                Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
                            },
                        )
                    }

                    extendedList.forEachIndexed { index, (label, text) ->
                        val displayText = text ?: stringResource(R.string.unknown)
                        cardsExtendedList += Material3SettingsItem(
                            title = { Text(label) },
                            description = { Text(displayText) },
                            icon = painterResource(iconsList[index]),
                            onClick = {
                                cm.setPrimaryClip(ClipData.newPlainText("text", displayText))
                                Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
                            },
                        )
                    }

                    Material3SettingsGroup(
                        title = stringResource(R.string.general),
                        items = cardsBaseList
                    )

                    Spacer(Modifier.height(8.dp))

                    Material3SettingsGroup(
                        title = stringResource(R.string.file_details),
                        items = cardsFileDetailsList
                    )

                    if (cardsExtendedList.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))

                        Material3SettingsGroup(
                            title = stringResource(R.string.information),
                            items = cardsExtendedList
                        )
                    }

                    if (!info?.description.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))

                        val descriptionText = info?.description ?: stringResource(R.string.unknown)

                        Material3SettingsGroup(
                            title = stringResource(R.string.description),
                            items = listOf(
                                Material3SettingsItem(
                                    title = { Text(stringResource(R.string.description)) },
                                    description = { Text(descriptionText) },
                                    onClick = {
                                        cm.setPrimaryClip(ClipData.newPlainText("text", descriptionText))
                                        Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
                                    }
                                )
                            )
                        )
                    }
                }
            }
        } else {
            item(contentType = "MediaInfoLoader") {
                ShimmerHost {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(all = 16.dp)
                    ) {
                        TextPlaceholder()
                    }
                }
            }
        }
    }
}
