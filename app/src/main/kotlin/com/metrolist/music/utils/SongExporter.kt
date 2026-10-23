package com.metrolist.music.utils

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.media3.datasource.cache.Cache
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.metrolist.music.db.MusicDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

object SongExporter {
    private const val TAG = "SongExporter"
    const val SUBDIRECTORY = "TaşkınMusic"
    const val LEGACY_SUBDIRECTORY = "TaskinMusic"

    fun getTargetSubdirectory(): String {
        val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        val trDir = File(musicDir, SUBDIRECTORY)
        val enDir = File(musicDir, LEGACY_SUBDIRECTORY)
        return when {
            trDir.exists() && trDir.isDirectory -> SUBDIRECTORY
            enDir.exists() && enDir.isDirectory -> LEGACY_SUBDIRECTORY
            else -> SUBDIRECTORY
        }
    }

    /**
     * Exports a downloaded song from the app's cache to the public Music directory via MediaStore.
     * Embeds physical audio tags (title, artist, album) and album cover artwork directly into the file.
     */
    suspend fun exportSongToMusicFolder(
        context: Context,
        mediaId: String,
        downloadCache: Cache,
        database: MusicDatabase,
    ): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val song = database.getSongByIdBlocking(mediaId)
                ?: error("Song not found in database: $mediaId")
            val format = song.format ?: database.format(mediaId).firstOrNull()

            val mimeType = format?.mimeType?.takeIf { it.isNotBlank() } ?: "audio/mp4"
            val extension = when {
                mimeType.contains("mp4", ignoreCase = true) || mimeType.contains("m4a", ignoreCase = true) -> "m4a"
                mimeType.contains("webm", ignoreCase = true) || mimeType.contains("opus", ignoreCase = true) -> "opus"
                mimeType.contains("mpeg", ignoreCase = true) || mimeType.contains("mp3", ignoreCase = true) -> "mp3"
                else -> "m4a"
            }

            val rawArtist = song.artists.joinToString(", ") { it.name }.takeIf { it.isNotBlank() } ?: "Taskin Music"
            val rawTitle = song.song.title.takeIf { it.isNotBlank() } ?: "Music"
            val rawAlbum = song.album?.title ?: "Taşkın Müzik"

            val safeArtist = sanitizeFileName(rawArtist)
            val safeTitle = sanitizeFileName(rawTitle)
            val fileName = "$safeArtist - $safeTitle.$extension"

            val spans = downloadCache.getCachedSpans(mediaId).sortedBy { it.position }
            if (spans.isEmpty() || !spans.all { it.file != null && it.file!!.exists() }) {
                error("No complete cached spans found for $mediaId")
            }

            val workingDir = File(context.cacheDir, "export_${System.currentTimeMillis()}_${(1000..9999).random()}").apply { mkdirs() }
            val tempRawInput = File(workingDir, "raw_in.$extension")
            val tempTaggedOutput = File(workingDir, "tagged_out.$extension")
            val tempCover = File(workingDir, "cover.jpg")

            try {
                FileOutputStream(tempRawInput).use { out ->
                    for (span in spans) {
                        span.file?.inputStream()?.use { input ->
                            input.copyTo(out)
                        }
                    }
                }

                val coverUrl = song.song.thumbnailUrl ?: song.album?.thumbnailUrl
                val coverFetched = fetchCoverImage(context, coverUrl, tempCover)

                val taggedSuccess = embedMetadataAndCover(
                    inputFile = tempRawInput,
                    coverFile = if (coverFetched) tempCover else null,
                    outputFile = tempTaggedOutput,
                    extension = extension,
                    title = rawTitle,
                    artist = rawArtist,
                    album = rawAlbum,
                )

                val fileToSave = if (taggedSuccess && tempTaggedOutput.exists() && tempTaggedOutput.length() > 0L) {
                    tempTaggedOutput
                } else {
                    tempRawInput
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val resolver = context.contentResolver
                    val relativePath = "${Environment.DIRECTORY_MUSIC}/"

                    val values = ContentValues().apply {
                        put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                        put(MediaStore.Audio.Media.TITLE, rawTitle)
                        put(MediaStore.Audio.Media.ARTIST, rawArtist)
                        put(MediaStore.Audio.Media.ALBUM, rawAlbum)
                        put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
                        put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath)
                        put(MediaStore.Audio.Media.IS_PENDING, 1)
                        if (song.song.duration > 0) {
                            put(MediaStore.Audio.Media.DURATION, song.song.duration * 1000L)
                        }
                    }

                    val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    val destUri = resolver.insert(collection, values)
                        ?: error("Failed to insert MediaStore audio record")

                    resolver.openOutputStream(destUri)?.use { out ->
                        fileToSave.inputStream().use { input ->
                            input.copyTo(out)
                        }
                    } ?: error("Failed to open output stream for $destUri")

                    val completeValues = ContentValues().apply {
                        put(MediaStore.Audio.Media.IS_PENDING, 0)
                    }
                    resolver.update(destUri, completeValues, null, null)

                    val actualPath = try {
                        resolver.query(destUri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val dataIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                                if (dataIdx != -1) cursor.getString(dataIdx) else null
                            } else null
                        }
                    } catch (_: Exception) { null }

                    if (!actualPath.isNullOrBlank()) {
                        android.media.MediaScannerConnection.scanFile(context, arrayOf(actualPath), arrayOf(mimeType), null)
                    }

                    registerExportedSong(
                        context = context,
                        mediaId = mediaId,
                        contentUri = destUri.toString(),
                        dataPath = actualPath,
                        fileName = fileName,
                    )

                    Timber.tag(TAG).i("Exported $fileName with embedded tags/cover to MediaStore: $destUri")
                    destUri
                } else {
                    @Suppress("DEPRECATION")
                    val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                    if (!musicDir.exists()) musicDir.mkdirs()
                    val targetFile = File(musicDir, fileName)
                    FileOutputStream(targetFile).use { out ->
                        fileToSave.inputStream().use { input ->
                            input.copyTo(out)
                        }
                    }
                    android.media.MediaScannerConnection.scanFile(context, arrayOf(targetFile.absolutePath), arrayOf(mimeType), null)
                    val destUri = Uri.fromFile(targetFile)
                    registerExportedSong(
                        context = context,
                        mediaId = mediaId,
                        contentUri = destUri.toString(),
                        dataPath = targetFile.absolutePath,
                        fileName = fileName,
                    )
                    Timber.tag(TAG).i("Exported $fileName with embedded tags/cover to storage: $destUri")
                    destUri
                }
            } finally {
                try {
                    workingDir.deleteRecursively()
                } catch (_: Exception) {}
            }
        }.onFailure { e ->
            Timber.tag(TAG).e(e, "Failed to export song $mediaId to Music folder")
        }
    }

    internal suspend fun fetchCoverImage(context: Context, url: String?, outputFile: File): Boolean =
        withContext(Dispatchers.IO) {
            if (url.isNullOrBlank()) return@withContext false
            // 1. Try Coil image loader first (checks memory/disk cache and network)
            try {
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .allowHardware(false)
                    .build()
                val result = context.imageLoader.execute(request)
                if (result is SuccessResult) {
                    val bitmap = result.image.toBitmap()
                    FileOutputStream(outputFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                    }
                    if (outputFile.exists() && outputFile.length() > 0L) {
                        return@withContext true
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Coil failed to fetch cover from $url")
            }

            // 2. Direct HTTP download fallback
            try {
                val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.doInput = true
                connection.connect()
                if (connection.responseCode in 200..299) {
                    val bitmap = BitmapFactory.decodeStream(connection.inputStream)
                    if (bitmap != null) {
                        FileOutputStream(outputFile).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                        }
                        if (outputFile.exists() && outputFile.length() > 0L) {
                            return@withContext true
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Direct HTTP failed to fetch cover from $url")
            }

            false
        }

    private fun embedMetadataAndCover(
        inputFile: File,
        coverFile: File?,
        outputFile: File,
        extension: String,
        title: String,
        artist: String,
        album: String,
    ): Boolean {
        val inputPath = inputFile.absolutePath
        val outputPath = outputFile.absolutePath
        val cleanTitle = cleanMetadata(title)
        val cleanArtist = cleanMetadata(artist)
        val cleanAlbum = cleanMetadata(album)

        val hasCover = coverFile != null && coverFile.exists() && coverFile.length() > 0L
        val coverPath = coverFile?.absolutePath

        val commandsToTry = mutableListOf<String>()

        if (hasCover && coverPath != null) {
            when {
                extension.equals("mp3", ignoreCase = true) -> {
                    commandsToTry.add(
                        "-y -i \"$inputPath\" -i \"$coverPath\" -map 0:a -map 1:0 -c:a copy -c:v:0 copy -id3v2_version 3 -disposition:v:0 attached_pic -metadata title=\"$cleanTitle\" -metadata artist=\"$cleanArtist\" -metadata album=\"$cleanAlbum\" \"$outputPath\""
                    )
                }
                extension.equals("opus", ignoreCase = true) || extension.equals("ogg", ignoreCase = true) -> {
                    commandsToTry.add(
                        "-y -i \"$inputPath\" -i \"$coverPath\" -map 0:a -map 1:0 -c:a copy -c:v:0 mjpeg -disposition:v:0 attached_pic -metadata title=\"$cleanTitle\" -metadata artist=\"$cleanArtist\" -metadata album=\"$cleanAlbum\" \"$outputPath\""
                    )
                }
                else -> {
                    // M4A / MP4
                    commandsToTry.add(
                        "-y -i \"$inputPath\" -i \"$coverPath\" -map 0:a -map 1:0 -c:a copy -c:v:0 copy -disposition:v:0 attached_pic -metadata title=\"$cleanTitle\" -metadata artist=\"$cleanArtist\" -metadata album=\"$cleanAlbum\" \"$outputPath\""
                    )
                    commandsToTry.add(
                        "-y -i \"$inputPath\" -i \"$coverPath\" -map 0:a -map 1:0 -c:a copy -c:v:0 mjpeg -disposition:v:0 attached_pic -metadata title=\"$cleanTitle\" -metadata artist=\"$cleanArtist\" -metadata album=\"$cleanAlbum\" \"$outputPath\""
                    )
                }
            }
        }

        // Fallback without cover (metadata tags only)
        if (extension.equals("mp3", ignoreCase = true)) {
            commandsToTry.add(
                "-y -i \"$inputPath\" -map 0:a -c:a copy -id3v2_version 3 -metadata title=\"$cleanTitle\" -metadata artist=\"$cleanArtist\" -metadata album=\"$cleanAlbum\" \"$outputPath\""
            )
        } else {
            commandsToTry.add(
                "-y -i \"$inputPath\" -map 0:a -c:a copy -metadata title=\"$cleanTitle\" -metadata artist=\"$cleanArtist\" -metadata album=\"$cleanAlbum\" \"$outputPath\""
            )
        }

        for (cmd in commandsToTry) {
            Timber.tag(TAG).d("Running FFmpeg tag embed: $cmd")
            val session = FFmpegKit.execute(cmd)
            if (ReturnCode.isSuccess(session.returnCode) && outputFile.exists() && outputFile.length() > 0L) {
                Timber.tag(TAG).i("Successfully embedded metadata/cover into $outputPath")
                return true
            } else {
                Timber.tag(TAG).w("FFmpeg tag command failed (${session.returnCode}): ${session.allLogsAsString?.take(200)}")
                if (outputFile.exists()) {
                    outputFile.delete()
                }
            }
        }

        return false
    }

    private fun cleanMetadata(value: String): String {
        return value.replace("\r", " ")
            .replace("\n", " ")
            .replace("\"", "\\\"")
            .replace("$", "\\$")
            .replace("`", "'")
            .trim()
    }

    private const val PREFS_NAME = "exported_songs"

    fun registerExportedSong(
        context: Context,
        mediaId: String,
        contentUri: String? = null,
        dataPath: String? = null,
        fileName: String? = null,
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putString("id_to_id_$mediaId", mediaId)
        if (!contentUri.isNullOrBlank()) {
            val normUri = contentUri.replace("external_primary", "external")
            editor.putString("id_to_uri_$mediaId", normUri)
            editor.putString("uri_to_id_$normUri", mediaId)
            editor.putString("uri_to_id_$contentUri", mediaId)
        }
        if (!dataPath.isNullOrBlank()) {
            editor.putString("id_to_path_$mediaId", dataPath)
            editor.putString("path_to_id_$dataPath", mediaId)
        }
        if (!fileName.isNullOrBlank()) {
            editor.putString("name_to_id_${fileName.lowercase().trim()}", mediaId)
        }
        editor.apply()
    }

    fun isExportedSong(
        context: Context,
        contentUri: String? = null,
        dataPath: String? = null,
        displayName: String? = null,
    ): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!contentUri.isNullOrBlank()) {
            val normUri = contentUri.replace("external_primary", "external")
            if (prefs.contains("uri_to_id_$normUri") || prefs.contains("uri_to_id_$contentUri")) return true
        }
        if (!dataPath.isNullOrBlank()) {
            if (prefs.contains("path_to_id_$dataPath")) return true
        }
        if (!displayName.isNullOrBlank()) {
            if (prefs.contains("name_to_id_${displayName.lowercase().trim()}")) return true
        }
        return false
    }

    fun findExportedSongUri(context: Context, mediaId: String): Uri? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val uriStr = prefs.getString("id_to_uri_$mediaId", null)
        if (!uriStr.isNullOrBlank()) {
            try {
                val uri = Uri.parse(uriStr)
                context.contentResolver.openFileDescriptor(uri, "r")?.use {
                    return uri
                }
            } catch (_: Exception) {}
        }
        val pathStr = prefs.getString("id_to_path_$mediaId", null)
        if (!pathStr.isNullOrBlank()) {
            val file = File(pathStr)
            if (file.exists() && file.length() > 0) {
                return Uri.fromFile(file)
            }
        }
        // Fallback: search Music directory for any matching audio file
        try {
            val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            val candidates = listOf(musicDir, File(musicDir, SUBDIRECTORY), File(musicDir, LEGACY_SUBDIRECTORY))
            for (dir in candidates) {
                if (dir.exists() && dir.isDirectory) {
                    val match = dir.listFiles()?.firstOrNull { it.isFile && (it.name.contains(mediaId) || it.name.contains("(_${mediaId}_)")) }
                    if (match != null && match.length() > 0) {
                        registerExportedSong(context, mediaId, dataPath = match.absolutePath, fileName = match.name)
                        return Uri.fromFile(match)
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    fun deleteExportedSong(context: Context, mediaId: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val uriStr = prefs.getString("id_to_uri_$mediaId", null)
        val pathStr = prefs.getString("id_to_path_$mediaId", null)

        if (!uriStr.isNullOrBlank()) {
            try {
                context.contentResolver.delete(Uri.parse(uriStr), null, null)
            } catch (_: Exception) {}
        }
        if (!pathStr.isNullOrBlank()) {
            try {
                File(pathStr).delete()
            } catch (_: Exception) {}
        }
        prefs.edit()
            .remove("id_to_uri_$mediaId")
            .remove("id_to_path_$mediaId")
            .remove("id_to_id_$mediaId")
            .apply()
    }

    /**
     * Prepares an audio file in cache or uses existing local file and returns a content URI suitable for sharing.
     */
    suspend fun getShareableAudioUri(
        context: Context,
        mediaId: String,
        downloadCache: Cache,
        database: MusicDatabase,
    ): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            val song = database.getSongByIdBlocking(mediaId) ?: return@runCatching null

            // If it's a local song with an accessible content Uri or file path
            if (song.song.isLocal) {
                if (song.id.startsWith("content://")) {
                    return@runCatching Uri.parse(song.id)
                } else {
                    val localFile = File(song.id)
                    if (localFile.exists()) {
                        return@runCatching androidx.core.content.FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.FileProvider",
                            localFile,
                        )
                    }
                }
            }

            // Check cached ExoPlayer spans
            val spans = downloadCache.getCachedSpans(mediaId).sortedBy { it.position }
            if (spans.isNotEmpty() && spans.all { it.file != null && it.file!!.exists() }) {
                val shareDir = File(context.cacheDir, "shared_audio").apply { if (!exists()) mkdirs() }
                val rawArtist = song.artists.joinToString(", ") { it.name }.takeIf { it.isNotBlank() } ?: "Taşkın Music"
                val rawTitle = song.song.title.takeIf { it.isNotBlank() } ?: "Music"
                val safeArtist = sanitizeFileName(rawArtist)
                val safeTitle = sanitizeFileName(rawTitle)
                val targetFile = File(shareDir, "$safeArtist - $safeTitle.mp3")

                FileOutputStream(targetFile).use { out ->
                    for (span in spans) {
                        span.file?.inputStream()?.use { input ->
                            input.copyTo(out)
                        }
                    }
                }

                return@runCatching androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.FileProvider",
                    targetFile,
                )
            }
            null
        }.getOrNull()
    }

    fun deleteExportedSong(
        context: Context,
        rawTitle: String,
        rawArtist: String,
        extension: String? = null,
    ) {
        val safeArtist = sanitizeFileName(rawArtist)
        val safeTitle = sanitizeFileName(rawTitle)
        val baseFileName = "$safeArtist - $safeTitle"
        val displayNameTarget = if (extension != null) "$baseFileName.$extension" else "$baseFileName.%"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val selection = if (extension != null) {
                "${MediaStore.Audio.Media.DISPLAY_NAME} = ? AND (${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?)"
            } else {
                "${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ? AND (${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?)"
            }
            val selectionArgs = arrayOf(displayNameTarget, "%${Environment.DIRECTORY_MUSIC}%", "%$SUBDIRECTORY%", "%$LEGACY_SUBDIRECTORY%")
            try {
                context.contentResolver.delete(collection, selection, selectionArgs)
            } catch (e: Exception) {
                Timber.w(e, "Could not delete MediaStore record for $displayNameTarget")
            }
        } else {
            @Suppress("DEPRECATION")
            val baseMusicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            val dirsToCheck = listOf(
                baseMusicDir,
                File(baseMusicDir, SUBDIRECTORY),
                File(baseMusicDir, LEGACY_SUBDIRECTORY),
            )
            dirsToCheck.forEach { musicDir ->
                if (musicDir.exists()) {
                    if (extension != null) {
                        File(musicDir, "$baseFileName.$extension").takeIf { it.exists() }?.delete()
                    } else {
                        musicDir.listFiles()?.filter { it.name.startsWith(baseFileName) }?.forEach { it.delete() }
                    }
                }
            }
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .take(100)
    }
}
