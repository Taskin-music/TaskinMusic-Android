/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import androidx.media3.exoplayer.offline.Download
import com.metrolist.music.db.entities.Song
import java.util.Locale

object SongInstallChecker {
    /**
     * Normalizes a string for lenient title and artist matching:
     * - lowercases with Turkish-safe rules
     * - strips noise like (Official Music Video), (Audio), [HD], etc.
     * - removes all punctuation and non-alphanumeric characters
     */
    fun normalize(input: String?): String {
        if (input.isNullOrBlank()) return ""
        var s = input.lowercase(Locale("tr", "TR"))
            .replace('ı', 'i')
            .replace('ğ', 'g')
            .replace('ü', 'u')
            .replace('ş', 's')
            .replace('ö', 'o')
            .replace('ç', 'c')

        // Remove video suffixes and brackets
        s = s.replace(Regex("""\(_[a-za-z0-9_-]{11}_\)"""), "")
            .replace(Regex("""\([a-za-z0-9_-]{11}\)"""), "")
            .replace(Regex("""\[[a-za-z0-9_-]{11}\]"""), "")
            .replace(Regex("""\([^)]*(official|video|audio|lyric|remaster|live|klip|hd|hq|canli)[^)]*\)"""), "")
            .replace(Regex("""\[[^\]]*(official|video|audio|lyric|remaster|live|klip|hd|hq|canli)[^\]]*\]"""), "")
            .replace("official music video", "")
            .replace("official video", "")
            .replace("official audio", "")
            .replace("lyric video", "")
            .replace("lyrics", "")
            .replace("feat.", "")
            .replace("feat", "")
            .replace("ft.", "")
            .replace("ft", "")

        // Keep only alphanumeric characters
        return s.filter { it.isLetterOrDigit() }
    }

    /**
     * Checks if a song is installed/downloaded either in local storage or ExoPlayer cache.
     */
    fun isInstalled(
        title: String,
        artist: String? = null,
        videoId: String? = null,
        installedSongs: List<Song>,
        downloads: Map<String, Download>? = null,
    ): Boolean {
        // 1. Direct ID check
        if (!videoId.isNullOrBlank()) {
            if (downloads?.get(videoId)?.state == Download.STATE_COMPLETED) return true
            if (installedSongs.any { (it.song.isDownloaded || it.song.isLocal) && (it.id == videoId || it.id.contains(videoId)) }) return true
        }

        // 2. Direct check in downloads map by title if available
        if (downloads != null) {
            val normCandidateTitle = normalize(title)
            downloads.values.filter { it.state == Download.STATE_COMPLETED }.forEach { dl ->
                val dlTitle = runCatching { String(dl.request.data) }.getOrNull()
                if (!dlTitle.isNullOrBlank() && normalize(dlTitle) == normCandidateTitle) {
                    return true
                }
            }
        }

        val normTitle = normalize(title)
        if (normTitle.isBlank()) return false
        val normArtist = normalize(artist)
        val combinedCandidate = "$normArtist$normTitle"

        for (song in installedSongs) {
            if (!song.song.isDownloaded && !song.song.isLocal) continue

            // If ID matches
            if (!videoId.isNullOrBlank() && (song.id == videoId || song.id.contains(videoId))) {
                return true
            }

            val sTitle = normalize(song.song.title)
            val sArtist = normalize(song.artists.firstOrNull()?.name)
            val combinedInstalled = "$sArtist$sTitle"

            // Exact title match
            if (sTitle == normTitle) {
                // If both have artist, ensure they don't contradict
                if (normArtist.isNotBlank() && sArtist.isNotBlank()) {
                    if (sArtist.contains(normArtist) || normArtist.contains(sArtist)) return true
                } else {
                    return true
                }
            }

            // Substring or combined match e.g. "Ahmet Kaya - Ayrılığın Hediyesi" matching "Ayrılığın Hediyesi"
            if (combinedCandidate.isNotBlank() && (combinedInstalled == combinedCandidate ||
                        combinedInstalled.contains(combinedCandidate) ||
                        combinedCandidate.contains(combinedInstalled))) {
                return true
            }

            // If candidate title is a substantial substring of the installed title or vice versa
            if (normTitle.length >= 5 && (sTitle.contains(normTitle) || normTitle.contains(sTitle))) {
                if (normArtist.isBlank() || sArtist.isBlank() || sArtist.contains(normArtist) || normArtist.contains(sArtist)) {
                    return true
                }
            }
        }

        return false
    }
}
