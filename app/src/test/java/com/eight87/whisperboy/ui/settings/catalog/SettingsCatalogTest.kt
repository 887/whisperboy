package com.eight87.whisperboy.ui.settings.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.eight87.whisperboy.theme.LibraryAccent
import com.eight87.whisperboy.theme.SleepTimerAccent
import com.eight87.whisperboy.theme.ThemeAccent

/**
 * JVM-only catalog shape + search index tests. No Robolectric needed —
 * the catalog itself is plain data, and `SettingsCatalog.search` operates
 * on the inline canonical English label / subtitle / keywords on each
 * entry.
 */
class SettingsCatalogTest {

    @Test
    fun `every section has at least one entry`() {
        Section.entries.forEach { section ->
            val entries = SettingsCatalog.bySection(section)
            assertTrue("section $section has no entries", entries.isNotEmpty())
        }
    }

    @Test
    fun `all entry ids are unique`() {
        val ids = SettingsCatalog.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `every const ID resolves via byId`() {
        // Touch each stable id so renaming any of them in the catalog
        // without updating this test trips a missing-id failure.
        val ids = listOf(
            SettingsCatalog.ID_THEME_MODE,
            SettingsCatalog.ID_BASE_THEME,
            SettingsCatalog.ID_TINT_BY_ALBUM_ART,
            SettingsCatalog.ID_ALBUM_ART_BACKGROUND,
            SettingsCatalog.ID_CUSTOM_CHROME_TINT,
            SettingsCatalog.ID_DEFAULT_SPEED,
            SettingsCatalog.ID_DEFAULT_SKIP_SILENCE,
            SettingsCatalog.ID_DEFAULT_GAIN_DB,
            SettingsCatalog.ID_REWIND_SECONDS,
            SettingsCatalog.ID_FORWARD_SECONDS,
            SettingsCatalog.ID_AUTO_REWIND_SECONDS,
            SettingsCatalog.ID_SYSTEM_EQUALIZER,
            SettingsCatalog.ID_SLEEP_DEFAULT_DURATION,
            SettingsCatalog.ID_SLEEP_FADE_OUT,
            SettingsCatalog.ID_SLEEP_SHAKE_TO_RESUME,
            SettingsCatalog.ID_SLEEP_AUTO_ARM_WINDOW,
            SettingsCatalog.ID_LIBRARY_FOLDERS,
            SettingsCatalog.ID_LIBRARY_SORT,
            SettingsCatalog.ID_LIBRARY_GRID_MODE,
            SettingsCatalog.ID_LIBRARY_SCAN_FILTERS,
            SettingsCatalog.ID_LIBRARY_RESCAN,
            SettingsCatalog.ID_ABOUT,
        )
        ids.forEach { id -> assertNotNull(SettingsCatalog.byId(id)) }
    }

    @Test
    fun `empty search yields empty result list`() {
        assertTrue(SettingsCatalog.search("").isEmpty())
        assertTrue(SettingsCatalog.search("   ").isEmpty())
    }

    @Test
    fun `search matches label substring case-insensitively`() {
        val results = SettingsCatalog.search("theme")
        assertTrue(
            "expected at least one match for 'theme'; got ${results.map { it.id }}",
            results.any { it.id == SettingsCatalog.ID_THEME_MODE },
        )
    }

    @Test
    fun `search matches subtitle substring`() {
        val results = SettingsCatalog.search("rescan")
        assertTrue(
            "expected rescan row in results; got ${results.map { it.id }}",
            results.any { it.id == SettingsCatalog.ID_LIBRARY_RESCAN },
        )
    }

    @Test
    fun `search matches keyword`() {
        val results = SettingsCatalog.search("material")
        assertTrue(
            "expected base-theme row via 'material' keyword; got ${results.map { it.id }}",
            results.any { it.id == SettingsCatalog.ID_BASE_THEME },
        )
    }

    @Test
    fun `Appearance section order matches tonearmboy parity`() {
        val ids = SettingsCatalog.bySection(Section.Appearance).map { it.id }
        assertEquals(
            listOf(
                SettingsCatalog.ID_THEME_MODE,
                SettingsCatalog.ID_BASE_THEME,
                SettingsCatalog.ID_TINT_BY_ALBUM_ART,
                SettingsCatalog.ID_ALBUM_ART_BACKGROUND,
                SettingsCatalog.ID_CUSTOM_CHROME_TINT,
            ),
            ids,
        )
    }

    @Test
    fun `Appearance entries do not all share one accent`() {
        val accents = SettingsCatalog.bySection(Section.Appearance).map { it.accent }
        assertTrue(
            "expected at least 2 distinct accents in Appearance; got $accents",
            accents.toSet().size >= 2,
        )
    }

    @Test
    fun `per-entry accents land on the expected category pairs`() {
        assertEquals(ThemeAccent, SettingsCatalog.byId(SettingsCatalog.ID_THEME_MODE).accent)
        assertEquals(ThemeAccent, SettingsCatalog.byId(SettingsCatalog.ID_BASE_THEME).accent)
        assertEquals(LibraryAccent, SettingsCatalog.byId(SettingsCatalog.ID_TINT_BY_ALBUM_ART).accent)
        assertEquals(SleepTimerAccent, SettingsCatalog.byId(SettingsCatalog.ID_CUSTOM_CHROME_TINT).accent)
    }

    @Test
    fun `search miss returns empty`() {
        val results = SettingsCatalog.search("this-string-cannot-match-anything-xyzzy")
        assertTrue(results.isEmpty())
    }
}
