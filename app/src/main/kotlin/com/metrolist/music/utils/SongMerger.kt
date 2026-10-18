/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.media3.datasource.cache.Cache
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.metrolist.music.constants.AudioQuality
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.AlbumEntity
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.SongAlbumMap
import com.metrolist.music.db.entities.SongEntity
import com.metrolist.music.extensions.parseClipId
import com.metrolist.music.models.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

data class MergeResult(
    val success: Boolean,
    val outputUri: Uri? = null,
    val outputPath: String? = null,
    val errorMessage: String? = null,
)

object SongMerger {
    private const val TAG = "SongMerger"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun mergeSongs(
        context: Context,
        songs: List<MediaMetadata>,
        customTitle: String?,
        downloadCache: Cache,
        database: MusicDatabase,
    ): MergeResult = withContext(Dispatchers.IO) {
        if (songs.size < 2) {
            return@withContext MergeResult(
                success = false,
                errorMessage = "Birleştirmek için en az 2 şarkı gereklidir.",
            )
        }

        val workingDir = File(context.cacheDir, "song_merger").apply { if (!exists()) mkdirs() }
        val tempInputFiles = mutableListOf<File>()
        var tempOutputFile: File? = null

        try {
            // 1. Prepare audio source for each song
            for ((index, metadata) in songs.withIndex()) {
                val cleanId = if (metadata.id.startsWith("clip:")) {
                    parseClipId(metadata.id)?.originalId ?: metadata.id
                } else {
                    metadata.id
                }

                val sourceFile = prepareSourceAudio(
                    context = context,
                    mediaId = cleanId,
                    workingDir = workingDir,
                    index = index,
                    downloadCache = downloadCache,
                    database = database,
                )

                if (sourceFile == null || !sourceFile.exists() || sourceFile.length() <= 0L) {
                    return@withContext MergeResult(
                        success = false,
                        errorMessage = "Parça kaynak sesi hazırlanamadı: ${metadata.title}",
                    )
                }
                tempInputFiles.add(sourceFile)
            }

            // 2. Output file path and tags
            val firstTitle = songs.first().title
            val secondTitle = songs.getOrNull(1)?.title ?: "Music"
            val defaultTitle = if (songs.size == 2) {
                "$firstTitle + $secondTitle"
            } else {
                "$firstTitle ve ${songs.size - 1} Parça"
            }
            val title = customTitle?.trim()?.ifBlank { null } ?: defaultTitle
            val artist = songs.map { song ->
                song.artists.joinToString(", ") { it.name }.ifBlank { null } ?: "Taşkın Music"
            }.distinct().joinToString(" & ").take(100)
            val album = "Birleştirilmiş Parçalar"

            val safeTitle = sanitizeFileName(title)
            val safeArtist = sanitizeFileName(artist)
            val outputFileName = "$safeArtist - $safeTitle.m4a"
            tempOutputFile = File(workingDir, "merged_${System.currentTimeMillis()}.m4a")

            // 3. Build FFmpeg command with robust aresample filter
            val inputArgs = tempInputFiles.joinToString(" ") { "-i \"${it.absolutePath}\"" }
            val resampleFilters = tempInputFiles.indices.joinToString(";") { idx ->
                "[$idx:a]aresample=44100,aformat=channel_layouts=stereo[a$idx]"
            }
            val concatInputs = tempInputFiles.indices.joinToString("") { idx -> "[a$idx]" }
            val filterComplex = "$resampleFilters;$concatInputs concat=n=${tempInputFiles.size}:v=0:a=1[outa]"

            val cleanMetaTitle = title.replace("\"", "\\\"")
            val cleanMetaArtist = artist.replace("\"", "\\\"")
            val cleanMetaAlbum = album.replace("\"", "\\\"")

            val ffmpegCommand = "-y $inputArgs -filter_complex \"$filterComplex\" -map \"[outa]\" -c:a aac -b:a 320k -metadata title=\"$cleanMetaTitle\" -metadata artist=\"$cleanMetaArtist\" -metadata album=\"$cleanMetaAlbum\" \"${tempOutputFile.absolutePath}\""

            Timber.tag(TAG).d("Running FFmpeg merge command: $ffmpegCommand")
            val session = FFmpegKit.execute(ffmpegCommand)

            if (!ReturnCode.isSuccess(session.returnCode) || !tempOutputFile.exists() || tempOutputFile.length() <= 0L) {
                val errorLogs = session.allLogsAsString
                Timber.tag(TAG).e("FFmpeg merge failed: %s", errorLogs)
                return@withContext MergeResult(
                    success = false,
                    errorMessage = "FFmpeg birleştirme hatası: ${session.failStackTrace ?: errorLogs.take(200)}",
                )
            }

            // 4. Save to MediaStore (Music/)
            val savedUri = saveToMediaStore(
                context = context,
                sourceFile = tempOutputFile,
                fileName = outputFileName,
                mimeType = "audio/mp4",
                title = title,
                artist = artist,
                album = album,
            ) ?: return@withContext MergeResult(
                success = false,
                errorMessage = "Birleştirilen şarkı Müzik klasörüne kaydedilemedi.",
            )

            // 5. Add to Room database so it immediately shows in Library!
            try {
                val localSongId = savedUri.toString().replace("external_primary", "external")
                val totalDurationSec = try {
                    val mmr = android.media.MediaMetadataRetriever()
                    mmr.setDataSource(context, Uri.parse(localSongId))
                    val durStr = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    mmr.release()
                    (durStr?.toLongOrNull()?.div(1000))?.toInt()
                } catch (_: Exception) {
                    null
                } ?: songs.sumOf { it.duration }.coerceAtLeast(1)

                val artistId = "local_artist_${artist.trim().lowercase()}"
                val albumId = "local_album_merged_tracks"

                val newSongEntity = SongEntity(
                    id = localSongId,
                    title = title,
                    duration = totalDurationSec,
                    thumbnailUrl = songs.firstOrNull()?.thumbnailUrl,
                    albumId = albumId,
                    albumName = album,
                    explicit = false,
                    inLibrary = LocalDateTime.now(),
                    isLocal = true,
                    isDownloaded = true,
                    dateDownload = LocalDateTime.now(),
                )

                database.transaction {
                    insert(newSongEntity)
                    val artistEntity = ArtistEntity(id = artistId, name = artist, isLocal = true)
                    insert(artistEntity)
                    replaceSongArtists(localSongId, listOf(artistEntity))
                    val albumEntity = AlbumEntity(
                        id = albumId,
                        title = album,
                        songCount = 1,
                        duration = totalDurationSec,
                        isLocal = true,
                        thumbnailUrl = songs.firstOrNull()?.thumbnailUrl,
                    )
                    insert(albumEntity)
                    insert(SongAlbumMap(songId = localSongId, albumId = albumId, index = 0))
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Could not add merged song to database library")
            }

            MergeResult(
                success = true,
                outputUri = savedUri,
                outputPath = outputFileName,
            )
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error merging songs")
            MergeResult(
                success = false,
                errorMessage = e.localizedMessage ?: "Bilinmeyen birleştirme hatası",
            )
        } finally {
            // Clean up temporary files
            try {
                tempInputFiles.forEach { it.delete() }
                tempOutputFile?.delete()
            } catch (_: Exception) {}
        }
    }

    private suspend fun prepareSourceAudio(
        context: Context,
        mediaId: String,
        workingDir: File,
        index: Int,
        downloadCache: Cache,
        database: MusicDatabase,
    ): File? = withContext(Dispatchers.IO) {
        val ext = when {
            mediaId.endsWith(".mp4", ignoreCase = true) -> ".mp4"
            mediaId.endsWith(".m4a", ignoreCase = true) -> ".m4a"
            mediaId.endsWith(".webm", ignoreCase = true) -> ".webm"
            mediaId.endsWith(".opus", ignoreCase = true) -> ".opus"
            mediaId.endsWith(".mp3", ignoreCase = true) -> ".mp3"
            mediaId.endsWith(".flac", ignoreCase = true) -> ".flac"
            mediaId.endsWith(".wav", ignoreCase = true) -> ".wav"
            else -> ".tmp"
        }
        val tempInput = File(workingDir, "src_${index}_${System.currentTimeMillis()}$ext")

        // 1. Direct local file or content URI
        if (mediaId.startsWith("content://") || mediaId.startsWith("file://") || mediaId.startsWith("/")) {
            try {
                val uri = Uri.parse(mediaId)
                context.contentResolver.openInputStream(uri)?.use { inStream ->
                    FileOutputStream(tempInput).use { outStream ->
                        inStream.copyTo(outStream)
                    }
                }
                if (tempInput.exists() && tempInput.length() > 0) return@withContext tempInput
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to read local source URI")
            }
        }

        // 2. Check exported song in Music/ directory
        val exportedUri = SongExporter.findExportedSongUri(context, mediaId)
        if (exportedUri != null) {
            try {
                context.contentResolver.openInputStream(exportedUri)?.use { inStream ->
                    FileOutputStream(tempInput).use { outStream ->
                        inStream.copyTo(outStream)
                    }
                }
                if (tempInput.exists() && tempInput.length() > 0) return@withContext tempInput
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to read exported song URI")
            }
        }

        // 3. Check download cache spans
        val spans = downloadCache.getCachedSpans(mediaId).sortedBy { it.position }
        if (spans.isNotEmpty() && spans.all { it.file != null && it.file!!.exists() }) {
            try {
                FileOutputStream(tempInput).use { outStream ->
                    for (span in spans) {
                        span.file?.inputStream()?.use { it.copyTo(outStream) }
                    }
                }
                if (tempInput.exists() && tempInput.length() > 0) return@withContext tempInput
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to copy cached spans")
            }
        }

        // 4. Online stream fetch fallback via InnerTubeX
        if (mediaId.length == 11) {
            try {
                val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
                if (connectivityManager != null) {
                    val playback = InnerTubeXPlayer.playerResponseForPlayback(
                        videoId = mediaId,
                        audioQuality = AudioQuality.HIGH,
                        connectivityManager = connectivityManager,
                    ).getOrNull()

                    val streamUrl = playback?.streamUrl
                    if (!streamUrl.isNullOrBlank()) {
                        val request = Request.Builder()
                            .url(streamUrl)
                            .header("User-Agent", "Mozilla/5.0 TaskinMusic/1.0")
                            .build()
                        httpClient.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                response.body.byteStream().use { inStream ->
                                    FileOutputStream(tempInput).use { outStream ->
                                        inStream.copyTo(outStream)
                                    }
                                }
                                if (tempInput.exists() && tempInput.length() > 0) return@withContext tempInput
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Online stream fetch for merge failed")
            }
        }

        null
    }

    private fun saveToMediaStore(
        context: Context,
        sourceFile: File,
        fileName: String,
        mimeType: String,
        title: String,
        artist: String,
        album: String,
    ): Uri? {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relativePath = "${Environment.DIRECTORY_MUSIC}/"
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Audio.Media.TITLE, title)
                put(MediaStore.Audio.Media.ARTIST, artist)
                put(MediaStore.Audio.Media.ALBUM, album)
                put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
                put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }

            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val destUri = resolver.insert(collection, values) ?: return null

            resolver.openOutputStream(destUri)?.use { out ->
                sourceFile.inputStream().use { input ->
                    input.copyTo(out)
                }
            } ?: return null

            val completeValues = ContentValues().apply {
                put(MediaStore.Audio.Media.IS_PENDING, 0)
            }
            resolver.update(destUri, completeValues, null, null)

            val normalizedUri = Uri.parse(destUri.toString().replace("external_primary", "external"))

            // Trigger MediaScanner
            try {
                resolver.query(destUri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val dataIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                        if (dataIdx != -1) {
                            val path = cursor.getString(dataIdx)
                            if (!path.isNullOrBlank()) {
                                MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf(mimeType), null)
                            }
                        }
                    }
                }
            } catch (_: Exception) {}

            return normalizedUri
        } else {
            @Suppress("DEPRECATION")
            val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            if (!musicDir.exists()) musicDir.mkdirs()
            val targetFile = File(musicDir, fileName)
            FileOutputStream(targetFile).use { out ->
                sourceFile.inputStream().use { input ->
                    input.copyTo(out)
                }
            }
            MediaScannerConnection.scanFile(context, arrayOf(targetFile.absolutePath), arrayOf(mimeType), null)
            return Uri.fromFile(targetFile)
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .take(100)
    }
}
