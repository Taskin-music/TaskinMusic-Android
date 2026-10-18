/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.library

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.LocalNavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.LibraryViewType
import com.metrolist.music.constants.PlaylistViewTypeKey
import com.metrolist.music.constants.SongFilter
import com.metrolist.music.utils.LocalMediaScanner
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.viewmodels.LibrarySongsViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    songsViewModel: LibrarySongsViewModel = hiltViewModel(),
) {
    val navController = LocalNavController.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var playlistViewType by rememberEnumPreference(PlaylistViewTypeKey, LibraryViewType.GRID)
    val isScanning by songsViewModel.isScanning.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        if (isGranted) {
            songsViewModel.scanLocalMedia { count ->
                Toast.makeText(
                    context,
                    context.getString(R.string.local_music_scanned, count),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    val triggerScan: () -> Unit = {
        if (!LocalMediaScanner.hasStoragePermission(context)) {
            val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
            permissionLauncher.launch(perm)
        } else {
            songsViewModel.scanLocalMedia { count ->
                Toast.makeText(
                    context,
                    context.getString(R.string.local_music_scanned, count),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (LocalMediaScanner.hasStoragePermission(context)) {
            songsViewModel.scanLocalMedia()
        }
    }

    // Tabs: Tracks (0), Playlists (1), Liked (2)
    val tabs = listOf(
        R.string.tracks,
        R.string.playlists,
        R.string.filter_liked,
    )

    val pagerState = rememberPagerState(initialPage = 0) { tabs.size }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)),
    ) {
        // Samsung Music Tab Row with Scan button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScrollableTabRow(
                selectedTabIndex = pagerState.currentPage,
                edgePadding = 16.dp,
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary,
                indicator = { tabPositions ->
                    if (pagerState.currentPage < tabPositions.size) {
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[pagerState.currentPage]),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                divider = {},
                modifier = Modifier.weight(1f),
            ) {
                tabs.forEachIndexed { index, titleRes ->
                    val selected = pagerState.currentPage == index
                    Tab(
                        selected = selected,
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(index)
                            }
                        },
                        text = {
                            Text(
                                text = stringResource(titleRes),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }

            ScanSyncButton(
                isScanning = isScanning,
                onClick = triggerScan,
            )
        }

        val pageBottomPadding = LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom).asPaddingValues()

        // Horizontal Pager with Swipe support
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            when (page) {
                0 -> LibrarySongsScreen(
                    navController = navController,
                    onDeselect = {},
                    fixedFilter = SongFilter.DOWNLOADED,
                    showFilterRow = false,
                    contentPadding = pageBottomPadding,
                    viewModel = songsViewModel,
                )
                1 -> LibraryPlaylistsScreen(
                    navController = navController,
                    filterContent = {},
                    viewType = playlistViewType,
                    onViewTypeChange = { playlistViewType = it },
                    contentPadding = pageBottomPadding,
                )
                2 -> LibrarySongsScreen(
                    navController = navController,
                    onDeselect = {},
                    fixedFilter = SongFilter.LIKED,
                    showFilterRow = false,
                    contentPadding = pageBottomPadding,
                )
            }
        }
    }
}

@Composable
private fun ScanSyncButton(
    isScanning: Boolean,
    onClick: () -> Unit,
) {
    val rotation = if (isScanning) {
        val transition = rememberInfiniteTransition(label = "scan_spin")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "rotation",
        ).value
    } else {
        0f
    }

    IconButton(
        onClick = onClick,
        enabled = !isScanning,
    ) {
        Icon(
            painter = painterResource(R.drawable.sync),
            contentDescription = stringResource(R.string.scan_local_music),
            tint = if (isScanning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(24.dp)
                .graphicsLayer { rotationZ = rotation },
        )
    }
}

