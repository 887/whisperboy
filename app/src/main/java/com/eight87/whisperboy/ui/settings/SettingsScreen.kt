package com.eight87.whisperboy.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.height
import com.eight87.whisperboy.R
import com.eight87.whisperboy.data.library.LibraryRescanCoordinator
import com.eight87.whisperboy.data.library.LibraryScanFilterSettings
import com.eight87.whisperboy.data.library.LibraryUiSettings
import com.eight87.whisperboy.data.playback.PlaybackSettings
import com.eight87.whisperboy.data.playback.SleepTimerSettings
import com.eight87.whisperboy.data.theme.ThemeSettings
import com.eight87.whisperboy.ui.settings.catalog.RenderSection
import com.eight87.whisperboy.ui.settings.catalog.Section
import com.eight87.whisperboy.ui.settings.catalog.SettingsBindings
import com.eight87.whisperboy.ui.settings.catalog.SettingsDimens
import com.eight87.whisperboy.ui.settings.catalog.SettingsSearchBar

/**
 * Settings root surface (Phase K.1 → ported to tonearmboy's catalog
 * architecture).
 *
 * Top chrome:
 *
 *   - Back arrow + "Settings" title in the [TopAppBar].
 *   - Pill-shaped global search bar pinned just under it.
 *   - Five grouped cards (Appearance / Behaviour / Sleep timer /
 *     Library / About), each with a `primary`-coloured section header.
 *
 * Every row is one entry in `SettingsCatalog`; the per-section
 * renderer ([RenderSection]) does the wiring between catalog id ↔
 * data facet ↔ trailing widget ↔ dialog picker. Sliders, radios,
 * multi-selects and switches all live in dialog pickers — there
 * are no per-knob sub-page screens anymore for Theme / Playback /
 * Sleep timer / Library defaults. The three surfaces that DO keep
 * a sub-page (Library folders, About, Licenses) get a Navigate-shape
 * row that fires the matching `on…` callback.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    themeSettings: ThemeSettings,
    playbackSettings: PlaybackSettings,
    sleepTimerSettings: SleepTimerSettings,
    libraryUiSettings: LibraryUiSettings,
    libraryScanFilterSettings: LibraryScanFilterSettings,
    libraryRescanCoordinator: LibraryRescanCoordinator,
    onBack: () -> Unit,
    onLibraryFoldersClick: () -> Unit,
    onAboutClick: () -> Unit,
    onLicensesClick: () -> Unit,
    onOpenSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val bindings = SettingsBindings(
        themeSettings = themeSettings,
        playbackSettings = playbackSettings,
        sleepTimerSettings = sleepTimerSettings,
        libraryUiSettings = libraryUiSettings,
        libraryScanFilterSettings = libraryScanFilterSettings,
        libraryRescanCoordinator = libraryRescanCoordinator,
        onLibraryFolders = onLibraryFoldersClick,
        onAbout = onAboutClick,
        onLicenses = onLicensesClick,
        snackbarHostState = snackbarHostState,
    )

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back_cd),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .semantics { testTag = "settings_screen" },
            verticalArrangement = Arrangement.spacedBy(SettingsDimens.CardSpacing),
        ) {
            SettingsSearchBar(
                onOpen = onOpenSearch,
                modifier = Modifier.padding(
                    start = SettingsDimens.PagePadding,
                    end = SettingsDimens.PagePadding,
                    top = 12.dp,
                ),
            )
            RenderSection(Section.Appearance, bindings)
            RenderSection(Section.Behaviour, bindings)
            RenderSection(Section.SleepTimer, bindings)
            RenderSection(Section.Library, bindings)
            RenderSection(Section.About, bindings)
            // Tail breather so the last card doesn't sit flush against
            // the system nav inset.
            androidx.compose.foundation.layout.Spacer(
                modifier = Modifier.height(24.dp),
            )
        }
    }
}
