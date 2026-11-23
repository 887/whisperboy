package com.eight87.whisperboy.ui.bookmark

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eight87.whisperboy.R
import com.eight87.whisperboy.data.library.BookmarkEntity
import com.eight87.whisperboy.data.library.BookmarkSource
import com.eight87.whisperboy.data.library.ChapterEntity
import com.eight87.whisperboy.data.library.ChapterSource
import com.eight87.whisperboy.theme.PlaybackAccent
import kotlinx.coroutines.launch

/**
 * Phase H.1 — bookmark list for a single book, grouped by chapter.
 *
 * Read/edit-only: bookmarks are *created* from the player (top-app-bar bookmark button + dialog,
 * H.2), or auto-created on sleep-timer fire (Phase G.5, [BookmarkEntity.setBySleepTimer] = true).
 * This screen only lists, seeks-to, renames, and deletes them.
 *
 * Interaction map:
 *  - Tap → fires [onBookmarkSeek] with the bookmark's absolute `positionInBookMs`; the host
 *    (`WhisperboyApp`'s `entry<BookmarkRoute>` block) calls `transport.seekTo(...)` + `play()` +
 *    pops the back stack so the user lands back in the player resuming at the bookmark.
 *  - Long-press → opens a [ModalBottomSheet] with Rename / Delete affordances. Rename launches
 *    an inline [AlertDialog] with a single text field; Delete launches a confirm dialog.
 *
 * Bookmarks with `setBySleepTimer = true` render a [Icons.Filled.Bedtime] trailing badge so the
 * user can distinguish auto-bookmarks from manual ones. Title rendering for auto-bookmarks falls
 * back to `bookmark_sleep_timer_default_title` when the persisted title is null (G.5 writes a
 * null title at fire-time so locale rendering lives here, in `ui/`, not in the data write).
 *
 * Narrow-interface (R.A) discipline: takes only [BookmarkSource] + [ChapterSource], not the full
 * `LibraryRepository`. Cold-start-perf B.1: flows collected via `collectAsStateWithLifecycle`;
 * `items(...)` keyed by stable `bookmarkId` (E.1).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BookmarkScreen(
    bookId: String,
    bookmarkSource: BookmarkSource,
    chapterSource: ChapterSource,
    onBack: () -> Unit,
    onBookmarkSeek: (positionInBookMs: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    val bookmarksFlow = remember(bookmarkSource, bookId) {
        bookmarkSource.observeBookmarksForBook(bookId)
    }
    val chaptersFlow = remember(chapterSource, bookId) {
        chapterSource.observeChaptersForBook(bookId)
    }
    val bookmarks by bookmarksFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val chapters by chaptersFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    // Dialog / sheet state.
    var actionTarget by remember { mutableStateOf<BookmarkEntity?>(null) }
    var renameTarget by remember { mutableStateOf<BookmarkEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<BookmarkEntity?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.bookmark_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.bookmark_screen_back_cd),
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (bookmarks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.bookmark_empty_state),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val chapterById = remember(chapters) { chapters.associateBy { it.chapterId } }
            val grouped = remember(bookmarks, chapterById) {
                groupByChapter(bookmarks, chapterById)
            }
            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                grouped.forEach { group ->
                    stickyHeader(key = "h-${group.chapter?.chapterId ?: "untagged"}") {
                        ChapterGroupHeader(chapter = group.chapter)
                    }
                    items(
                        items = group.bookmarks,
                        key = { it.bookmarkId },
                        contentType = { "bookmark" },
                    ) { bookmark ->
                        BookmarkRow(
                            bookmark = bookmark,
                            chapter = group.chapter,
                            onClick = { onBookmarkSeek(bookmark.positionInBookMs) },
                            onLongClick = { actionTarget = bookmark },
                        )
                    }
                }
            }
        }
    }

    // Long-press → action sheet (Rename / Delete).
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    if (actionTarget != null) {
        val target = actionTarget!!
        // m3-expressive D.3 — bookmark long-press sheet pinned to M3E
        // `surfaceContainer` + drag handle tinted with PlaybackAccent
        // (bookmarks live in the playback flow; no dedicated bookmark accent).
        ModalBottomSheet(
            onDismissRequest = { actionTarget = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            dragHandle = { BottomSheetDefaults.DragHandle(color = PlaybackAccent.onContainer) },
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Text(
                    text = displayTitle(target),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
                HorizontalDivider()
                TextButton(
                    onClick = {
                        renameTarget = target
                        actionTarget = null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.bookmark_rename_action),
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                    )
                }
                TextButton(
                    onClick = {
                        deleteTarget = target
                        actionTarget = null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.bookmark_delete_action),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                    )
                }
            }
        }
    }

    if (renameTarget != null) {
        val target = renameTarget!!
        val defaultTitle = displayTitle(target)
        RenameBookmarkDialog(
            initial = defaultTitle,
            onConfirm = { newTitle ->
                scope.launch {
                    bookmarkSource.renameBookmark(
                        id = target.bookmarkId,
                        title = newTitle.ifBlank { null },
                    )
                }
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    if (deleteTarget != null) {
        val target = deleteTarget!!
        DeleteBookmarkDialog(
            onConfirm = {
                scope.launch { bookmarkSource.deleteBookmark(target.bookmarkId) }
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun ChapterGroupHeader(chapter: ChapterEntity?) {
    val label = chapter?.title
        ?: chapter?.let { stringResource(R.string.player_unknown_chapter, it.chapterIndex + 1) }
        ?: stringResource(R.string.bookmark_chapter_untagged)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookmarkRow(
    bookmark: BookmarkEntity,
    chapter: ChapterEntity?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val timeInChapterMs = positionInChapterMs(bookmark, chapter)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = formatHMmSs(timeInChapterMs),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayTitle(bookmark),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatRelativeAddedAt(bookmark.addedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (bookmark.setBySleepTimer) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Filled.Bedtime,
                contentDescription = stringResource(R.string.bookmark_sleep_timer_badge_cd),
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun RenameBookmarkDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.bookmark_rename_dialog_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.bookmark_add_dialog_hint)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
                Text(stringResource(R.string.bookmark_add_dialog_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}

@Composable
private fun DeleteBookmarkDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bookmark_delete_confirm_title)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.bookmark_delete_action),
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

// --- internals ---------------------------------------------------------------------

internal data class BookmarkGroup(
    val chapter: ChapterEntity?,
    val bookmarks: List<BookmarkEntity>,
)

/**
 * Group bookmarks by their chapter, in chapter-index order. Bookmarks whose chapterId has been
 * SET_NULL'd by a re-scan (no matching ChapterEntity) collect under a trailing "untagged" group.
 * Inside each group bookmarks are sorted by `positionInBookMs` ascending.
 */
internal fun groupByChapter(
    bookmarks: List<BookmarkEntity>,
    chapterById: Map<String, ChapterEntity>,
): List<BookmarkGroup> {
    val byChapterId = bookmarks.groupBy { it.chapterId }
    val orderedChapters = chapterById.values.sortedBy { it.chapterIndex }
    val groups = mutableListOf<BookmarkGroup>()
    for (chapter in orderedChapters) {
        val bms = byChapterId[chapter.chapterId].orEmpty()
        if (bms.isNotEmpty()) {
            groups += BookmarkGroup(
                chapter = chapter,
                bookmarks = bms.sortedBy { it.positionInBookMs },
            )
        }
    }
    val untagged = bookmarks.filter { it.chapterId == null || it.chapterId !in chapterById }
    if (untagged.isNotEmpty()) {
        groups += BookmarkGroup(
            chapter = null,
            bookmarks = untagged.sortedBy { it.positionInBookMs },
        )
    }
    return groups
}

@Composable
private fun displayTitle(bookmark: BookmarkEntity): String {
    val raw = bookmark.title
    return when {
        !raw.isNullOrBlank() -> raw
        bookmark.setBySleepTimer -> stringResource(R.string.bookmark_sleep_timer_default_title)
        else -> stringResource(R.string.bookmark_default_title)
    }
}

internal fun positionInChapterMs(bookmark: BookmarkEntity, chapter: ChapterEntity?): Long {
    val start = chapter?.positionInBookMs ?: 0L
    return (bookmark.positionInBookMs - start).coerceAtLeast(0L)
}

/**
 * Hours-aware mm:ss formatter (the `ui/playback` one only handles minutes:seconds and is
 * `internal` to that package). Bookmarks can land deep inside a long audiobook chapter — show
 * `h:mm:ss` past the hour mark, `mm:ss` below it.
 */
internal fun formatHMmSs(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/**
 * Coarse "x minutes / hours / days ago" rendering for the bookmark-row addedAt timestamp.
 * Deliberately not using `DateUtils.getRelativeTimeSpanString` — Robolectric-flaky and pulls in
 * platform locale plumbing we don't need for a single-line per-row label. Cap at "x days ago";
 * older bookmarks just say "long ago".
 */
@Composable
internal fun formatRelativeAddedAt(addedAt: Long): String {
    val now = System.currentTimeMillis()
    val deltaMs = (now - addedAt).coerceAtLeast(0L)
    val minutes = deltaMs / 60_000L
    val hours = minutes / 60L
    val days = hours / 24L
    return when {
        minutes < 1L -> stringResource(R.string.bookmark_added_just_now)
        minutes < 60L -> stringResource(R.string.bookmark_added_minutes_ago, minutes.toInt())
        hours < 24L -> stringResource(R.string.bookmark_added_hours_ago, hours.toInt())
        days < 30L -> stringResource(R.string.bookmark_added_days_ago, days.toInt())
        else -> stringResource(R.string.bookmark_added_long_ago)
    }
}
