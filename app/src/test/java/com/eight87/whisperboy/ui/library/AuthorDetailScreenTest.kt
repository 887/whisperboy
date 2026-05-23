package com.eight87.whisperboy.ui.library

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.eight87.whisperboy.data.library.BookEntity
import com.eight87.whisperboy.data.library.BookSource
import com.eight87.whisperboy.ui.settings.TestApplication
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R.F.9 — Robolectric Compose coverage for [AuthorDetailScreen].
 *
 * Injects a fake [BookSource] whose `observeBooksByAuthor` returns a [MutableStateFlow]
 * the test controls. Other [BookSource] methods throw — they aren't reached on this surface.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class AuthorDetailScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun book(id: String, title: String) = BookEntity(
        bookId = id,
        treeUriString = "content://x",
        relativePath = id,
        title = title,
        author = "Some Author",
        durationMs = 1000L,
    )

    private fun fakeSource(flow: Flow<List<BookEntity>>): BookSource = object : BookSource {
        override fun observeBooks(): Flow<List<BookEntity>> = emptyFlow()
        override fun observeBookProgress(): Flow<List<com.eight87.whisperboy.data.library.BookProgress>> = emptyFlow()
        override fun observeBook(id: String): Flow<BookEntity?> = emptyFlow()
        override fun observeBooksByAuthor(authorName: String): Flow<List<BookEntity>> = flow
        override suspend fun search(query: String): List<BookEntity> = emptyList()
        override suspend fun allBookIds(): Set<String> = emptySet()
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

    @Test
    fun `top app bar renders the author name as title`() {
        val flow = MutableStateFlow<List<BookEntity>>(emptyList())
        composeRule.setContent {
            AuthorDetailScreen(
                authorName = "Ursula K. Le Guin",
                bookSource = fakeSource(flow),
                onBack = {},
                onBookTap = {},
            )
        }
        composeRule.onNodeWithText("Ursula K. Le Guin").assertExists()
    }

    @Test
    fun `empty state renders when the flow emits an empty list`() {
        val flow = MutableStateFlow<List<BookEntity>>(emptyList())
        composeRule.setContent {
            AuthorDetailScreen(
                authorName = "Nobody",
                bookSource = fakeSource(flow),
                onBack = {},
                onBookTap = {},
            )
        }
        composeRule.onNodeWithText("No books by this author.").assertExists()
    }

    @Test
    fun `one tile renders per book when the flow emits a non-empty list`() {
        val flow = MutableStateFlow(
            listOf(
                book("b1", "A Wizard of Earthsea"),
                book("b2", "The Tombs of Atuan"),
            ),
        )
        composeRule.setContent {
            AuthorDetailScreen(
                authorName = "Ursula K. Le Guin",
                bookSource = fakeSource(flow),
                onBack = {},
                onBookTap = {},
            )
        }
        // Both titles should appear — at adaptive 160dp width on the default Robolectric
        // window the grid lays them side-by-side in the first row.
        composeRule.onNodeWithText("A Wizard of Earthsea").assertExists()
        composeRule.onNodeWithText("The Tombs of Atuan").assertExists()
        // Empty-state copy must be absent when there are tiles.
        composeRule.onAllNodesWithText("No books by this author.").assertCountEquals(0)
    }

    @Test
    fun `back arrow tap fires onBack callback`() {
        var backFired = 0
        val flow = MutableStateFlow<List<BookEntity>>(emptyList())
        composeRule.setContent {
            AuthorDetailScreen(
                authorName = "Author",
                bookSource = fakeSource(flow),
                onBack = { backFired++ },
                onBookTap = {},
            )
        }
        composeRule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backFired)
        assertTrue(backFired > 0)
    }
}
