package com.eight87.whisperboy.ui.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Clear
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eight87.whisperboy.R
import com.eight87.whisperboy.data.library.BookEntity
import com.eight87.whisperboy.data.library.BookFilter
import com.eight87.whisperboy.data.library.BookSortKey
import com.eight87.whisperboy.data.library.BookSource
import com.eight87.whisperboy.data.library.GridMode
import com.eight87.whisperboy.data.library.LibraryHealth
import com.eight87.whisperboy.data.library.LibraryRescanCoordinator
import com.eight87.whisperboy.data.library.LibraryRoot
import com.eight87.whisperboy.data.library.LibraryUiSettings
import com.eight87.whisperboy.data.library.PersistedUriPermissionStore
import com.eight87.whisperboy.data.library.RescanState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import com.eight87.whisperboy.theme.LibraryAccent
import com.eight87.whisperboy.ui.common.CoverArt
import com.eight87.whisperboy.ui.common.refreshCoverArt
import com.eight87.whisperboy.ui.common.FastScrollbar
import kotlinx.coroutines.launch

/**
 * Phase E.1 — the library screen.
 *
 * Takes only narrow data interfaces (R.A discipline): [BookSource] for the catalog flow,
 * [PersistedUriPermissionStore] for the "Add folder" FAB (the SAF picker still attaches a
 * `FolderType` here; rescan + manage live in Settings → Library folders).
 *
 * Top app bar uses the M3 default expanded height (64dp). An earlier draft of m3-expressive
 * gotcha #6 claimed tonearmboy converged on 32dp — that claim was incorrect; tonearmboy uses
 * the M3 default everywhere. Cover grid wrapped in [FastScrollbar] — section-letter chips along
 * the right edge appear when scrolling/dragging, 800ms linger fade-out (tonearmboy `4e2ff73`).
 *
 * Folder management lives in Settings → Library folders (Phase K.4 partial). The library
 * top app bar exposes search / sort / grid-toggle / settings (gear) icons; "Add folder" is a
 * floating action button at the bottom-right when at least one root exists.
 *
 * Filter chips (E.2), sort menu + sort-aware section keys (E.3), search (E.4), long-press
 * action sheet + multi-select (E.5), and now-playing bar (E.6) land in follow-up commits.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    bookSource: BookSource,
    persistedUriPermissionStore: PersistedUriPermissionStore,
    libraryUiSettings: LibraryUiSettings,
    libraryRescanCoordinator: LibraryRescanCoordinator,
    onBookTap: (String) -> Unit,
    onSettingsClick: () -> Unit,
    onLibraryFoldersClick: () -> Unit,
    onAuthorClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    // Voice-style boot: keep books null until the first Room emission lands so the
    // empty-state composable doesn't flash for the half-frame before the Flow fires.
    // `null` = haven't loaded yet, `emptyList()` = library genuinely empty. Caller renders
    // chrome (toolbar) unconditionally; content area gates on non-null.
    val booksOrNull by bookSource.observeBooks()
        .collectAsStateWithLifecycle(initialValue = null)
    val books: List<BookEntity> = booksOrNull ?: emptyList()
    val roots by persistedUriPermissionStore.observeRoots()
        .collectAsStateWithLifecycle(initialValue = emptyList<LibraryRoot>())
    // Phase P.4 — observe library health; render an error banner above the rail+content row
    // whenever the scanner couldn't read one or more of the persisted roots (revoked SAF
    // permission, missing SD card, etc.). Banner taps deep-link to LibraryFoldersScreen so
    // the user can remove + re-pick.
    val health by libraryRescanCoordinator.health
        .collectAsStateWithLifecycle(initialValue = LibraryHealth())
    // Issue-3 — in-library scan progress banner + "Found N new books" snackbar.
    // Observe the rescan state; while Running, render the banner. On Running→Idle
    // with a positive book delta, fire a transient snackbar. We track the
    // last-seen book count alongside the previous running-state book count so
    // the delta survives a state-flip race.
    val rescanState by libraryRescanCoordinator.state
        .collectAsStateWithLifecycle(initialValue = RescanState.Idle)
    val coroutineScope = rememberCoroutineScope()

    // Phase E.3 follow-up — persisted via DataStore (`library_ui`). `collectAsStateWithLifecycle`
    // (cold-start-perf.md B.1) defers collection to Lifecycle.STARTED so the first emission
    // doesn't fire during composition. Initial values match the defaults the impl coerces to,
    // so the screen renders consistently before the Flow's first emission lands.
    val gridMode by libraryUiSettings.gridMode
        .collectAsStateWithLifecycle(initialValue = GridMode.Grid)
    val sortKey by libraryUiSettings.sortKey
        .collectAsStateWithLifecycle(initialValue = BookSortKey.Title)
    val filter by libraryUiSettings.filter
        .collectAsStateWithLifecycle(initialValue = BookFilter.All)
    var sortMenuOpen by remember { mutableStateOf(false) }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var searchMode by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var actionSheetBookId by remember { mutableStateOf<String?>(null) }
    var forgetConfirmBookId by remember { mutableStateOf<String?>(null) }

    // Issue-3 — "Found N new books" snackbar is now driven by the data layer's one-shot
    // `scanSummaries` SharedFlow, NOT a Compose-side baseline. The prior preScanBookCount
    // captured `books.size = 0` if the Flow hadn't emitted its first value yet (which is
    // the case for scans that fire at app launch), causing the snackbar to report the
    // full library as "new" every time. The coordinator now snapshots existing book IDs
    // synchronously before touching Room and emits `(seenBookIds - existingIds).size`
    // on completion — race-free.
    val running = rescanState as? RescanState.Running
    val context = LocalContext.current
    LaunchedEffect(libraryRescanCoordinator) {
        libraryRescanCoordinator.scanSummaries.collect { summary ->
            if (summary.newBooks > 0) {
                snackbarHostState.showSnackbar(
                    message = context.getString(
                        R.string.library_scan_progress_complete_format,
                        summary.newBooks,
                    ),
                )
            }
        }
    }

    val filteredBooks = remember(books, filter) { filterBooks(books, filter) }
    val searchedBooks = remember(filteredBooks, searchQuery) {
        searchBooks(filteredBooks, searchQuery)
    }
    val sortedBooks = remember(searchedBooks, sortKey) { sortedBooks(searchedBooks, sortKey) }
    val sectionStarts = remember(sortedBooks, sortKey) { sectionStartsFor(sortedBooks, sortKey) }

    val pickFolder = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> if (uri != null) pendingUri = uri }

    // Phase A.6 (`cover-art.md`) — "Use custom cover from device" SAF picker. The user picks
    // an image, we read its bytes via `contentResolver.openInputStream`, hand them to
    // `BookSource.setCustomCover`, and tag the row as `Custom` so a later rescan leaves the
    // user's pick alone. The launcher is created at the top of the composable (matching the
    // `pickFolder` idiom above); `pendingCustomCoverBookId` stashes the target book id from
    // the moment the user taps the action-sheet button until the picker returns.
    var pendingCustomCoverBookId by remember { mutableStateOf<String?>(null) }
    val pickCustomCover = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val bookId = pendingCustomCoverBookId
        pendingCustomCoverBookId = null
        if (uri != null && bookId != null) {
            coroutineScope.launch {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null && bytes.isNotEmpty()) {
                    bookSource.setCustomCover(bookId, bytes)
                }
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (searchMode) {
            LibrarySearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onClose = {
                    searchMode = false
                    searchQuery = ""
                },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
        TopAppBar(
            title = { Text(stringResource(R.string.library_title)) },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            actions = {
                IconButton(onClick = { searchMode = true }) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = stringResource(R.string.library_search_cd),
                    )
                }
                Box {
                    IconButton(onClick = { sortMenuOpen = true }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Sort,
                            contentDescription = stringResource(R.string.library_sort_cd),
                        )
                    }
                    DropdownMenu(
                        expanded = sortMenuOpen,
                        onDismissRequest = { sortMenuOpen = false },
                    ) {
                        BookSortKey.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(stringResource(sortKeyLabel(option))) },
                                leadingIcon = if (option == sortKey) {
                                    {
                                        Icon(
                                            imageVector = Icons.Filled.Check,
                                            contentDescription = null,
                                        )
                                    }
                                } else null,
                                onClick = {
                                    coroutineScope.launch { libraryUiSettings.setSortKey(option) }
                                    sortMenuOpen = false
                                },
                            )
                        }
                    }
                }
                IconButton(onClick = {
                    val next = if (gridMode == GridMode.Grid) GridMode.List else GridMode.Grid
                    coroutineScope.launch { libraryUiSettings.setGridMode(next) }
                }) {
                    val (icon, cd) = when (gridMode) {
                        GridMode.Grid -> Icons.AutoMirrored.Filled.ViewList to
                            R.string.library_grid_mode_toggle_to_list_cd
                        GridMode.List -> Icons.Filled.GridView to
                            R.string.library_grid_mode_toggle_to_grid_cd
                    }
                    Icon(imageVector = icon, contentDescription = stringResource(cd))
                }
                IconButton(onClick = onSettingsClick) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = stringResource(R.string.library_settings_cd),
                    )
                }
            },
        )
        }

        // Issue-3 — scan progress banner. Sits directly beneath the TopAppBar so the user
        // gets immediate feedback that a background scan is in flight on first launch after
        // configuring a folder. Auto-dismisses on Idle.
        if (running != null) {
            LibraryScanProgressBanner(
                running = running,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Phase P.4 — unreadable-roots banner. Renders above the rail+content row so the user
        // sees the warning before scrolling into the (possibly stale) book grid. Click-through
        // navigates to the folder management screen where the user can re-pick.
        if (health.unreadableRoots.isNotEmpty()) {
            LibraryHealthBanner(
                unreadableCount = health.unreadableRoots.size,
                onClick = onLibraryFoldersClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        val showRail = books.isNotEmpty() && !searchMode
        if (showRail) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                LibraryRail(
                    filter = filter,
                    onFilterChange = { next ->
                        coroutineScope.launch { libraryUiSettings.setFilter(next) }
                    },
                )
                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    if (booksOrNull != null) {
                        LibraryContent(
                            sortedBooks = sortedBooks,
                            sectionStarts = sectionStarts,
                            gridMode = gridMode,
                            searchMode = searchMode,
                            searchQuery = searchQuery,
                            onBookTap = onBookTap,
                            onBookLongPress = { actionSheetBookId = it },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        } else {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (booksOrNull != null) {
                    LibraryContent(
                        sortedBooks = sortedBooks,
                        sectionStarts = sectionStarts,
                        gridMode = gridMode,
                        searchMode = searchMode,
                        searchQuery = searchQuery,
                        onBookTap = onBookTap,
                        onBookLongPress = { actionSheetBookId = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        // Phase E.6 — pinned now-playing bar (mini-player) is mounted by
        // `NowPlayingSheet` at the WhisperboyApp root, NOT here. Hosting
        // it at the root lets the bar double as the peek of the swipe-up
        // sheet (Auxio-style) and keeps the fast-ticking sheet-progress
        // animation off the library's recomposition path.
    }

    // FAB — "Add folder". Visible whenever the library has at least one root and we're
    // not in search mode. Same SAF launcher pattern the old overflow dropdown used.
    // Sits above the grid area; the NowPlayingBar (peek of the playback sheet) draws
    // on top of this Box from `WhisperboyApp` so the FAB never overlaps the mini-player.
    if (roots.isNotEmpty() && !searchMode) {
        FloatingActionButton(
            onClick = { pickFolder.launch(null) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = stringResource(R.string.library_fab_add_folder_cd),
            )
        }
    }
    // Issue-3 — host the "Found N new books" snackbar at the bottom of the library
    // surface. The WhisperboyApp root also owns one for cross-screen messages; this
    // local host stays scoped to library-only notifications.
    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
    )
    }

    val pending = pendingUri
    if (pending != null) {
        ManageFoldersFolderTypeSheet(
            onDismiss = { pendingUri = null },
            onSelect = { folderType ->
                coroutineScope.launch {
                    persistedUriPermissionStore.addRoot(pending, folderType)
                    pendingUri = null
                }
            },
        )
    }

    val actionBookId = actionSheetBookId
    if (actionBookId != null) {
        val actionBook = remember(books, actionBookId) { books.find { it.bookId == actionBookId } }
        if (actionBook != null) {
            BookActionSheet(
                book = actionBook,
                onDismiss = { actionSheetBookId = null },
                onMarkCompleted = {
                    coroutineScope.launch { bookSource.markCompleted(actionBook.bookId) }
                    actionSheetBookId = null
                },
                onMarkNotStarted = {
                    coroutineScope.launch { bookSource.markNotStarted(actionBook.bookId) }
                    actionSheetBookId = null
                },
                onPickCustomCover = {
                    pendingCustomCoverBookId = actionBook.bookId
                    pickCustomCover.launch(arrayOf("image/*"))
                    actionSheetBookId = null
                },
                onForget = {
                    forgetConfirmBookId = actionBook.bookId
                    actionSheetBookId = null
                },
                onViewByAuthor = {
                    val author = actionBook.author
                    if (!author.isNullOrBlank()) {
                        onAuthorClick(author)
                    }
                    actionSheetBookId = null
                },
            )
        } else {
            // Book vanished from the catalog while the sheet was open (e.g. rescan dropped it).
            // Just dismiss silently.
            actionSheetBookId = null
        }
    }

    val forgetBookId = forgetConfirmBookId
    if (forgetBookId != null) {
        BookForgetConfirmDialog(
            onDismiss = { forgetConfirmBookId = null },
            onConfirm = {
                coroutineScope.launch { bookSource.forgetBook(forgetBookId) }
                forgetConfirmBookId = null
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookActionSheet(
    book: BookEntity,
    onDismiss: () -> Unit,
    onMarkCompleted: () -> Unit,
    onMarkNotStarted: () -> Unit,
    onPickCustomCover: () -> Unit,
    onForget: () -> Unit,
    onViewByAuthor: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isCompleted = book.completedAt != null
    val refreshContext = LocalContext.current
    // m3-expressive D.3 — pin sheet container to the M3E `surfaceContainer` tier
    // (one notch above the page `surface`) + drag handle tinted with the library
    // accent so the long-press sheet visually anchors to the library category.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        dragHandle = { BottomSheetDefaults.DragHandle(color = LibraryAccent.onContainer) },
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.library_book_action_sheet_title, book.title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            // Phase A.6 — custom cover picker. Sits above the mark-completed / mark-not-started
            // pair so the cover-art affordance has visual priority and the destructive "Forget"
            // action stays last.
            TextButton(onClick = onPickCustomCover, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.library_book_action_custom_cover),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // cover-art.md Phase D.1 — drop Coil's memory + disk cache key for this
            // book's cover so the next composition re-reads fresh bytes from disk.
            // Useful after the user drops a `cover.jpg` next to the audio file and
            // a rescan has now copied it into `<filesDir>/covers/<bookId>` but the
            // grid is still showing the previous bitmap.
            TextButton(
                onClick = {
                    refreshCoverArt(refreshContext, book.coverPath)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.library_book_action_refresh_cover),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (isCompleted) {
                TextButton(onClick = onMarkNotStarted, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.library_book_action_mark_not_started),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                TextButton(onClick = onMarkCompleted, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.library_book_action_mark_completed),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            // R.F.9 — per-author detail entry point. Hidden when the row has no author
            // (rare — Voice's "Unknown author" fallback is a UI display string, not a stored
            // value, so a missing/blank `book.author` here means the scanner had nothing).
            val author = book.author
            if (!author.isNullOrBlank()) {
                TextButton(onClick = onViewByAuthor, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.library_book_action_view_by_author, author),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            TextButton(onClick = onForget, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.library_book_action_forget),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun BookForgetConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_book_action_forget_confirm_title)) },
        text = {
            Text(
                stringResource(R.string.library_book_action_forget_confirm_body),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.library_book_action_forget_confirm_yes),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibrarySearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.library_search_close_cd),
            )
        }
        TextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text(stringResource(R.string.library_search_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            Icons.Filled.Clear,
                            contentDescription = stringResource(R.string.settings_search_cd_clear),
                        )
                    }
                }
            },
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester),
        )
    }
}

@Composable
private fun LibrarySearchNoResults(
    query: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.library_search_no_results, query),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LibraryContent(
    sortedBooks: List<BookEntity>,
    sectionStarts: List<Pair<Int, String>>,
    gridMode: GridMode,
    searchMode: Boolean,
    searchQuery: String,
    onBookTap: (String) -> Unit,
    onBookLongPress: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (sortedBooks.isEmpty()) {
        if (searchMode && searchQuery.isNotBlank()) {
            LibrarySearchNoResults(query = searchQuery, modifier = modifier)
        } else {
            LibraryEmptyState(modifier = modifier)
        }
    } else {
        when (gridMode) {
            GridMode.Grid -> LibraryCoverGrid(
                books = sortedBooks,
                sectionStarts = sectionStarts,
                onBookTap = onBookTap,
                onBookLongPress = onBookLongPress,
                modifier = modifier,
            )
            GridMode.List -> LibraryCoverList(
                books = sortedBooks,
                sectionStarts = sectionStarts,
                onBookTap = onBookTap,
                onBookLongPress = onBookLongPress,
                modifier = modifier,
            )
        }
    }
}

@Composable
internal fun LibraryEmptyState(modifier: Modifier = Modifier) {
    // m3-expressive D.1 — elevate the empty-state copy onto a `surfaceContainerHigh`
    // card so the welcome message reads as a callout against the bare page surface
    // rather than floating text. M3E surface ladder: page = `surface`, card-tier
    // banner = `surfaceContainerHigh`.
    Box(
        modifier = modifier.padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(R.string.library_empty_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.library_empty_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LibraryCoverGrid(
    books: List<BookEntity>,
    sectionStarts: List<Pair<Int, String>>,
    onBookTap: (String) -> Unit,
    onBookLongPress: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    // Section header index → label, for O(1) lookup while walking books.
    val headerByIndex = remember(sectionStarts) { sectionStarts.toMap() }

    Box(modifier = modifier) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp),
            state = gridState,
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 96.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            books.forEachIndexed { index, book ->
                val header = headerByIndex[index]
                if (header != null) {
                    item(
                        key = "section:$header",
                        span = { GridItemSpan(maxLineSpan) },
                        contentType = "section",
                    ) {
                        SectionHeader(label = header)
                    }
                }
                item(
                    key = "book:${book.bookId}",
                    contentType = "book",
                ) {
                    BookGridTile(
                        book = book,
                        onTap = { onBookTap(book.bookId) },
                        onLongPress = { onBookLongPress(book.bookId) },
                    )
                }
            }
        }

        FastScrollbar(
            state = gridState,
            sectionStarts = sectionStarts,
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}

@Composable
private fun SectionHeader(label: String) {
    // m3-expressive D.1 — section header rendered as a card-tier chip
    // (`surfaceContainerHigh`) so it sits one notch above the page surface,
    // matching the M3E ladder we apply to list rows + bottom sheets.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun LibraryCoverList(
    books: List<BookEntity>,
    sectionStarts: List<Pair<Int, String>>,
    onBookTap: (String) -> Unit,
    onBookLongPress: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val headerByIndex = remember(sectionStarts) { sectionStarts.toMap() }

    Box(modifier = modifier) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            books.forEachIndexed { index, book ->
                val header = headerByIndex[index]
                if (header != null) {
                    item(key = "section:$header", contentType = "section") {
                        SectionHeader(label = header)
                    }
                }
                item(key = "book:${book.bookId}", contentType = "book") {
                    BookListRow(
                        book = book,
                        onTap = { onBookTap(book.bookId) },
                        onLongPress = { onBookLongPress(book.bookId) },
                    )
                }
            }
        }

        FastScrollbar(
            state = listState,
            sectionStarts = sectionStarts,
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BookGridTile(
    book: BookEntity,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
    ) {
        CoverArt(
            coverPath = book.coverPath,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = book.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = book.author ?: stringResource(R.string.library_book_unknown_author),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookListRow(
    book: BookEntity,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(
            coverPath = book.coverPath,
            modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = book.author ?: stringResource(R.string.library_book_unknown_author),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun sortKeyLabel(key: BookSortKey): Int = when (key) {
    BookSortKey.Recent -> R.string.library_sort_recent
    BookSortKey.Title -> R.string.library_sort_title
    BookSortKey.Author -> R.string.library_sort_author
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManageFoldersFolderTypeSheet(
    onDismiss: () -> Unit,
    onSelect: (com.eight87.whisperboy.data.library.FolderType) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // m3-expressive D.3 — same M3E sheet tier + accented drag handle as the
    // long-press action sheet; folder-type picker is also a library surface.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        dragHandle = { BottomSheetDefaults.DragHandle(color = LibraryAccent.onContainer) },
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = stringResource(R.string.folder_type_dialog_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(12.dp))
            com.eight87.whisperboy.data.library.FolderType.allOrdered.forEach { type ->
                TextButton(
                    onClick = { onSelect(type) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = stringResource(folderTypeTitle(type)),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stringResource(folderTypeSubtitle(type)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.dialog_cancel))
            }
        }
    }
}

internal fun folderTypeTitle(type: com.eight87.whisperboy.data.library.FolderType): Int = when (type) {
    com.eight87.whisperboy.data.library.FolderType.SingleFile -> R.string.folder_type_singlefile_title
    com.eight87.whisperboy.data.library.FolderType.SingleFolder -> R.string.folder_type_singlefolder_title
    com.eight87.whisperboy.data.library.FolderType.Root -> R.string.folder_type_root_title
    com.eight87.whisperboy.data.library.FolderType.Author -> R.string.folder_type_author_title
}

private fun folderTypeSubtitle(type: com.eight87.whisperboy.data.library.FolderType): Int = when (type) {
    com.eight87.whisperboy.data.library.FolderType.SingleFile -> R.string.folder_type_singlefile_subtitle
    com.eight87.whisperboy.data.library.FolderType.SingleFolder -> R.string.folder_type_singlefolder_subtitle
    com.eight87.whisperboy.data.library.FolderType.Root -> R.string.folder_type_root_subtitle
    com.eight87.whisperboy.data.library.FolderType.Author -> R.string.folder_type_author_subtitle
}

/**
 * Phase P.4 — error-tinted banner card rendered above the library rail+content row whenever
 * the rescan coordinator reports unreadable roots. Tap navigates to LibraryFoldersScreen
 * where the user can remove + re-pick the SAF tree. Copy is pluralized via
 * `library_health_unreadable_banner` so "1 folder unreadable" reads naturally.
 */
@Composable
internal fun LibraryHealthBanner(
    unreadableCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.WarningAmber,
                contentDescription = stringResource(R.string.library_health_unreadable_cd),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = androidx.compose.ui.res.pluralStringResource(
                    R.plurals.library_health_unreadable_banner,
                    unreadableCount,
                    unreadableCount,
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.NavigateNext,
                contentDescription = null,
            )
        }
    }
}

