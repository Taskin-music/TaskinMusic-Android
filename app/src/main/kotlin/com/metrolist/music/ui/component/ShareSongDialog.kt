package com.metrolist.music.ui.component

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalDownloadUtil
import com.metrolist.music.R
import com.metrolist.music.utils.SongExporter
import kotlinx.coroutines.launch
import java.net.URLEncoder

@Composable
fun ShareSongDialog(
    songId: String,
    songTitle: String,
    songArtist: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val downloadUtil = LocalDownloadUtil.current
    val coroutineScope = rememberCoroutineScope()
    var isPreparingFile by remember { mutableStateOf(false) }

    val encodedTitle = URLEncoder.encode(songTitle, "UTF-8")
    val encodedArtist = URLEncoder.encode(songArtist, "UTF-8")
    val encodedSearch = URLEncoder.encode("$songArtist $songTitle", "UTF-8")

    // 1. Direct Web MP3 download link (Opens directly in any browser for 1-tap MP3 downloading)
    val webMp3Link = "https://y2mate.nu/tr/?v=$songId"
    val webMp3ShareText = buildString {
        appendLine("🎵 $songArtist - $songTitle")
        appendLine()
        appendLine("⬇️ Şarkıyı MP3 Olarak İndir (Tarayıcıda Aç):")
        append(webMp3Link)
    }

    // 2. Taşkın Music App Deep Link
    val taskinAppWebLink = "https://metrolist.cc/download?id=$songId&title=$encodedTitle&artist=$encodedArtist"
    val taskinShareText = buildString {
        appendLine("🎵 $songArtist - $songTitle")
        appendLine()
        appendLine("⚡ Taşkın Music ile Aç ve Otomatik İndir:")
        appendLine(taskinAppWebLink)
        appendLine()
        appendLine("📱 Doğrudan İndirme Şeması:")
        append("taskinmusic://download?id=$songId&title=$encodedTitle&artist=$encodedArtist")
    }

    // Standard streaming links
    val ytmLink = "https://music.youtube.com/watch?v=$songId"
    val ytLink = "https://youtu.be/$songId"
    val spotifyLink = "https://open.spotify.com/search/$encodedSearch"

    // All links combined
    val allLinksText = buildString {
        appendLine("🎵 $songArtist - $songTitle")
        appendLine()
        appendLine("⬇️ Doğrudan MP3 İndir (Web):")
        appendLine(webMp3Link)
        appendLine()
        appendLine("▶️ YouTube:")
        appendLine(ytLink)
        appendLine()
        appendLine("🎧 YouTube Music:")
        appendLine(ytmLink)
        appendLine()
        appendLine("🟢 Spotify:")
        appendLine(spotifyLink)
        appendLine()
        appendLine("⚡ Taşkın Music Uygulaması:")
        append(taskinAppWebLink)
    }

    val shareTextAction: (String) -> Unit = { textToShare ->
        onDismiss()
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, textToShare)
        }
        context.startActivity(Intent.createChooser(intent, null))
    }

    val copyAction: (String) -> Unit = { textToCopy ->
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Share Link", textToCopy)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, R.string.link_copied, Toast.LENGTH_SHORT).show()
    }

    val shareFileAction: () -> Unit = {
        if (!isPreparingFile) {
            isPreparingFile = true
            Toast.makeText(context, R.string.preparing_audio_file, Toast.LENGTH_SHORT).show()
            coroutineScope.launch {
                val fileUri = SongExporter.getShareableAudioUri(
                    context = context,
                    mediaId = songId,
                    downloadCache = downloadUtil.downloadCache,
                    database = database,
                )
                isPreparingFile = false
                if (fileUri != null) {
                    onDismiss()
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "audio/*"
                        putExtra(Intent.EXTRA_STREAM, fileUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "$songArtist - $songTitle"))
                } else {
                    Toast.makeText(
                        context,
                        "Şarkı henüz cihazda inik değil. Lütfen önce şarkıyı indirin veya Web MP3 linkini paylaşın.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
        title = {
            Column {
                Text(
                    text = stringResource(R.string.share_dialog_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "$songArtist • $songTitle",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 1. Web'den MP3 İndirme Linki (Direct browser download link)
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { shareTextAction(webMp3ShareText) },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.download),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.share_direct_mp3),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Text(
                                text = stringResource(R.string.share_direct_mp3_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                            )
                        }
                        IconButton(onClick = { copyAction(webMp3Link) }) {
                            Icon(
                                painter = painterResource(R.drawable.content_copy),
                                contentDescription = "Kopyala",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }

                // 2. MP3 Dosyası Olarak Gönder (Sends actual audio file to WhatsApp, Telegram, etc.)
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(onClick = shareFileAction),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (isPreparingFile) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.music_note),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.share_mp3_file),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Text(
                                text = stringResource(R.string.share_mp3_file_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                            )
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // 3. Taşkın Music Uygulama Linki
                ShareOptionRow(
                    icon = painterResource(R.drawable.download),
                    title = stringResource(R.string.share_taskin_music),
                    subtitle = stringResource(R.string.share_taskin_music_desc),
                    onShare = { shareTextAction(taskinShareText) },
                    onCopy = { copyAction(taskinAppWebLink) },
                )

                // 4. YouTube Music Linki
                ShareOptionRow(
                    icon = painterResource(R.drawable.music_note),
                    title = stringResource(R.string.share_youtube_music),
                    subtitle = ytmLink,
                    onShare = { shareTextAction(ytmLink) },
                    onCopy = { copyAction(ytmLink) },
                )

                // 5. YouTube Linki
                ShareOptionRow(
                    icon = painterResource(R.drawable.play),
                    title = stringResource(R.string.share_youtube),
                    subtitle = ytLink,
                    onShare = { shareTextAction(ytLink) },
                    onCopy = { copyAction(ytLink) },
                )

                // 6. Spotify Linki
                ShareOptionRow(
                    icon = painterResource(R.drawable.search),
                    title = stringResource(R.string.share_spotify),
                    subtitle = stringResource(R.string.share_spotify_desc),
                    onShare = { shareTextAction(spotifyLink) },
                    onCopy = { copyAction(spotifyLink) },
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // 7. Tüm Linkleri Birlikte Paylaş
                ShareOptionRow(
                    icon = painterResource(R.drawable.share),
                    title = stringResource(R.string.share_all_links),
                    subtitle = stringResource(R.string.share_all_links_desc),
                    onShare = { shareTextAction(allLinksText) },
                    onCopy = { copyAction(allLinksText) },
                )
            }
        },
    )
}

@Composable
private fun ShareOptionRow(
    icon: Painter,
    title: String,
    subtitle: String,
    onShare: () -> Unit,
    onCopy: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onShare),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onCopy) {
                Icon(
                    painter = painterResource(R.drawable.content_copy),
                    contentDescription = "Kopyala",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

