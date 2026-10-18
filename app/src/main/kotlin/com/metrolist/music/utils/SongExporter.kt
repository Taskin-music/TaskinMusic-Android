package com.metrolist.music.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.media3.datasource.cache.Cache
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
                    for (span in spans) {
                        span.file?.inputStream()?.use { input ->
                            input.copyTo(out)
                        }
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

                Timber.tag(TAG).i("Exported $fileName to MediaStore: $destUri")
                destUri
            } else {
                @Suppress("DEPRECATION")
                val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                if (!musicDir.exists()) musicDir.mkdirs()
                val targetFile = File(musicDir, fileName)
                FileOutputStream(targetFile).use { out ->
                    for (span in spans) {
                        span.file?.inputStream()?.use { input ->
                            input.copyTo(out)
                        }
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
                Timber.tag(TAG).i("Exported $fileName to storage: $destUri")
                destUri
            }
        }.onFailure { e ->
            Timber.tag(TAG).e(e, "Failed to export song $mediaId to Music folder")
        }
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
