/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.media3.datasource.cache.Cache
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import android.net.ConnectivityManager
import com.metrolist.music.R
import com.metrolist.music.constants.AudioQuality
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.AlbumArtistMap
import com.metrolist.music.db.entities.AlbumEntity
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.SongAlbumMap
import com.metrolist.music.db.entities.SongEntity
import com.metrolist.music.extensions.parseClipId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

enum class AudioTargetFormat(
    val extension: String,
    val mimeType: String,
    val titleRes: Int,
    val descRes: Int,
    val isLossless: Boolean,
) {
    FLAC(
        extension = "flac",
        mimeType = "audio/flac",
        titleRes = R.string.convert_lossless_flac,
        descRes = R.string.convert_flac_desc,
        isLossless = true,
    ),
    WAV(
        extension = "wav",
        mimeType = "audio/wav",
        titleRes = R.string.convert_lossless_wav,
        descRes = R.string.convert_wav_desc,
        isLossless = true,
    ),
    MP3(
        extension = "mp3",
        mimeType = "audio/mpeg",
        titleRes = R.string.convert_high_mp3,
        descRes = R.string.convert_mp3_desc,
        isLossless = false,
    ),
    M4A(
        extension = "m4a",
        mimeType = "audio/mp4",
        titleRes = R.string.convert_high_m4a,
        descRes = R.string.convert_m4a_desc,
        isLossless = false,
    ),
    OPUS(
        extension = "opus",
        mimeType = "audio/opus",
        titleRes = R.string.convert_high_opus,
        descRes = R.string.convert_opus_desc,
        isLossless = false,
    ),
}

data class ConversionResult(
    val success: Boolean,
    val outputUri: Uri? = null,
    val outputPath: String? = null,
    val errorMessage: String? = null,
)

object AudioConverter {
    private const val TAG = "AudioConverter"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun convertAudio(
        context: Context,
        mediaId: String,
        targetFormat: AudioTargetFormat,
        addToLibrary: Boolean,
        downloadCache: Cache,
        database: MusicDatabase,
    ): ConversionResult = withContext(Dispatchers.IO) {
        val workingDir = File(context.cacheDir, "audio_converter").apply { if (!exists()) mkdirs() }
        var tempInputFile: File? = null
        var tempOutputFile: File? = null
        var tempCoverFile: File? = null

        try {
            val cleanId = if (mediaId.startsWith("clip:")) {
                parseClipId(mediaId)?.originalId ?: mediaId
            } else {
                mediaId
            }

            val song = database.getSongByIdBlocking(mediaId)
                ?: database.getSongByIdBlocking(cleanId)
            val title = song?.song?.title?.ifBlank { null } ?: "Unknown Title"
            val artist = song?.artists?.joinToString(", ") { it.name }?.ifBlank { null } ?: "Unknown Artist"
            val album = song?.album?.title ?: song?.song?.albumName ?: "TaskinMusic"

            // 1. Prepare source audio file
            tempInputFile = prepareSourceAudio(
                context = context,
                mediaId = cleanId,
                workingDir = workingDir,
                downloadCache = downloadCache,
                database = database,
            ) ?: return@withContext ConversionResult(
                success = false,
                errorMessage = "Kaynak ses dosyası okunamadı veya indirilemedi.",
            )

            // 2. Prepare output path
            val safeArtist = sanitizeFileName(artist)
            val safeTitle = sanitizeFileName(title)
            val baseFileName = "$safeArtist - $safeTitle"
            val outputFileName = "$baseFileName.${targetFormat.extension}"
            tempOutputFile = File(workingDir, "out_${System.currentTimeMillis()}.${targetFormat.extension}")

            // 3. Fetch cover art if available
            tempCoverFile = File(workingDir, "cover_${System.currentTimeMillis()}.jpg")
            val coverUrl = song?.song?.thumbnailUrl ?: song?.album?.thumbnailUrl
            val hasCover = SongExporter.fetchCoverImage(context, coverUrl, tempCoverFile)

            // 4. Build FFmpeg command based on selected format
            val ffmpegCommand = buildFFmpegCommand(
                inputFile = tempInputFile,
                outputFile = tempOutputFile,
                format = targetFormat,
                title = title,
                artist = artist,
                album = album,
                coverFile = if (hasCover) tempCoverFile else null,
            )

            Timber.tag(TAG).d("Executing FFmpeg command: $ffmpegCommand")
            var session = FFmpegKit.execute(ffmpegCommand)

            if ((!ReturnCode.isSuccess(session.returnCode) || !tempOutputFile.exists() || tempOutputFile.length() <= 0L) && hasCover) {
                Timber.tag(TAG).w("FFmpeg conversion with cover failed, retrying without cover")
                if (tempOutputFile.exists()) tempOutputFile.delete()
                val fallbackCommand = buildFFmpegCommand(
                    inputFile = tempInputFile,
                    outputFile = tempOutputFile,
                    format = targetFormat,
                    title = title,
                    artist = artist,
                    album = album,
                    coverFile = null,
                )
                session = FFmpegKit.execute(fallbackCommand)
            }

            if (!ReturnCode.isSuccess(session.returnCode) || !tempOutputFile.exists() || tempOutputFile.length() <= 0L) {
                val errorLogs = session.allLogsAsString
                Timber.tag(TAG).e("FFmpeg conversion failed: %s", errorLogs)
                return@withContext ConversionResult(
                    success = false,
                    errorMessage = "FFmpeg dönüştürme hatası: ${session.failStackTrace ?: errorLogs.take(200)}",
                )
            }

            // 4. Save to MediaStore (Music/)
            val savedUri = saveToMediaStore(
                context = context,
                sourceFile = tempOutputFile,
                fileName = outputFileName,
                mimeType = targetFormat.mimeType,
                title = title,
                artist = artist,
                album = album,
            ) ?: return@withContext ConversionResult(
                success = false,
                errorMessage = "Dönüştürülen ses Müzik klasörüne kaydedilemedi.",
            )

            // 5. Optionally add to Local Library
            if (addToLibrary) {
                try {
                    val localSongId = savedUri.toString().replace("external_primary", "external")
                    val durationSec = try {
                        val mmr = android.media.MediaMetadataRetriever()
                        mmr.setDataSource(context, Uri.parse(localSongId))
                        val durStr = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                        mmr.release()
                        (durStr?.toLongOrNull()?.div(1000))?.toInt()
                    } catch (_: Exception) {
                        null
                    } ?: song?.song?.duration ?: ((tempOutputFile.length() / 16000L).toInt().coerceAtLeast(1))

                    val artistId = "local_artist_${artist.trim().lowercase()}"
                    val albumId = album.takeIf { it.isNotBlank() }?.let { "local_album_${it.trim().lowercase()}_$artistId" }

                    val newSongEntity = SongEntity(
                        id = localSongId,
                        title = title,
                        duration = durationSec,
                        thumbnailUrl = song?.song?.thumbnailUrl,
                        albumId = albumId,
                        albumName = album,
                        explicit = song?.song?.explicit ?: false,
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
                        if (albumId != null) {
                            val albumEntity = AlbumEntity(
                                id = albumId,
                                title = album,
                                songCount = 1,
                                duration = durationSec,
                                isLocal = true,
                                thumbnailUrl = song?.song?.thumbnailUrl,
                            )
                            insert(albumEntity)
                            insert(com.metrolist.music.db.entities.SongAlbumMap(songId = localSongId, albumId = albumId, index = 0))
                        }
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Could not add converted song to database library")
                }
            }

            ConversionResult(
                success = true,
                outputUri = savedUri,
                outputPath = outputFileName,
            )
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error converting audio format")
            ConversionResult(
                success = false,
                errorMessage = e.localizedMessage ?: "Bilinmeyen dönüştürme hatası",
            )
        } finally {
            // Cleanup temp files
            try {
                tempInputFile?.delete()
                tempOutputFile?.delete()
                tempCoverFile?.delete()
            } catch (_: Exception) {}
        }
    }

    private fun buildFFmpegCommand(
        inputFile: File,
        outputFile: File,
        format: AudioTargetFormat,
        title: String,
        artist: String,
        album: String,
        coverFile: File? = null,
    ): String {
        val inputPath = inputFile.absolutePath
        val outputPath = outputFile.absolutePath

        val cleanTitle = title.replace("\r", " ").replace("\n", " ").replace("\"", "\\\"").trim()
        val cleanArtist = artist.replace("\r", " ").replace("\n", " ").replace("\"", "\\\"").trim()
        val cleanAlbum = album.replace("\r", " ").replace("\n", " ").replace("\"", "\\\"").trim()

        val codecArgs = when (format) {
            AudioTargetFormat.FLAC -> "-c:a flac -compression_level 8"
            AudioTargetFormat.WAV -> "-c:a pcm_s16le"
            AudioTargetFormat.MP3 -> "-c:a libmp3lame -b:a 320k -q:a 0"
            AudioTargetFormat.M4A -> "-c:a aac -b:a 320k"
            AudioTargetFormat.OPUS -> "-c:a libopus -b:a 256k -vbr on"
        }

        if (coverFile != null && coverFile.exists() && coverFile.length() > 0L) {
            val coverPath = coverFile.absolutePath
            return when (format) {
                AudioTargetFormat.MP3 ->
                    "-y -i \"$inputPath\" -i \"$coverPath\" -map 0:a -map 1:0 $codecArgs -c:v:0 copy -id3v2_version 3 -disposition:v:0 attached_pic -metadata title=\"$cleanTitle\" -metadata artist=\"$cleanArtist\" -metadata album=\"$cleanAlbum\" \"$outputPath\""
                AudioTargetFormat.M4A ->
                    "-y -i \"$inputPath\" -i \"$coverPath\" -map 0:a -map 1:0 $codecArgs -c:v:0 copy -disposition:v:0 attached_pic -metadata title=\"$cleanTitle\" -metadata artist=\"$cleanArtist\" -metadata album=\"$cleanAlbum\" \"$outputPath\""
                AudioTargetFormat.FLAC ->
                    "-y -i \"$inputPath\" -i \"$coverPath\" -map 0:a -map 1:0 $codecArgs -c:v:0 copy -disposition:v:0 attached_pic -metadata title=\"$cleanTitle\" -metadata artist=\"$cleanArtist\" -metadata album=\"$cleanAlbum\" \"$outputPath\""
                AudioTargetFormat.OPUS ->
                    "-y -i \"$inputPath\" -i \"$coverPath\" -map 0:a -map 1:0 $codecArgs -c:v:0 mjpeg -disposition:v:0 attached_pic -metadata title=\"$cleanTitle\" -metadata artist=\"$cleanArtist\" -metadata album=\"$cleanAlbum\" \"$outputPath\""
                AudioTargetFormat.WAV ->
                    "-y -i \"$inputPath\" -vn $codecArgs -metadata title=\"$cleanTitle\" -metadata artist=\"$cleanArtist\" -metadata album=\"$cleanAlbum\" \"$outputPath\""
            }
        }

        return "-y -i \"$inputPath\" -vn $codecArgs -metadata title=\"$cleanTitle\" -metadata artist=\"$cleanArtist\" -metadata album=\"$cleanAlbum\" \"$outputPath\""
    }

    private suspend fun prepareSourceAudio(
        context: Context,
        mediaId: String,
        workingDir: File,
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
        val tempInput = File(workingDir, "src_${System.currentTimeMillis()}$ext")

        // 1. Local file or content URI
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

        // 2. Exported song in Music/ directory
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

        // 3. Download cache spans
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

        // 3. Fallback: Fetch stream online via InnerTubeX
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
                Timber.tag(TAG).e(e, "Online stream fetch for conversion failed")
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

            // Notify MediaScanner so file managers index the new file immediately
            try {
                resolver.query(destUri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val dataIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                        if (dataIdx != -1) {
                            val path = cursor.getString(dataIdx)
                            if (!path.isNullOrBlank()) {
                                android.media.MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf(mimeType), null)
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
            android.media.MediaScannerConnection.scanFile(context, arrayOf(targetFile.absolutePath), arrayOf(mimeType), null)
            return Uri.fromFile(targetFile)
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .take(100)
    }
}
