package com.eight87.whisperboy.ui.routes

import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.eight87.whisperboy.AboutRoute
import com.eight87.whisperboy.LibraryFoldersRoute
import com.eight87.whisperboy.LicensesRoute
import com.eight87.whisperboy.SettingsRoute
import com.eight87.whisperboy.SettingsSearchRoute
import com.eight87.whisperboy.ui.settings.AboutScreen
import com.eight87.whisperboy.ui.settings.LicensesScreen
import com.eight87.whisperboy.ui.settings.SettingsScreen
import com.eight87.whisperboy.ui.settings.catalog.SettingsSearchScreen

/**
 * Phase K — Settings hub + leaf sub-pages (About, Licenses). Theme /
 * Playback / Sleep timer / Library defaults all live as dialog pickers
 * inside the [SettingsScreen] catalog now, so they no longer have
 * their own routes. The Library folders sub-tree lives in
 * `LibraryDestinations` to keep this file scoped.
 */
@Suppress("NOTHING_TO_INLINE") internal inline fun EntryProviderScope<NavKey>.registerSettingsEntries(scope: RouteScope) {
    val graph = scope.graph
    val backStack = scope.backStack

    entry<SettingsRoute> {
        SettingsScreen(
            themeSettings = graph.themeSettings,
            playbackSettings = graph.playbackSettings,
            sleepTimerSettings = graph.sleepTimerSettings,
            libraryUiSettings = graph.libraryUiSettings,
            libraryScanFilterSettings = graph.libraryScanFilterSettings,
            libraryRescanCoordinator = graph.libraryRescanCoordinator,
            onBack = { backStack.removeLastOrNull() },
            onLibraryFoldersClick = { backStack.add(LibraryFoldersRoute) },
            onAboutClick = { backStack.add(AboutRoute) },
            onOpenSearch = { backStack.add(SettingsSearchRoute) },
            modifier = Modifier.safeDrawingPadding(),
        )
    }
    entry<SettingsSearchRoute> {
        SettingsSearchScreen(
            onBack = { backStack.removeLastOrNull() },
            // Selecting a search result pops back to the Settings root.
            // Catalog is flat there — every entry lives on the root surface.
            onResult = { _ -> backStack.removeLastOrNull() },
        )
    }
    entry<AboutRoute> {
        AboutScreen(
            onBack = { backStack.removeLastOrNull() },
            onLicensesClick = { backStack.add(LicensesRoute) },
            modifier = Modifier.safeDrawingPadding(),
        )
    }
    entry<LicensesRoute> {
        LicensesScreen(
            onBack = { backStack.removeLastOrNull() },
            modifier = Modifier.safeDrawingPadding(),
        )
    }
}
