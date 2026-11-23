package com.eight87.whisperboy.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * m3-expressive Phase C — regression guard for the per-row accent
 * palette.
 *
 * Two cheap invariants:
 *
 * 1. Every category accent has a `container` that is strictly
 *    distinct from its `onContainer`. A typo that points both fields
 *    at the same colour would render an invisible glyph inside a
 *    coloured circle — compiles fine, looks broken. The check
 *    enumerates the public `darkAccents` map so adding a new pair to
 *    the map automatically pulls it into the assertion.
 * 2. [accentFor] returns the same instance the canonical top-level
 *    `val` advertises for each known id. Prevents drift between the
 *    `when` arms and the hand-named accents.
 */
class CategoryAccentTest {

  @Test fun `every category accent has contrasting container and onContainer`() {
    assertEquals(
      "darkAccents must cover all six categories",
      6,
      darkAccents.size,
    )
    assert(darkAccents.values.all { it.container != it.onContainer }) {
      "Every CategoryAccent must have container != onContainer"
    }
  }

  @Test fun `accentFor returns the canonical accent for every known id`() {
    assertEquals(PlaybackAccent, accentFor("playback"))
    assertEquals(SleepTimerAccent, accentFor("sleep"))
    assertEquals(LibraryAccent, accentFor("library"))
    assertEquals(ThemeAccent, accentFor("theme"))
    assertEquals(AboutAccent, accentFor("about"))
    assertEquals(LicensesAccent, accentFor("licenses"))
  }

  @Test fun `accentFor hashes unknown ids deterministically into the category palette`() {
    val palette = setOf(
      PlaybackAccent, SleepTimerAccent, LibraryAccent, ThemeAccent, AboutAccent, LicensesAccent,
    )
    val ids = listOf("not-a-real-id", "about.app_name", "about.github", "about.license", "")
    ids.forEach { id ->
      assert(accentFor(id) in palette) { "accentFor(\"$id\") must come from the palette" }
      assertEquals(accentFor(id), accentFor(id))
    }
    // Sub-rows must not all collapse to one hue (the bug the hash fallback fixed).
    assert(ids.map(::accentFor).toSet().size > 1) { "unknown ids should spread across the palette" }
  }

  @Test fun `category accents are not all identical`() {
    // Cheap sanity: at least Playback and Library should differ —
    // a regression that points every `val` at the same Color would
    // make the test in the first @Test still pass (container vs
    // onContainer) but turn every row the same colour.
    assertNotEquals(PlaybackAccent, LibraryAccent)
  }
}
