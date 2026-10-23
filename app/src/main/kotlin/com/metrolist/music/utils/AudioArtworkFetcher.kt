/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.ContentUris
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.core.net.toUri
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Custom Coil 3 fetcher that extracts high-quality album art for local audio files:
 * 1. Android Q+ MediaStore ContentResolver.loadThumbnail for fast cached thumbnails
 * 2. MediaMetadataRetriever embeddedPicture for ID3 / MP4 embedded APIC album covers
 * 3. Legacy content://media/external/audio/albumart stream with album ID fallback
 */
class AudioArtworkFetcher(
    private val context: Context,
    private val uri: Uri,
) : Fetcher {

    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        val uriString = uri.toString()

        // 1. Handle album art URI: content://media/.../albumart/{albumId}
        if (uriString.contains("/albumart")) {
            // First try opening legacy stream directly
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val bitmap = BitmapFactory.decodeStream(stream)
                    if (bitmap != null) {
                        return@withContext ImageFetchResult(
                            image = bitmap.asImage(),
                            isSampled = false,
                            dataSource = DataSource.DISK,
                        )
                    }
                }
            } catch (_: Exception) { }

            // Find a song belonging to this album and load its thumbnail or embedded artwork
            val albumId = uri.lastPathSegment?.toLongOrNull()
            if (albumId != null) {
                try {
                    val projection = arrayOf(MediaStore.Audio.Media._ID)
                    val selection = "${MediaStore.Audio.Media.ALBUM_ID} = ?"
                    val args = arrayOf(albumId.toString())
                    context.contentResolver.query(
                        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
                        projection,
                        selection,
                        args,
                        null,
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val songId = cursor.getLong(0)
                            val songUri = ContentUris.withAppendedId(
                                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                                songId,
                            )
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                try {
                                    val bitmap = context.contentResolver.loadThumbnail(songUri, Size(512, 512), null)
                                    return@withContext ImageFetchResult(
                                        image = bitmap.asImage(),
                                        isSampled = false,
                                        dataSource = DataSource.DISK,
                                    )
                                } catch (_: Exception) { }
                            }
                            try {
                                MediaMetadataRetriever().use { mmr ->
                                    mmr.setDataSource(context, songUri)
                                    val picture = mmr.embeddedPicture
                                    if (picture != null) {
                                        val bitmap = BitmapFactory.decodeByteArray(picture, 0, picture.size)
                                        if (bitmap != null) {
                                            return@withContext ImageFetchResult(
                                                image = bitmap.asImage(),
                                                isSampled = false,
                                                dataSource = DataSource.DISK,
                                            )
                                        }
                                    }
                                }
                            } catch (_: Exception) { }
                        }
                    }
                } catch (_: Exception) { }
            }
        }

        // 2. Handle audio song URI (content://media/.../audio/media/{id} or file:// or local path)
        if (uriString.contains("/audio/media") ||
            uriString.startsWith("file://") ||
            uriString.startsWith("/") ||
            (uri.scheme == "content" && uriString.contains("audio"))
        ) {
            // Try ContentResolver.loadThumbnail on Android Q+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.scheme == "content") {
                try {
                    val bitmap = context.contentResolver.loadThumbnail(uri, Size(512, 512), null)
                    return@withContext ImageFetchResult(
                        image = bitmap.asImage(),
                        isSampled = false,
                        dataSource = DataSource.DISK,
                    )
                } catch (_: Exception) { }
            }

            // Try MediaMetadataRetriever to extract embedded artwork (ID3 APIC frame / MP4 covr)
            try {
                MediaMetadataRetriever().use { mmr ->
                    if (uri.scheme == "content") {
                        mmr.setDataSource(context, uri)
                    } else {
                        val path = if (uriString.startsWith("file://")) uriString.removePrefix("file://") else uriString
                        mmr.setDataSource(path)
                    }
                    val picture = mmr.embeddedPicture
                    if (picture != null) {
                        val bitmap = BitmapFactory.decodeByteArray(picture, 0, picture.size)
                        if (bitmap != null) {
                            return@withContext ImageFetchResult(
                                image = bitmap.asImage(),
                                isSampled = false,
                                dataSource = DataSource.DISK,
                            )
                        }
                    }

                    // Online fallback 1: Check YouTube ID in URI/display name
                    val ytMatch = Regex("""\(_([a-zA-Z0-9_-]{11})_\)""").find(uriString)
                        ?: Regex("""\(([a-zA-Z0-9_-]{11})\)""").find(uriString)
                    if (ytMatch != null) {
                        val videoId = ytMatch.groupValues[1]
                        val ytBitmap = fetchBitmapFromUrl("https://i.ytimg.com/vi/$videoId/hqdefault.jpg")
                        if (ytBitmap != null) {
                            return@withContext ImageFetchResult(
                                image = ytBitmap.asImage(),
                                isSampled = false,
                                dataSource = DataSource.NETWORK,
                            )
                        }
                    }

                    // Online fallback 2: Search iTunes API by title and artist
                    val title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    val artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    val query = listOfNotNull(artist, title).filter { it.isNotBlank() && it != "<unknown>" }.joinToString(" ")
                    if (query.isNotBlank()) {
                        val onlineBitmap = fetchOnlineArtwork(query)
                        if (onlineBitmap != null) {
                            return@withContext ImageFetchResult(
                                image = onlineBitmap.asImage(),
                                isSampled = false,
                                dataSource = DataSource.NETWORK,
                            )
                        }
                    }
                }
            } catch (_: Exception) { }
        }

        null
    }

    private fun fetchBitmapFromUrl(urlString: String): android.graphics.Bitmap? {
        return try {
            val url = java.net.URL(urlString)
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 3000
            connection.readTimeout = 4000
            connection.inputStream.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchOnlineArtwork(query: String): android.graphics.Bitmap? {
        return try {
            val searchUrl = "https://itunes.apple.com/search?term=${java.net.URLEncoder.encode(query, "UTF-8")}&entity=song&limit=1"
            val conn = java.net.URL(searchUrl).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 4000
            val json = conn.inputStream.bufferedReader().use { it.readText() }
            val match = Regex(""""artworkUrl100"\s*:\s*"([^"]+)"""").find(json)
            if (match != null) {
                val artUrl = match.groupValues[1].replace("100x100bb.jpg", "600x600bb.jpg")
                fetchBitmapFromUrl(artUrl)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    class Factory(private val context: Context) : Fetcher.Factory<coil3.Uri> {
        override fun create(data: coil3.Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val scheme = data.scheme
            val dataStr = data.toString()
            if (scheme == "content" && (dataStr.contains("audio") || dataStr.contains("albumart"))) {
                return AudioArtworkFetcher(context, Uri.parse(dataStr))
            }
            if ((scheme == "file" || dataStr.startsWith("/")) &&
                (dataStr.endsWith(".mp3", ignoreCase = true) ||
                 dataStr.endsWith(".m4a", ignoreCase = true) ||
                 dataStr.endsWith(".flac", ignoreCase = true) ||
                 dataStr.endsWith(".opus", ignoreCase = true) ||
                 dataStr.endsWith(".ogg", ignoreCase = true))
            ) {
                return AudioArtworkFetcher(context, Uri.parse(dataStr))
            }
            return null
        }
    }

    class AndroidUriFactory(private val context: Context) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val scheme = data.scheme
            val dataStr = data.toString()
            if (scheme == "content" && (dataStr.contains("audio") || dataStr.contains("albumart"))) {
                return AudioArtworkFetcher(context, data)
            }
            if ((scheme == "file" || dataStr.startsWith("/")) &&
                (dataStr.endsWith(".mp3", ignoreCase = true) ||
                 dataStr.endsWith(".m4a", ignoreCase = true) ||
                 dataStr.endsWith(".flac", ignoreCase = true) ||
                 dataStr.endsWith(".opus", ignoreCase = true) ||
                 dataStr.endsWith(".ogg", ignoreCase = true))
            ) {
                return AudioArtworkFetcher(context, data)
            }
            return null
        }
    }
}
