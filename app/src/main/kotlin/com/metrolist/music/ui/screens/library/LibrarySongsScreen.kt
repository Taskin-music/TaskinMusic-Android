/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.library

import android.app.Activity
import android.Manifest
import android.net.Uri
import android.os.Build
import android.widget.Toast
import com.metrolist.music.utils.MediaDeleteHelper
import com.metrolist.music.utils.SongExporter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.metrolist.music.utils.LocalMediaScanner
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.metrolist.innertube.YouTube
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.db.entities.Song
import com.metrolist.music.playback.ExoDownloadService
import androidx.media3.exoplayer.offline.DownloadService
import com.metrolist.music.ui.menu.AddToPlaylistDialog
import com.metrolist.music.constants.CONTENT_TYPE_HEADER
import com.metrolist.music.constants.CONTENT_TYPE_SONG
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.constants.SongFilter
import com.metrolist.music.constants.SongFilterKey
import com.metrolist.music.constants.SongSortDescendingKey
import com.metrolist.music.constants.SongSortType
import com.metrolist.music.constants.SongSortTypeKey
import com.metrolist.music.constants.YtmSyncKey
import com.metrolist.music.extensions.fileNameAndSize
import com.metrolist.music.extensions.matchesNormalizedQuery
import com.metrolist.music.extensions.normalizeForSearch
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.ui.component.ChipsRow
import com.metrolist.music.ui.component.DefaultDialog
import com.metrolist.music.ui.component.HideOnScrollFAB
import com.metrolist.music.ui.component.LibrarySearchEmptyPlaceholder
import com.metrolist.music.ui.component.LibrarySearchHeader
import com.metrolist.music.ui.component.LocalMenuState
import com.metrolist.music.ui.component.SongListItem
import com.metrolist.music.ui.component.SortHeader
import com.metrolist.music.ui.menu.SongMenu
import com.metrolist.music.ui.utils.isScrollingUp
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.viewmodels.LibrarySongsViewModel
import timber.log.Timber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LibrarySongsScreen(
    navController: NavController,
    onDeselect: () -> Unit = {},
    fixedFilter: SongFilter? = null,
    showFilterRow: Boolean = true,
    contentPadding: PaddingValues = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
    viewModel: LibrarySongsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val menuState = LocalMenuState.current
    val uploadUnsupportedFormatStr = stringResource(R.string.upload_unsupported_format)
    val uploadFileTooLargeStr = stringResource(R.string.upload_file_too_large)
    val uploadFailedStr = stringResource(R.string.upload_failed)
    val uploadCompleteStr = stringResource(R.string.upload_complete)
    val queueAllSongsStr = stringResource(R.string.queue_all_songs)
    val playerConnection = LocalPlayerConnection.current ?: return
    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsStateWithLifecycle()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val database = LocalDatabase.current
    val scope = rememberCoroutineScope()

    var inSelectMode by rememberSaveable { mutableStateOf(false) }
    val selection = rememberSaveable(
        saver = listSaver<MutableList<String>, String>(
            save = { it.toList() },
            restore = { it.toMutableStateList() },
        ),
    ) { mutableStateListOf() }
    val onExitSelectionMode = {
        inSelectMode = false
        selection.clear()
    }
    BackHandler(enabled = inSelectMode, onBack = onExitSelectionMode)

    var showAddToPlaylistDialog by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirmDialog by rememberSaveable { mutableStateOf(false) }

    AddToPlaylistDialog(
        isVisible = showAddToPlaylistDialog,
        onGetSong = { selection.toList() },
        onGetSongIds = { selection.toList() },
        onDismiss = {
            showAddToPlaylistDialog = false
            onExitSelectionMode()
        },
    )

    val (sortType, onSortTypeChange) =
        rememberEnumPreference(
            SongSortTypeKey,
            SongSortType.CREATE_DATE,
        )
    val (sortDescending, onSortDescendingChange) = rememberPreference(SongSortDescendingKey, true)

    val (ytmSync) = rememberPreference(YtmSyncKey, true)
    val hideExplicit by rememberPreference(key = HideExplicitKey, defaultValue = false)

    var prefFilter by rememberEnumPreference(SongFilterKey, fixedFilter ?: SongFilter.LIKED)
    val filter = fixedFilter ?: prefFilter
    val songs by remember(filter) {
        when (filter) {
            SongFilter.DOWNLOADED -> viewModel.downloadedSongs
            SongFilter.LIKED -> viewModel.likedSongs
            else -> viewModel.allSongs
        }
    }.collectAsStateWithLifecycle()

    val hasStoragePerm = remember { mutableStateOf(LocalMediaScanner.hasStoragePermission(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasStoragePerm.value = isGranted
        if (isGranted) {
            viewModel.scanLocalMedia()
        }
    }

    LaunchedEffect(Unit) {
        if (LocalMediaScanner.hasStoragePermission(context)) {
            viewModel.scanLocalMedia()
        }
    }

    var isSearchActive by rememberSaveable { mutableStateOf(false) }
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val debouncedSearchQuery by viewModel.debouncedSearchQuery.collectAsStateWithLifecycle()
    val normalizedQuery = remember(debouncedSearchQuery) { debouncedSearchQuery.normalizeForSearch() }

    // Upload state
    var showUploadDialog by remember { mutableStateOf(false) }
    var uploadProgress by remember { mutableFloatStateOf(0f) }
    var currentUploadIndex by remember { mutableIntStateOf(0) }
    var totalUploads by remember { mutableIntStateOf(0) }
    var currentFileName by remember { mutableStateOf("") }
    var isUploading by remember { mutableStateOf(false) }
    var uploadJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    val filePickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris: List<Uri> ->
            if (uris.isNotEmpty()) {
                uris.forEach { uri ->
                    try {
                        val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        context.contentResolver.takePersistableUriPermission(uri, takeFlags)
                    } catch (e: SecurityException) {
                        Timber.w(e, "Could not take persistable permission")
                    }
                }
                uploadJob =
                    scope.launch {
                        isUploading = true
                        showUploadDialog = true
                        totalUploads = uris.size
                        var successCount = 0

                        uris.forEachIndexed { index, uri ->
                            currentUploadIndex = index + 1
                            uploadProgress = 0f

                            try {
                                val (fileName, contentLength) =
                                    withContext(Dispatchers.IO) {
                                        context.contentResolver.fileNameAndSize(uri)
                                    }
                                currentFileName = fileName

                                if (fileName.substringAfterLast('.', "").lowercase() !in YouTube.SUPPORTED_UPLOAD_TYPES) {
                                    Toast.makeText(context, uploadUnsupportedFormatStr, Toast.LENGTH_SHORT).show()
                                    return@forEachIndexed
                                }
                                if (contentLength >= YouTube.MAX_UPLOAD_SIZE) {
                                    Toast.makeText(context, uploadFileTooLargeStr, Toast.LENGTH_SHORT).show()
                                    return@forEachIndexed
                                }
                                if (contentLength <= 0) {
                                    Toast.makeText(context, uploadFailedStr, Toast.LENGTH_SHORT).show()
                                    return@forEachIndexed
                                }

                                val result =
                                    YouTube.uploadSong(
                                        filename = fileName,
                                        contentLength = contentLength,
                                        content = { checkNotNull(context.contentResolver.openInputStream(uri)) },
                                        onProgress = { progress -> uploadProgress = progress },
                                    )

                                if (result.getOrThrow()) successCount++
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Toast
                                    .makeText(
                                        context,
                                        uploadFailedStr + ": ${e.message}",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                            }
                        }

                        isUploading = false

                        if (successCount > 0) {
                            // Show completion briefly
                            uploadProgress = 1f
                            currentFileName = uploadCompleteStr
                            kotlinx.coroutines.delay(1000)

                            // Show toast on main thread
                            withContext(Dispatchers.Main) {
                                Toast
                                    .makeText(
                                        context,
                                        uploadCompleteStr,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                            }

                            showUploadDialog = false

                            // Refresh uploaded songs
                            viewModel.syncUploadedSongs()
                        } else {
                            showUploadDialog = false
                        }
                    }
            }
        }

    LaunchedEffect(Unit) {
        if (ytmSync) {
            when (filter) {
                SongFilter.LIKED -> viewModel.syncLikedSongs()
                SongFilter.LIBRARY -> viewModel.syncLibrarySongs()
                SongFilter.UPLOADED -> viewModel.syncUploadedSongs()
                else -> return@LaunchedEffect
            }
        }
    }

    val lazyListState = rememberLazyListState()

    val backStackEntry by navController.currentBackStackEntryAsState()
    val scrollToTop =
        backStackEntry?.savedStateHandle?.getStateFlow("scrollToTop", false)?.collectAsStateWithLifecycle()

    LaunchedEffect(scrollToTop?.value) {
        if (scrollToTop?.value == true) {
            lazyListState.animateScrollToItem(0)
            backStackEntry?.savedStateHandle?.set("scrollToTop", false)
        }
    }

    val filteredSongs =
        remember(songs, hideExplicit, normalizedQuery) {
            val baseSongs = if (hideExplicit) songs.filter { !it.song.explicit } else songs
            val q = normalizedQuery.trim()
            if (q.isBlank()) {
                baseSongs
            } else {
                baseSongs.filter { song ->
                    val artistNames = song.artists.map { it.name }.toTypedArray()
                    matchesNormalizedQuery(q, song.song.title, song.album?.title, *artistNames)
                }
            }
        }

    var pendingDeleteSongs by remember { mutableStateOf<List<Song>?>(null) }
    val deleteMediaLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            scope.launch(Dispatchers.IO) {
                pendingDeleteSongs?.let {
                    MediaDeleteHelper.performPostDeleteCleanup(context, database, playerConnection, it)
                }
                pendingDeleteSongs = null
                withContext(Dispatchers.Main) {
                    onExitSelectionMode()
                }
            }
        }
    }

    if (showDeleteConfirmDialog) {
        DefaultDialog(
            onDismiss = { showDeleteConfirmDialog = false },
            title = { Text(stringResource(R.string.delete)) },
            buttons = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
                TextButton(
                    onClick = {
                        showDeleteConfirmDialog = false
                        val selectedIds = selection.toList()
                        scope.launch(Dispatchers.IO) {
                            val toDelete = filteredSongs.filter { it.id in selectedIds }
                            val result = MediaDeleteHelper.requestDeleteSongs(context, toDelete)
                            when (result) {
                                is MediaDeleteHelper.DeleteResult.RequiresIntentSender -> {
                                    pendingDeleteSongs = result.songsToDelete
                                    withContext(Dispatchers.Main) {
                                        deleteMediaLauncher.launch(result.intentSenderRequest)
                                    }
                                }
                                is MediaDeleteHelper.DeleteResult.Completed -> {
                                    MediaDeleteHelper.performPostDeleteCleanup(
                                        context,
                                        database,
                                        playerConnection,
                                        result.songsDeleted
                                    )
                                    withContext(Dispatchers.Main) {
                                        onExitSelectionMode()
                                    }
                                }
                                is MediaDeleteHelper.DeleteResult.Error -> {}
                            }
                        }
                    },
                ) {
                    Text(
                        text = stringResource(R.string.delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
        ) {
            Text("${selection.size} şarkı cihazdan / indirmelerden silinecek. Onaylıyor musunuz?")
        }
    }

    // Upload progress dialog
    if (showUploadDialog) {
        DefaultDialog(
            onDismiss = {
                if (isUploading) {
                    uploadJob?.cancel()
                    isUploading = false
                }
                showUploadDialog = false
            },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.upload),
                    contentDescription = null,
                )
            },
            title = { Text(stringResource(R.string.uploading)) },
            buttons = {
                TextButton(
                    onClick = {
                        if (isUploading) {
                            uploadJob?.cancel()
                            isUploading = false
                        }
                        showUploadDialog = false
                    },
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        ) {
            Text(
                text = stringResource(R.string.upload_progress, currentUploadIndex, totalUploads),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = currentFileName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            LinearProgressIndicator(
                progress = { uploadProgress },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = lazyListState,
            contentPadding = contentPadding,
        ) {
            if (fixedFilter == SongFilter.DOWNLOADED && !hasStoragePerm.value) {
                item(key = "storage_permission_card") {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.storage),
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.scan_local_music),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = stringResource(R.string.storage_permission_required),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        Manifest.permission.READ_MEDIA_AUDIO
                                    } else {
                                        Manifest.permission.READ_EXTERNAL_STORAGE
                                    }
                                    permissionLauncher.launch(perm)
                                },
                            ) {
                                Text(stringResource(R.string.grant_permission))
                            }
                        }
                    }
                }
            }

            if (showFilterRow && !inSelectMode) {
                item(
                    key = "filter",
                    contentType = CONTENT_TYPE_HEADER,
                ) {
                    Row {
                        Spacer(Modifier.width(12.dp))
                        FilterChip(
                            label = { Text(stringResource(R.string.songs)) },
                            selected = true,
                            colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface),
                            onClick = onDeselect,
                            shape = RoundedCornerShape(16.dp),
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.close),
                                    contentDescription = "",
                                )
                            },
                        )
                        ChipsRow(
                            chips =
                                listOf(
                                    SongFilter.LIKED to stringResource(R.string.filter_liked),
                                    SongFilter.LIBRARY to stringResource(R.string.filter_library),
                                    SongFilter.UPLOADED to stringResource(R.string.filter_uploaded),
                                    SongFilter.DOWNLOADED to stringResource(R.string.filter_downloaded),
                                ),
                            currentValue = filter,
                            onValueUpdate = {
                                prefFilter = it
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            item(
                key = "header",
                contentType = CONTENT_TYPE_HEADER,
            ) {
                if (inSelectMode) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        IconButton(onClick = onExitSelectionMode) {
                            Icon(
                                painter = painterResource(R.drawable.close),
                                contentDescription = stringResource(R.string.close),
                            )
                        }
                        Text(
                            text = "${selection.size} seçildi",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                        Spacer(Modifier.weight(1f))
                        Checkbox(
                            checked = selection.size == filteredSongs.size && filteredSongs.isNotEmpty(),
                            onCheckedChange = { checked ->
                                if (checked) {
                                    selection.clear()
                                    selection.addAll(filteredSongs.map { it.id })
                                } else {
                                    selection.clear()
                                }
                            },
                        )
                        IconButton(
                            enabled = selection.isNotEmpty(),
                            onClick = { showAddToPlaylistDialog = true },
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.playlist_add),
                                contentDescription = stringResource(R.string.add_to_playlist),
                            )
                        }
                        IconButton(
                            enabled = selection.isNotEmpty(),
                            onClick = { showDeleteConfirmDialog = true },
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.delete),
                                contentDescription = stringResource(R.string.delete),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                } else {
                    LibrarySearchHeader(
                        isSearchActive = isSearchActive,
                        searchQuery = searchQuery,
                        onSearchQueryChange = viewModel::updateSearchQuery,
                        onBack = {
                            isSearchActive = false
                            viewModel.updateSearchQuery("")
                        },
                        keyboardController = keyboardController,
                        modifier = Modifier.padding(start = 16.dp),
                    ) {
                        SortHeader(
                            sortType = sortType,
                            sortDescending = sortDescending,
                            onSortTypeChange = onSortTypeChange,
                            onSortDescendingChange = onSortDescendingChange,
                            sortTypeText = { sortType ->
                                when (sortType) {
                                    SongSortType.CREATE_DATE -> R.string.sort_by_create_date
                                    SongSortType.NAME -> R.string.sort_by_name
                                    SongSortType.ARTIST -> R.string.sort_by_artist
                                    SongSortType.PLAY_TIME -> R.string.sort_by_play_time
                                }
                            },
                        )

                        Spacer(Modifier.weight(1f))

                        Text(
                            text =
                                pluralStringResource(
                                    R.plurals.n_song,
                                    filteredSongs.size,
                                    filteredSongs.size,
                                ),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )

                        IconButton(
                            onClick = { isSearchActive = true },
                            modifier = Modifier.padding(start = 8.dp, end = 8.dp).size(40.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.search),
                                contentDescription = stringResource(R.string.search),
                            )
                        }
                    }
                }
            }

            if (filteredSongs.isEmpty() && searchQuery.isNotBlank()) {
                item(
                    key = "empty_search_result",
                    contentType = CONTENT_TYPE_HEADER,
                ) {
                    LibrarySearchEmptyPlaceholder(modifier = Modifier.animateItem())
                }
            }

            itemsIndexed(
                items = filteredSongs,
                key = { _, item -> item.id },
                contentType = { _, _ -> CONTENT_TYPE_SONG },
            ) { index, song ->
                SongListItem(
                    song = song,
                    showInLibraryIcon = true,
                    isActive = song.id == mediaMetadata?.id,
                    isPlaying = isPlaying,
                    showLikedIcon = true,
                    showDownloadIcon = filter != SongFilter.DOWNLOADED,
                    trailingContent = {
                        if (inSelectMode) {
                            Checkbox(
                                checked = selection.contains(song.id),
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        selection.add(song.id)
                                    } else {
                                        selection.remove(song.id)
                                        if (selection.isEmpty()) inSelectMode = false
                                    }
                                },
                            )
                        } else {
                            IconButton(
                                onClick = {
                                    menuState.show {
                                        SongMenu(
                                            originalSong = song,
                                            onDismiss = menuState::dismiss,
                                        )
                                    }
                                },
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.more_vert),
                                    contentDescription = null,
                                )
                            }
                        }
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    if (inSelectMode) {
                                        if (selection.contains(song.id)) {
                                            selection.remove(song.id)
                                            if (selection.isEmpty()) inSelectMode = false
                                        } else {
                                            selection.add(song.id)
                                        }
                                    } else {
                                        if (song.id == mediaMetadata?.id) {
                                            playerConnection.togglePlayPause()
                                        } else {
                                            playerConnection.playQueue(
                                                ListQueue(
                                                    title = queueAllSongsStr,
                                                    items = filteredSongs.map { it.toMediaItem() },
                                                    startIndex = index,
                                                ),
                                            )
                                        }
                                    }
                                },
                                onLongClick = {
                                    if (!inSelectMode) {
                                        inSelectMode = true
                                        selection.add(song.id)
                                    }
                                },
                            ).animateItem(),
                )
            }
        }

        // Show upload FAB when on UPLOADED filter, shuffle FAB otherwise
        HideOnScrollFAB(
            visible = if (filter == SongFilter.UPLOADED) true else filteredSongs.isNotEmpty(),
            lazyListState = lazyListState,
            icon = if (filter == SongFilter.UPLOADED) R.drawable.upload else R.drawable.shuffle,
            onClick = {
                if (filter == SongFilter.UPLOADED) {
                    filePickerLauncher.launch(
                        arrayOf(
                            "audio/mpeg",
                            "audio/mp4",
                            "audio/x-m4a",
                            "audio/flac",
                            "audio/ogg",
                            "audio/x-ms-wma",
                        ),
                    )
                } else {
                    playerConnection.playQueue(
                        ListQueue(
                            title = queueAllSongsStr,
                            items = filteredSongs.shuffled().map { it.toMediaItem() },
                        ),
                    )
                }
            },
        )
    }
}
