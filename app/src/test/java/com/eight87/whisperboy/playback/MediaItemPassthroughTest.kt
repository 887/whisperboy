package com.eight87.whisperboy.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression guard for the v1.0-6317488 fix:
 * [WhisperboyLibrarySessionCallback.onAddMediaItems] used to call `resolveForPlayback`
 * unconditionally, which would `mapNotNull`-drop any MediaItem whose mediaId didn't start
 * with `book:` and which had no `requestMetadata.searchQuery` — even ones whose URI was
 * already set by our own PlaybackController.startPlayback. The session received zero items
 * and ExoPlayer reported BUFFERING → ENDED with no error and no notification.
 *
 * These tests assert the discriminator: a MediaItem built with `.setUri(...)` carries a
 * non-null `localConfiguration.uri`; the URI-less placeholders used by
 * Auto / lockscreen-resume path do not. The pass-through guard in the callback keys on
 * exactly that property.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaItemPassthroughTest {

    @Test
    fun `in-app item built with setUri has localConfiguration uri`() {
        val item = MediaItem.Builder()
            .setMediaId("chapter-42")
            .setUri(Uri.parse("content://example/audiobook.m4b"))
            .build()
        assertNotNull(
            "Items from PlaybackController.startPlayback must carry a URI so the session callback's pass-through guard fires.",
            item.localConfiguration?.uri,
        )
    }

    @Test
    fun `placeholder item without setUri has no localConfiguration`() {
        val item = MediaItem.Builder()
            .setMediaId("book:abc123")
            .build()
        assertNull(
            "Auto / voice-search / lockscreen-resume placeholders arrive URI-less; the callback must run the bookId / searchQuery lookup for them.",
            item.localConfiguration,
        )
    }

    @Test
    fun `voice-search style item has no uri but does carry searchQuery on requestMetadata`() {
        val item = MediaItem.Builder()
            .setMediaId("__searchQuery__")
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder()
                    .setSearchQuery("the will of the many")
                    .build(),
            )
            .build()
        assertNull(item.localConfiguration)
        assertNotNull(item.requestMetadata.searchQuery)
    }
}
