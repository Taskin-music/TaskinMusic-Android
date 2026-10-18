/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.app.RecoverableSecurityException
import android.content.Context
import android.content.IntentSender
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.media3.exoplayer.offline.DownloadService
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Song
import com.metrolist.music.playback.ExoDownloadService
import com.metrolist.music.playback.PlayerConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

object MediaDeleteHelper {
    private const val TAG = "MediaDeleteHelper"

    sealed class DeleteResult {
        data class RequiresIntentSender(
            val intentSenderRequest: IntentSenderRequest,
            val songsToDelete: List<Song>
        ) : DeleteResult()

        data class Completed(val songsDeleted: List<Song>) : DeleteResult()
        data class Error(val message: String) : DeleteResult()
    }

    /**
     * Attempts to delete physical files from storage.
     * If Android Scoped Storage requires system user permission (Android 10+ / 11+),
     * returns [DeleteResult.RequiresIntentSender].
     * Otherwise directly completes file deletion and returns [DeleteResult.Completed].
     */
    fun requestDeleteSongs(
        context: Context,
        songs: List<Song>
    ): DeleteResult {
        if (songs.isEmpty()) return DeleteResult.Completed(emptyList())

        val contentUris = mutableListOf<Uri>()
        val nonContentSongs = mutableListOf<Song>()

        for (song in songs) {
            val id = song.id
            if (id.startsWith("content://")) {
                contentUris.add(Uri.parse(id))
            } else {
                nonContentSongs.add(song)
                if (id.startsWith("file://") || id.startsWith("/")) {
                    val path = if (id.startsWith("file://")) Uri.parse(id).path ?: "" else id
                    val file = File(path)
                    if (file.exists()) {
                        file.delete()
                    }
                    runCatching {
                        MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
                    }
                }
            }
        }

        // On Android 11+ (API 30+), use MediaStore.createDeleteRequest for MediaStore content URIs
        if (contentUris.isNotEmpty()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return try {
                    val pendingIntent = MediaStore.createDeleteRequest(context.contentResolver, contentUris)
                    val request = IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                    DeleteResult.RequiresIntentSender(request, songs)
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error creating MediaStore delete request")
                    // Fallback: try individual delete
                    for (uri in contentUris) {
                        try {
                            context.contentResolver.delete(uri, null, null)
                        } catch (secEx: SecurityException) {
                            Timber.tag(TAG).w(secEx, "SecurityException deleting $uri")
                        }
                    }
                    DeleteResult.Completed(songs)
                }
            } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                // Android 10 (API 29) RecoverableSecurityException handling
                for (uri in contentUris) {
                    try {
                        context.contentResolver.delete(uri, null, null)
                    } catch (rse: RecoverableSecurityException) {
                        val request = IntentSenderRequest.Builder(rse.userAction.actionIntent.intentSender).build()
                        return DeleteResult.RequiresIntentSender(request, songs)
                    } catch (e: Exception) {
                        Timber.tag(TAG).w(e, "Error deleting $uri on Android 10")
                    }
                }
                return DeleteResult.Completed(songs)
            } else {
                // Android 9 and below: direct delete
                for (uri in contentUris) {
                    runCatching { context.contentResolver.delete(uri, null, null) }
                }
                return DeleteResult.Completed(songs)
            }
        }

        return DeleteResult.Completed(songs)
    }

    /**
     * Executes post-deletion cleanup:
     * 1. Safely manages player playback so currently playing songs do not crash ExoPlayer
     * 2. Removes items from player queue
     * 3. Removes DownloadService offline records
     * 4. Deletes exported audio copies from TaskinMusic public folder
     * 5. Deletes local songs / un-libraries songs from Room database
     * 6. Displays user feedback toast
     */
    suspend fun performPostDeleteCleanup(
        context: Context,
        database: MusicDatabase,
        playerConnection: PlayerConnection?,
        songs: List<Song>,
        showToast: Boolean = true
    ) {
        if (songs.isEmpty()) return
        val songIds = songs.map { it.id }.toSet()

        // 1. Gracefully stop / advance playback if playing a deleted song
        playerConnection?.player?.let { player ->
            withContext(Dispatchers.Main) {
                try {
                    val count = player.mediaItemCount
                    if (count > 0) {
                        val deletedIndices = mutableListOf<Int>()
                        for (i in 0 until count) {
                            val mediaItem = player.getMediaItemAt(i)
                            if (songIds.contains(mediaItem.mediaId)) {
                                deletedIndices.add(i)
                            }
                        }

                        if (deletedIndices.size == count) {
                            // All songs in queue are deleted
                            player.stop()
                            player.clearMediaItems()
                        } else if (deletedIndices.isNotEmpty()) {
                            val currentIdx = player.currentMediaItemIndex
                            val isCurrentDeleted = deletedIndices.contains(currentIdx)

                            if (isCurrentDeleted) {
                                val nextSurvivingIndex = (currentIdx + 1 until count).firstOrNull { it !in deletedIndices }
                                    ?: (0 until currentIdx).firstOrNull { it !in deletedIndices }

                                if (nextSurvivingIndex != null) {
                                    player.seekToDefaultPosition(nextSurvivingIndex)
                                } else {
                                    player.stop()
                                }
                            }

                            for (i in deletedIndices.reversed()) {
                                player.removeMediaItem(i)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error updating player on song deletion")
                }
            }
        }

        // 2. Remove from DownloadService & exported files
        for (song in songs) {
            runCatching {
                DownloadService.sendRemoveDownload(
                    context,
                    ExoDownloadService::class.java,
                    song.id,
                    false
                )
            }
            // Only clean up exported audio files if this is NOT a local song.
            // Local songs are already deleted by their specific URI/path in step 1.
            // Calling deleteExportedSong for a local song would wipe out other converted formats sharing the same title/artist!
            if (!song.song.isLocal) {
                val artistName = song.artists.firstOrNull()?.name.orEmpty()
                val format = song.format ?: database.format(song.id).firstOrNull()
                val ext = format?.mimeType?.let { mime ->
                    when {
                        mime.contains("mp4", true) || mime.contains("m4a", true) -> "m4a"
                        mime.contains("webm", true) || mime.contains("opus", true) -> "opus"
                        mime.contains("mpeg", true) || mime.contains("mp3", true) -> "mp3"
                        mime.contains("flac", true) -> "flac"
                        mime.contains("wav", true) -> "wav"
                        else -> null
                    }
                }
                runCatching {
                    SongExporter.deleteExportedSong(context, song.song.title, artistName, ext)
                }
            }
        }

        // 3. Database cleanup: delete local songs or un-library non-local songs
        database.query {
            for (song in songs) {
                if (song.song.isLocal) {
                    delete(song.song)
                } else {
                    update(song.song.copy(inLibrary = null, isDownloaded = false))
                }
            }
        }

        // 4. User feedback toast
        if (showToast) {
            withContext(Dispatchers.Main) {
                val message = if (songs.size == 1) {
                    "1 şarkı silindi"
                } else {
                    "${songs.size} şarkı silindi"
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
