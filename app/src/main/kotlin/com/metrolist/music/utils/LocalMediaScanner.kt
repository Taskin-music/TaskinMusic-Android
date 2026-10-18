/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.AlbumArtistMap
import com.metrolist.music.db.entities.AlbumEntity
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.SongAlbumMap
import com.metrolist.music.db.entities.SongEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalMediaScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
) {
    companion object {
        const val TAG = "LocalMediaScanner"

        fun hasStoragePermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_MEDIA_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
    }

    suspend fun scan(): Int = withContext(Dispatchers.IO) {
        if (!hasStoragePermission(context)) {
            Timber.tag(TAG).w("Scan skipped: storage permission not granted")
            return@withContext 0
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val projection = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.DATA,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.RELATIVE_PATH)
            }
        }.toTypedArray()

        // Capture all audio/music files, including downloads, while excluding ringtones, alarms and notifications
        val selection = buildString {
            append("(")
            append("${MediaStore.Audio.Media.IS_MUSIC} != 0")
            append(" OR ${MediaStore.Audio.Media.MIME_TYPE} LIKE 'audio/%'")
            append(" OR ${MediaStore.Audio.Media.DISPLAY_NAME} LIKE '%.mp3'")
            append(" OR ${MediaStore.Audio.Media.DISPLAY_NAME} LIKE '%.m4a'")
            append(" OR ${MediaStore.Audio.Media.DISPLAY_NAME} LIKE '%.flac'")
            append(" OR ${MediaStore.Audio.Media.DISPLAY_NAME} LIKE '%.opus'")
            append(" OR ${MediaStore.Audio.Media.DISPLAY_NAME} LIKE '%.ogg'")
            append(" OR ${MediaStore.Audio.Media.DISPLAY_NAME} LIKE '%.wav'")
            append(")")
            append(" AND (${MediaStore.Audio.Media.IS_RINGTONE} = 0 OR ${MediaStore.Audio.Media.IS_RINGTONE} IS NULL)")
            append(" AND (${MediaStore.Audio.Media.IS_NOTIFICATION} = 0 OR ${MediaStore.Audio.Media.IS_NOTIFICATION} IS NULL)")
            append(" AND (${MediaStore.Audio.Media.IS_ALARM} = 0 OR ${MediaStore.Audio.Media.IS_ALARM} IS NULL)")
        }
        val sortOrder = "${MediaStore.Audio.Media.DATE_MODIFIED} DESC"

        val foundUris = mutableSetOf<String>()
        var count = 0

        try {
            // Preload existing local songs map for fast O(1) lookups
            val existingLocalSongsMap = database.localSongs().associateBy { it.id }
            val downloadedSongs = database.downloadedSongsByCreateDateAsc().firstOrNull().orEmpty()
            val downloadedTitles = downloadedSongs.map { it.song.title.trim().lowercase() }.toSet()
            val downloadedIds = downloadedSongs.map { it.song.id }.toSet()

            context.contentResolver.query(collection, projection, selection, null, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val displayNameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val dateModifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                val relativePathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                } else -1

                database.withTransaction {
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val durationMs = cursor.getLong(durationCol)

                        // Skip audio clips shorter than 5 seconds (chimes, tiny notifications)
                        if (durationMs in 1..4999) continue

                        val rawDisplayName = cursor.getString(displayNameCol)
                        val nameLower = rawDisplayName.orEmpty().lowercase()

                        // Filter non-music audio: TED talks, WhatsApp voice notes, recordings
                        if (nameLower.startsWith("ted") || nameLower.startsWith("tedx") || nameLower.contains("ted talk") || nameLower.contains("ted konusma") || nameLower.contains("ted konuşma")) continue
                        if (nameLower.startsWith("aud-") || nameLower.startsWith("ptt-") || nameLower.startsWith("whatsapp")) continue
                        if (nameLower.startsWith("rec_") || nameLower.startsWith("call_") || nameLower.startsWith("voice_") || nameLower.startsWith("hangouts_") || nameLower.startsWith("sound_")) continue

                        val dataPath = if (dataCol != -1) cursor.getString(dataCol) else null
                        val relativePath = if (relativePathCol != -1) cursor.getString(relativePathCol) else null
                        val fullPath = (relativePath ?: dataPath).orEmpty().lowercase()

                        // Filter non-music folders (voice notes, calls, recordings, notifications, ringtones)
                        if (fullPath.contains("whatsapp") ||
                            fullPath.contains("call") ||
                            fullPath.contains("soundrecorder") ||
                            fullPath.contains("voicerecorder") ||
                            fullPath.contains("recordings") ||
                            fullPath.contains("telegram") ||
                            fullPath.contains("notifications") ||
                            fullPath.contains("ringtones") ||
                            fullPath.contains("alarms")
                        ) {
                            continue
                        }

                        val cleanedDisplayName = rawDisplayName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
                        val rawTitle = cursor.getString(titleCol)?.takeIf { it.isNotBlank() && it != "<unknown>" }
                            ?: cleanedDisplayName
                            ?: "Unknown Title"

                        val rawArtist = cursor.getString(artistCol)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown Artist"
                        val rawAlbum = cursor.getString(albumCol)?.takeIf { it.isNotBlank() && it != "<unknown>" }
                        val albumIdLong = cursor.getLong(albumIdCol)

                        val dateAddedSec = try { cursor.getLong(dateAddedCol) } catch (_: Exception) { 0L }
                        val dateModifiedSec = try { cursor.getLong(dateModifiedCol) } catch (_: Exception) { 0L }
                        val timestampSec = maxOf(dateAddedSec, dateModifiedSec).takeIf { it > 0 } ?: (System.currentTimeMillis() / 1000)

                        val fileDateTime = try {
                            LocalDateTime.ofInstant(Instant.ofEpochSecond(timestampSec), ZoneId.systemDefault())
                        } catch (_: Exception) {
                            LocalDateTime.now()
                        }

                        val contentUri = ContentUris.withAppendedId(collection, id).toString()
                        val normalizedContentUri = contentUri.replace("external_primary", "external")
                        foundUris.add(contentUri)
                        foundUris.add(normalizedContentUri)

                        // Check if file name has embedded YouTube video ID in (_..._) or (...)
                        val ytMatch = Regex("""\(_([a-zA-Z0-9_-]{11})_\)""").find(rawDisplayName.orEmpty())
                            ?: Regex("""\(([a-zA-Z0-9_-]{11})\)""").find(rawDisplayName.orEmpty())
                        val ytThumbnail = ytMatch?.groupValues?.get(1)?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }

                        // If this file matches an existing downloaded YouTube song in the database
                        val matched = downloadedSongs.find {
                            !it.song.isLocal && (
                                it.song.title.equals(rawTitle, ignoreCase = true) ||
                                (cleanedDisplayName != null && it.song.title.equals(cleanedDisplayName, ignoreCase = true)) ||
                                (ytMatch != null && it.song.id == ytMatch.groupValues[1]) ||
                                (rawDisplayName != null && SongExporter.isExportedSong(context, contentUri, dataPath, rawDisplayName))
                            )
                        }

                        if (matched != null) {
                            SongExporter.registerExportedSong(context, matched.song.id, contentUri, dataPath, rawDisplayName)
                            if (matched.song.inLibrary == null) {
                                database.inLibrary(matched.song.id, fileDateTime)
                            }
                            if (!matched.song.isDownloaded) {
                                database.updateDownloadedInfo(matched.song.id, true, fileDateTime)
                            }
                            continue
                        }

                        val albumArtUri = if (albumIdLong > 0) {
                            ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumIdLong).toString()
                        } else null
                        val finalThumbnail = ytThumbnail ?: albumArtUri ?: contentUri

                        val existing = existingLocalSongsMap[contentUri]
                            ?: existingLocalSongsMap[normalizedContentUri]
                            ?: existingLocalSongsMap[contentUri.replace("external", "external_primary")]
                        if (existing != null) {
                            // Update download date & library status
                            database.updateDownloadedInfo(existing.id, true, fileDateTime)
                            if (existing.inLibrary == null) {
                                database.inLibrary(existing.id, fileDateTime)
                            }
                            // Update thumbnail if missing or if we found a better YouTube thumbnail
                            if (existing.thumbnailUrl.isNullOrBlank() || (ytThumbnail != null && existing.thumbnailUrl == contentUri)) {
                                database.updateSongThumbnail(existing.id, finalThumbnail)
                            }
                        } else {
                            val artistId = "local_artist_${rawArtist.trim().lowercase()}"
                            val albumId = rawAlbum?.let { "local_album_${it.trim().lowercase()}_$artistId" }

                            // 1. Insert Artist
                            val artistEntity = ArtistEntity(
                                id = artistId,
                                name = rawArtist,
                                isLocal = true,
                            )
                            database.insert(artistEntity)

                            // 2. Insert Album if available
                            if (albumId != null) {
                                val albumEntity = AlbumEntity(
                                    id = albumId,
                                    title = rawAlbum,
                                    songCount = 1,
                                    duration = (durationMs / 1000).toInt(),
                                    isLocal = true,
                                    thumbnailUrl = finalThumbnail,
                                    inLibrary = fileDateTime,
                                )
                                database.insert(albumEntity)
                                database.insert(AlbumArtistMap(albumId = albumId, artistId = artistId, order = 0))
                            }

                            // 3. Insert Song with real file download date & artwork
                            val songEntity = SongEntity(
                                id = normalizedContentUri,
                                title = rawTitle,
                                duration = (durationMs / 1000).toInt(),
                                thumbnailUrl = finalThumbnail,
                                albumId = albumId,
                                albumName = rawAlbum,
                                inLibrary = fileDateTime,
                                isLocal = true,
                                isDownloaded = true,
                                dateDownload = fileDateTime,
                            )
                            database.insert(songEntity)

                            // 4. Map song to artist and album
                            database.replaceSongArtists(normalizedContentUri, listOf(artistEntity))
                            if (albumId != null) {
                                database.insert(SongAlbumMap(songId = normalizedContentUri, albumId = albumId, index = 0))
                            }
                        }

                        count++
                    }

                    // Clean up any local songs in the DB that no longer exist on storage or mirror a downloaded song
                    for (localSong in existingLocalSongsMap.values) {
                        if (localSong.id.startsWith("clip:")) continue
                        val normId = localSong.id.replace("external_primary", "external")
                        val hasDownloadedCounterpart = downloadedSongs.any {
                            !it.song.isLocal && it.song.isDownloaded && it.song.title.equals(localSong.title, ignoreCase = true)
                        }
                        if (hasDownloadedCounterpart) {
                            database.deleteLocalSongById(localSong.id)
                            continue
                        }
                        if (foundUris.contains(localSong.id) || foundUris.contains(normId)) {
                            continue
                        }
                        if (localSong.id.startsWith("file://") || localSong.id.startsWith("/")) {
                            val path = localSong.id.removePrefix("file://")
                            if (java.io.File(path).exists()) continue
                        }
                        database.deleteLocalSongById(localSong.id)
                    }
                }
            }

            Timber.tag(TAG).i("Finished scanning: $count local songs indexed")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error scanning MediaStore audio")
        }

        count
    }
}
