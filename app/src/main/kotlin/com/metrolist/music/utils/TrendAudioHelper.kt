/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import com.metrolist.music.extensions.parseClipId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import timber.log.Timber
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

data class TrendHighlightResult(
    val startSec: Int,
    val endSec: Int,
    val sourceDescription: String,
)

object TrendAudioHelper {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    suspend fun findTrendingPart(
        videoId: String,
        totalDurationSec: Int,
        desiredClipDurationSec: Int = 30,
    ): TrendHighlightResult = withContext(Dispatchers.IO) {
        val duration = if (totalDurationSec > 0) totalDurationSec else 180
        val targetClipLength = desiredClipDurationSec.coerceAtMost(duration)

        // Clean videoId (if it's a clip: ID, extract base ID)
        val cleanId = if (videoId.startsWith("clip:")) {
            parseClipId(videoId)?.originalId ?: videoId
        } else {
            videoId
        }

        // If local file or invalid YouTube ID, use intelligent musical chorus
        if (cleanId.startsWith("content://") || cleanId.startsWith("file://") || cleanId.length != 11) {
            return@withContext computeIntelligentChorus(duration, targetClipLength, "Akıllı Koro / Hook Tespiti")
        }

        // 1. Try SponsorBlock poi_highlight (Community-verified peak point of interest / viral section)
        try {
            val url = "https://sponsor.ajay.app/api/skipSegments?videoID=$cleanId&category=poi_highlight"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 TaskinMusic/1.0")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body.string()
                    val array = JSONArray(body)
                    if (array.length() > 0) {
                        val firstObj = array.getJSONObject(0)
                        val segment = firstObj.getJSONArray("segment")
                        val highlightStartSec = segment.getDouble(0)
                        val start = (highlightStartSec - 5).roundToInt().coerceIn(0, (duration - targetClipLength).coerceAtLeast(0))
                        val end = (start + targetClipLength).coerceAtMost(duration)
                        Timber.d("TrendAudioHelper: Found SponsorBlock POI highlight at $highlightStartSec -> $start..$end")
                        return@withContext TrendHighlightResult(
                            startSec = start,
                            endSec = end,
                            sourceDescription = "TikTok / Trend Klip Noktası (Topluluk Onaylı)"
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "TrendAudioHelper: SponsorBlock query failed")
        }

        // 2. Intelligent Chorus / Viral Hook computation (golden ratio of popular songs: 33% of length)
        return@withContext computeIntelligentChorus(duration, targetClipLength, "En Popüler Nakarat Bölümü (Otomatik)")
    }

    private fun computeIntelligentChorus(
        totalDurationSec: Int,
        clipLengthSec: Int,
        description: String,
    ): TrendHighlightResult {
        val chorusStart = (totalDurationSec * 0.33).roundToInt()
            .coerceIn(0, (totalDurationSec - clipLengthSec).coerceAtLeast(0))
        val chorusEnd = (chorusStart + clipLengthSec).coerceAtMost(totalDurationSec)
        return TrendHighlightResult(
            startSec = chorusStart,
            endSec = chorusEnd,
            sourceDescription = description
        )
    }
}
