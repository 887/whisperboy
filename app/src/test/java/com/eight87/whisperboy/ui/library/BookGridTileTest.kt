package com.eight87.whisperboy.ui.library

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.eight87.whisperboy.data.library.BookEntity
import com.eight87.whisperboy.ui.settings.TestApplication
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric Compose coverage for [BookGridTile] — the cover/title/author
 * cell rendered in the library grid. [BookGridTile] is `internal` so the test
 * can mount it without standing up the whole library screen.
 *
 * The tile delegates cover rendering to [com.eight87.whisperboy.ui.common.CoverArt];
 * passing a null cover path here exercises the placeholder branch (Coil never
 * fires under Robolectric without a network/decode layer).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class BookGridTileTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val book = BookEntity(
        bookId = "b1",
        treeUriString = "content://example/tree",
        relativePath = "book/",
        title = "The Hobbit",
        author = "J.R.R. Tolkien",
        durationMs = 3_600_000L,
        coverPath = null,
    )

    @Test
    fun `renders title and author`() {
        composeRule.setContent {
            BookGridTile(book = book, positionInBookMs = 0L, onTap = {}, onLongPress = {})
        }
        composeRule.onNodeWithText("The Hobbit").assertExists()
        composeRule.onNodeWithText("J.R.R. Tolkien").assertExists()
    }

    @Test
    fun `falls back to unknown author when author is null`() {
        composeRule.setContent {
            BookGridTile(
                book = book.copy(author = null),
                positionInBookMs = 0L,
                onTap = {},
                onLongPress = {},
            )
        }
        composeRule.onNodeWithText("Unknown author").assertExists()
    }

    @Test
    fun `composes without crash when cover path is null`() {
        // Smoke: the cover branch is the placeholder; no exception thrown.
        composeRule.setContent {
            BookGridTile(book = book, positionInBookMs = 0L, onTap = {}, onLongPress = {})
        }
        composeRule.onNodeWithText("The Hobbit").assertExists()
    }

    @Test
    fun `tap on the tile fires onTap`() {
        var taps = 0
        composeRule.setContent {
            BookGridTile(book = book, positionInBookMs = 0L, onTap = { taps++ }, onLongPress = {})
        }
        composeRule.onNodeWithText("The Hobbit").performClick()
        assertEquals(1, taps)
    }

    @Test
    fun `long-press on the tile fires onLongPress`() {
        var longs = 0
        composeRule.setContent {
            BookGridTile(book = book, positionInBookMs = 0L, onTap = {}, onLongPress = { longs++ })
        }
        composeRule.onNodeWithText("The Hobbit").performTouchInput { longClick() }
        composeRule.waitForIdle()
        assertEquals(1, longs)
    }
}
