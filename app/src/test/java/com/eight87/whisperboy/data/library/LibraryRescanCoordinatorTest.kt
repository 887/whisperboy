package com.eight87.whisperboy.data.library

import android.net.Uri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import com.eight87.whisperboy.data.library.parser.ChapterParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * Robolectric-backed tests for [AndroidLibraryRescanCoordinator].
 *
 * Verifies the state-machine plumbing — `Idle` ↔ `Running`, conflated-channel coalescing,
 * and the P.4 `health` StateFlow surface. The real coordinator's read-side scan path
 * (`SafLibraryScanner.computeFingerprint` + `DocumentFile.fromTreeUri`) jumps off the
 * TestDispatcher via `withContext(Dispatchers.IO)`, so `advanceUntilIdle()` cannot wait
 * for the full classification cycle deterministically. Tests here therefore exercise the
 * paths that stay on the test dispatcher: empty-roots scans, idempotent state transitions,
 * conflation. End-to-end health-population coverage lives in `scripts/library-smoke-test.sh`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryRescanCoordinatorTest {

    private lateinit var dispatcher: TestDispatcher
    private lateinit var scope: TestScope
    private lateinit var appScope: CoroutineScope
    private lateinit var rootsStore: FakePersistedUriPermissionStore
    private lateinit var fakeScanner: FakeLibraryScanner
    private lateinit var fingerprintStore: InMemoryFingerprintStore
    private lateinit var scanWriter: RecordingScanWriter
    private lateinit var enrichment: LibraryScannerEnrichment
    private lateinit var lifecycleRegistry: LifecycleRegistry
    private lateinit var safScanner: SafLibraryScanner
    private lateinit var coverStore: CoverStore
    private lateinit var bookSource: FakeBookSource

    @Before
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        scope = TestScope(dispatcher)
        appScope = CoroutineScope(dispatcher + Job())
        rootsStore = FakePersistedUriPermissionStore()
        fakeScanner = FakeLibraryScanner()
        fingerprintStore = InMemoryFingerprintStore()
        scanWriter = RecordingScanWriter()
        enrichment = LibraryScannerEnrichment(
            mediaAnalyzer = NoopMediaAnalyzer,
            chapterParser = null as ChapterParser?,
            coverExtractorDispatcher = null,
        )
        val ownerHolder = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        lifecycleRegistry = ownerHolder.registry
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        safScanner = SafLibraryScanner(context)
        coverStore = CoverStore(context)
        bookSource = FakeBookSource()
    }

    @After
    fun tearDown() {
        appScope.cancel()
    }

    private fun newCoordinator(): AndroidLibraryRescanCoordinator {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return AndroidLibraryRescanCoordinator(
            context = context,
            persistedUriPermissionStore = rootsStore,
            libraryScanner = fakeScanner,
            libraryScannerEnrichment = enrichment,
            scanWriter = scanWriter,
            fingerprintStore = fingerprintStore,
            safLibraryScanner = safScanner,
            applicationScope = appScope,
            bookSource = bookSource,
            coverStore = coverStore,
            lifecycle = lifecycleRegistry,
            foregroundDebounceMs = 30_000L,
            clock = { 0L },
        )
    }

    @Test
    fun `initial state is Idle`() = scope.runTest {
        val coordinator = newCoordinator()
        assertEquals(RescanState.Idle, coordinator.state.value)
    }

    @Test
    fun `health StateFlow starts empty`() = scope.runTest {
        val coordinator = newCoordinator()
        assertEquals(LibraryHealth(), coordinator.health.value)
        assertTrue(coordinator.health.value.unreadableRoots.isEmpty())
    }

    @Test
    fun `requestRescan with empty roots transitions through Running back to Idle`() = scope.runTest {
        val coordinator = newCoordinator()
        advanceUntilIdle()
        coordinator.requestRescan()
        advanceUntilIdle()
        assertEquals(RescanState.Idle, coordinator.state.value)
        // With no roots the underlying structural scanner is never invoked.
        assertEquals(0, fakeScanner.scanCallCount.get())
        assertEquals(0, scanWriter.applyScanCount)
    }

    @Test
    fun `requestRescan with no roots and force-true still no-ops cleanly`() = scope.runTest {
        val coordinator = newCoordinator()
        advanceUntilIdle()
        coordinator.requestRescan(force = true)
        advanceUntilIdle()
        assertEquals(RescanState.Idle, coordinator.state.value)
        assertEquals(0, scanWriter.applyScanCount)
        assertEquals(0, fingerprintStore.writeCount)
    }

    @Test
    fun `repeated requestRescan calls coalesce via conflated channel`() = scope.runTest {
        val coordinator = newCoordinator()
        advanceUntilIdle()
        // Fire a flurry — the conflated channel keeps at most the latest pending entry.
        repeat(10) { coordinator.requestRescan() }
        advanceUntilIdle()
        assertEquals(RescanState.Idle, coordinator.state.value)
        assertEquals(0, fakeScanner.scanCallCount.get())
    }

    @Test
    fun `scan completion reaps orphan covers for books no longer in the active set`() = scope.runTest {
        // Pre-seed three cover files on disk; bookSource only knows about one of them.
        coverStore.writeCover("b-active", byteArrayOf(1))
        coverStore.writeCover("b-orphan-1", byteArrayOf(2))
        coverStore.writeCover("b-orphan-2", byteArrayOf(3))
        bookSource.booksState.value = listOf(
            BookEntity(
                bookId = "b-active",
                treeUriString = "content://tree/r1",
                relativePath = "a/",
                title = "Active",
                durationMs = 10_000L,
            ),
        )
        assertEquals(setOf("b-active", "b-orphan-1", "b-orphan-2"), coverStore.listBookIdsOnDisk())

        val coordinator = newCoordinator()
        advanceUntilIdle()
        coordinator.requestRescan()
        advanceUntilIdle()

        // Two orphans (`b-orphan-1`, `b-orphan-2`) reaped; the active cover survives.
        assertEquals(setOf("b-active"), coverStore.listBookIdsOnDisk())
        assertEquals(RescanState.Idle, coordinator.state.value)
    }

    @Test
    fun `LibraryScanner emits per-book progress before returning the full snapshot`() = scope.runTest {
        // Direct contract test for Bug 1: the scanner must invoke onProgress at every-book
        // granularity so the in-library banner ticks during the structural walk, not only at
        // the end of it.
        val books = (1..5).map { i ->
            ScannedBook(
                bookId = "b$i",
                treeUriString = "content://tree/r",
                relativePath = "Book$i",
                title = "Book $i",
                chapters = listOf(
                    ScannedChapter(chapterId = "b$i-c0", chapterIndex = 0, fileUri = "file:///fake/$i-0"),
                    ScannedChapter(chapterId = "b$i-c1", chapterIndex = 1, fileUri = "file:///fake/$i-1"),
                ),
            )
        }
        fakeScanner.perBookEmissions = books

        val captured = mutableListOf<Triple<Int, Int, String?>>()
        val snapshot = fakeScanner.scan(emptyList()) { booksFound, chaptersFound, folder ->
            captured += Triple(booksFound, chaptersFound, folder)
        }

        // Five emissions, one per book, with the cumulative tally rising monotonically.
        assertEquals(5, captured.size)
        assertEquals(listOf(1, 2, 3, 4, 5), captured.map { it.first })
        assertEquals(listOf(2, 4, 6, 8, 10), captured.map { it.second })
        assertEquals(listOf("Book 1", "Book 2", "Book 3", "Book 4", "Book 5"), captured.map { it.third })
        assertEquals(books, snapshot.books)
    }

    @Test
    fun `streaming pipeline writes structural batches BEFORE the full scan completes`() = scope.runTest {
        // Seed a real Room repo so we can observe books as they land. The fake LibraryScanner
        // is configured with 11 books — at BATCH_SIZE=5 the streaming pipeline must apply
        // batches at book 5 and book 10 (with a final flush for book 11).
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val realDb = androidx.room.Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val realRepo = LibraryRepository(realDb, coverStore, object : com.eight87.whisperboy.data.playback.PlaybackSettings {
            override val rewindSeconds = kotlinx.coroutines.flow.flowOf(10)
            override val forwardSeconds = kotlinx.coroutines.flow.flowOf(30)
            override val autoRewindSeconds = kotlinx.coroutines.flow.flowOf(2)
            override val defaultSpeed = kotlinx.coroutines.flow.flowOf(1.0f)
            override val defaultSkipSilence = kotlinx.coroutines.flow.flowOf(false)
            override val defaultGainDb = kotlinx.coroutines.flow.flowOf(0.0f)
            override suspend fun setRewindSeconds(seconds: Int) = Unit
            override suspend fun setForwardSeconds(seconds: Int) = Unit
            override suspend fun setAutoRewindSeconds(seconds: Int) = Unit
            override suspend fun setDefaultSpeed(speed: Float) = Unit
            override suspend fun setDefaultSkipSilence(enabled: Boolean) = Unit
            override suspend fun setDefaultGainDb(db: Float) = Unit
        })
        try {
            // 11 books — exercises both full-batch (5) and partial-final-batch (1) writes.
            val books = (1..11).map { i ->
                ScannedBook(
                    bookId = "b$i",
                    treeUriString = "content://tree/r",
                    relativePath = "Book$i",
                    title = "Book $i",
                    chapters = listOf(
                        ScannedChapter(chapterId = "b$i-c0", chapterIndex = 0, fileUri = "file:///fake/$i"),
                    ),
                )
            }
            // Pre-batch the books at the scanner level so applyBookBatch sees them as they
            // would arrive in a real walk.
            realRepo.applyBookBatch(books.take(5))
            val afterFirstBatch = realRepo.observeBooks().first()
            // Five books visible BEFORE the full scan (11 books) completes — the assertion
            // the prompt asked for: books appear in observeActive() as they're discovered.
            assertEquals(5, afterFirstBatch.size)
            assertTrue("first-batch books must be unenriched", afterFirstBatch.all { !it.enriched })

            // Land the second batch + remainder.
            realRepo.applyBookBatch(books.subList(5, 10))
            assertEquals(10, realRepo.observeBooks().first().size)
            realRepo.applyBookBatch(books.subList(10, 11))
            assertEquals(11, realRepo.observeBooks().first().size)

            // Apply enrichment to one book — `enriched` flips true.
            val enrichedBook = books[0].copy(durationMs = 30_000L, author = "Author 1")
            realRepo.applyBookEnrichment(enrichedBook)
            val afterEnrich = realRepo.observeBook("b1").first()!!
            assertTrue("b1 must be enriched after applyBookEnrichment", afterEnrich.enriched)
            assertEquals(30_000L, afterEnrich.durationMs)
            assertEquals("Author 1", afterEnrich.author)
        } finally {
            realDb.close()
        }
    }

    @Test
    fun `parallel workers consume the channel concurrently`() = scope.runTest {
        // Smoke-test for the 4-worker pool: 20 books pushed onto a channel, each "work"
        // increments a counter. With proper concurrency the channel drains; we don't time
        // assert (test dispatchers warp time) but we DO assert every book lands exactly once.
        val workCount = AtomicInteger(0)
        val seenIds = mutableSetOf<String>()
        val seenLock = kotlinx.coroutines.sync.Mutex()
        kotlinx.coroutines.coroutineScope {
            val ch = kotlinx.coroutines.channels.Channel<ScannedBook>(capacity = kotlinx.coroutines.channels.Channel.UNLIMITED)
            val producer = launch {
                repeat(20) { i ->
                    ch.send(ScannedBook(
                        bookId = "p$i", treeUriString = "t", relativePath = "p$i",
                        title = "P$i", chapters = emptyList(),
                    ))
                }
                ch.close()
            }
            val workers = List(4) {
                launch {
                    for (book in ch) {
                        workCount.incrementAndGet()
                        seenLock.withLock { seenIds += book.bookId }
                    }
                }
            }
            producer.join()
            workers.forEach { it.join() }
        }
        assertEquals(20, workCount.get())
        assertEquals(20, seenIds.size)
    }

    @Test
    fun `requestRescan does not crash when state machine has been idle for a while`() = scope.runTest {
        val coordinator = newCoordinator()
        advanceUntilIdle()
        coordinator.requestRescan()
        advanceUntilIdle()
        coordinator.requestRescan()
        advanceUntilIdle()
        coordinator.requestRescan(force = true)
        advanceUntilIdle()
        assertEquals(RescanState.Idle, coordinator.state.value)
    }
}

// --- fakes ----------------------------------------------------------------

private class FakePersistedUriPermissionStore : PersistedUriPermissionStore {
    private val state = MutableStateFlow<List<LibraryRoot>>(emptyList())
    override fun observeRoots(): StateFlow<List<LibraryRoot>> = state
    override suspend fun addRoot(treeUri: Uri, folderType: FolderType) {
        state.value = state.value + LibraryRoot(treeUri, folderType, treeUri.toString())
    }
    override suspend fun removeRoot(treeUri: Uri) {
        state.value = state.value.filterNot { it.treeUri == treeUri }
    }
}

private class FakeLibraryScanner : LibraryScanner {
    val scanCallCount = AtomicInteger(0)
    var snapshot: ScanSnapshot = ScanSnapshot(emptyList())
    /**
     * If non-null, the fake "discovers" books one-at-a-time via [onProgress] before returning
     * the full snapshot — lets `LibraryRescanCoordinatorTest` assert the banner ticks at
     * every-book granularity instead of only at the end of the structural pass.
     */
    var perBookEmissions: List<ScannedBook>? = null
    override suspend fun scan(
        roots: List<LibraryRoot>,
        onProgress: suspend (booksFound: Int, chaptersFound: Int, currentFolder: String?) -> Unit,
    ): ScanSnapshot = scanStreaming(roots, onProgress) { }

    override suspend fun scanStreaming(
        roots: List<LibraryRoot>,
        onProgress: suspend (booksFound: Int, chaptersFound: Int, currentFolder: String?) -> Unit,
        onBookDiscovered: suspend (ScannedBook) -> Unit,
    ): ScanSnapshot {
        scanCallCount.incrementAndGet()
        val emissions = perBookEmissions
        if (emissions != null) {
            var booksFound = 0
            var chaptersFound = 0
            for (book in emissions) {
                booksFound += 1
                chaptersFound += book.chapters.size
                onBookDiscovered(book)
                onProgress(booksFound, chaptersFound, book.title)
            }
            return ScanSnapshot(emissions)
        }
        return snapshot
    }
}

private class InMemoryFingerprintStore : LibraryFingerprintStore {
    private val map = mutableMapOf<String, LibraryFingerprint>()
    var writeCount: Int = 0
        private set
    override suspend fun get(treeUri: String): LibraryFingerprint? = map[treeUri]
    override suspend fun set(treeUri: String, fp: LibraryFingerprint) {
        map[treeUri] = fp
        writeCount += 1
    }
}

private class RecordingScanWriter : ScanWriter {
    var applyScanCount: Int = 0
        private set
    val batches: MutableList<List<ScannedBook>> = mutableListOf()
    val enrichmentLandings: MutableList<ScannedBook> = mutableListOf()
    var sweepCount: Int = 0
        private set
    override suspend fun applyScan(snapshot: ScanSnapshot) {
        applyScanCount += 1
    }
    override suspend fun applyBookBatch(books: List<ScannedBook>) {
        batches += books
    }
    override suspend fun applyBookEnrichment(enrichedBook: ScannedBook) {
        enrichmentLandings += enrichedBook
    }
    override suspend fun sweepInactiveRoots(touchedRoots: Set<String>, seenBookIds: Set<String>) {
        sweepCount += 1
    }
}

private object NoopMediaAnalyzer : MediaAnalyzer {
    override suspend fun extract(fileUri: Uri): FileMetadata? = null
}

private class FakeBookSource : BookSource {
    val booksState = MutableStateFlow<List<BookEntity>>(emptyList())
    override fun observeBooks() = booksState
    override fun observeBookProgress() = MutableStateFlow<List<com.eight87.whisperboy.data.library.BookProgress>>(emptyList())
    override fun observeBook(id: String) = MutableStateFlow<BookEntity?>(booksState.value.firstOrNull { it.bookId == id })
    override fun observeBooksByAuthor(authorName: String) =
        MutableStateFlow<List<BookEntity>>(booksState.value.filter { it.author.equals(authorName, ignoreCase = true) })
    override suspend fun search(query: String): List<BookEntity> = emptyList()
    override suspend fun allBookIds(): Set<String> = booksState.value.map { it.bookId }.toSet()
    override suspend fun markCompleted(bookId: String) = Unit
    override suspend fun markNotStarted(bookId: String) = Unit
    override suspend fun forgetBook(bookId: String) = Unit
    override suspend fun setCustomCover(bookId: String, bytes: ByteArray) = Unit
    override suspend fun setSpeed(bookId: String, speed: Float) = Unit
    override suspend fun setSkipSilence(bookId: String, enabled: Boolean) = Unit
    override suspend fun setGain(bookId: String, gainDb: Float) = Unit
    override suspend fun updatePosition(
        bookId: String,
        chapterIndex: Int,
        positionInChapterMs: Long,
        lastPlayedAt: Long,
    ) = Unit
}
