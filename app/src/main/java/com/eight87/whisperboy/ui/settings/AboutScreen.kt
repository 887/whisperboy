package com.eight87.whisperboy.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.eight87.whisperboy.BuildConfig
import com.eight87.whisperboy.R
import com.eight87.whisperboy.ui.settings.catalog.SettingsCard
import com.eight87.whisperboy.ui.settings.catalog.SettingsDimens
import com.eight87.whisperboy.ui.settings.catalog.SettingsRow
import com.eight87.whisperboy.ui.settings.catalog.SettingsRowDivider
import kotlinx.coroutines.launch

/**
 * About surface — three M3-Expressive grouped cards (Build / Source / Credits) sitting
 * underneath an app-icon header. Layout mirrors tonearmboy's D.16.4 About so both apps
 * share a recognisable shape; whisperboy keeps its own icon at the top and its own
 * easter egg (lion image) on triple-tap of the version row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onLicensesClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val easterEgg = remember { EasterEggController() }
    var lionVisible by remember { mutableStateOf(false) }

    val versionSubtitle = stringResource(
        R.string.about_version_subtitle,
        BuildConfig.VERSION_NAME,
        BuildConfig.GIT_SHA,
    )
    val buildDateSubtitle = stringResource(R.string.about_build_date_subtitle, BuildConfig.BUILD_DATE)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
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
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(SettingsDimens.CardSpacing),
        ) {
            // App-icon header. The user explicitly likes this above the cards;
            // tonearmboy's About has been updated to mirror the shape (with the fox).
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // `mipmap.ic_launcher` resolves to the adaptive-icon XML on Android 8+,
                // which `painterResource` can't load. Use the foreground PNG directly.
                Image(
                    painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(96.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Medium,
                )
            }

            // ---- Build card ----
            SettingsCard(
                title = stringResource(R.string.about_card_build),
                modifier = Modifier.padding(horizontal = SettingsDimens.PagePadding),
            ) {
                SettingsRow(
                    id = "about.app_name",
                    icon = Icons.Outlined.Info,
                    label = stringResource(R.string.about_application_label),
                    subtitle = stringResource(R.string.about_application_subtitle),
                    onClick = null,
                )
                SettingsRowDivider()
                val easterEggFirst = stringResource(R.string.about_easter_egg_first)
                val easterEggSecond = stringResource(R.string.about_easter_egg_second)
                SettingsRow(
                    id = "about.version",
                    icon = Icons.Outlined.Numbers,
                    label = stringResource(R.string.about_version_label),
                    subtitle = versionSubtitle,
                    onClick = {
                        when (easterEgg.tap(System.currentTimeMillis())) {
                            EasterEggController.Outcome.FirstPromptSnackbar -> scope.launch {
                                snackbarHostState.showSnackbar(easterEggFirst)
                            }
                            EasterEggController.Outcome.SecondPromptSnackbar -> scope.launch {
                                snackbarHostState.showSnackbar(easterEggSecond)
                            }
                            EasterEggController.Outcome.Reveal -> {
                                lionVisible = true
                            }
                        }
                    },
                )
                SettingsRowDivider()
                SettingsRow(
                    id = "about.build_date",
                    icon = Icons.Outlined.Schedule,
                    label = stringResource(R.string.about_build_date_label),
                    subtitle = buildDateSubtitle,
                    onClick = null,
                )
            }

            // ---- Source card ----
            SettingsCard(
                title = stringResource(R.string.about_card_source),
                modifier = Modifier.padding(horizontal = SettingsDimens.PagePadding),
            ) {
                SettingsRow(
                    id = "about.github",
                    icon = Icons.AutoMirrored.Filled.Launch,
                    label = stringResource(R.string.about_github_label),
                    subtitle = stringResource(R.string.about_github_subtitle),
                    onClick = { openExternalBrowser(context, GITHUB_URL) },
                )
                SettingsRowDivider()
                SettingsRow(
                    id = "about.licenses",
                    icon = Icons.Filled.Code,
                    label = stringResource(R.string.about_oss_licenses_label),
                    subtitle = stringResource(R.string.about_oss_licenses_subtitle),
                    onClick = onLicensesClick,
                )
                SettingsRowDivider()
                SettingsRow(
                    id = "about.license",
                    icon = Icons.AutoMirrored.Filled.Article,
                    label = stringResource(R.string.about_license),
                    subtitle = stringResource(R.string.about_license_subtitle),
                    onClick = { openExternalBrowser(context, LICENSE_URL) },
                )
            }

            // ---- Credits card ----
            // Clean-room implementation. Inspiration drawn from UX screenshots of Voice
            // only; no code copied. Critical because Voice is GPLv3 and whisperboy is
            // MIT — if any Voice code were here, our license would be tainted.
            SettingsCard(
                title = stringResource(R.string.about_card_credits),
                modifier = Modifier.padding(horizontal = SettingsDimens.PagePadding),
            ) {
                SettingsRow(
                    id = "about.credits.cleanroom",
                    icon = Icons.Outlined.Info,
                    label = stringResource(R.string.about_credits_cleanroom_label),
                    subtitle = stringResource(R.string.about_credits_cleanroom_subtitle),
                    onClick = null,
                )
                SettingsRowDivider()
                SettingsRow(
                    id = "about.credits.voice",
                    icon = Icons.Filled.Favorite,
                    label = stringResource(R.string.about_credits_voice_label),
                    subtitle = stringResource(R.string.about_credits_voice_subtitle),
                    onClick = { openExternalBrowser(context, VOICE_URL) },
                )
                SettingsRowDivider()
                SettingsRow(
                    id = "about.credits.media3",
                    icon = Icons.Filled.Code,
                    label = stringResource(R.string.about_credits_media3_label),
                    subtitle = stringResource(R.string.about_credits_media3_subtitle),
                    onClick = null,
                )
            }

            Spacer(Modifier.height(SettingsDimens.CardSpacing))
        }
    }

    if (lionVisible) {
        EasterEggLionDialog(onDismiss = { lionVisible = false })
    }
}

/**
 * Fullscreen modal for the lion reveal. Tap-outside or back-press dismisses;
 * a 70% black scrim sits behind the image so the lion stands out regardless of theme.
 */
@Composable
private fun EasterEggLionDialog(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(id = R.drawable.easter_egg_lion),
                contentDescription = stringResource(R.string.about_easter_egg_lion_cd),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
            )
        }
    }
}

private const val GITHUB_URL = "https://github.com/887/whisperboy"
private const val LICENSE_URL = "https://github.com/887/whisperboy/blob/main/LICENSE"
private const val VOICE_URL = "https://github.com/PaulWoitaschek/Voice"

/**
 * Open a URL in the user's default external browser. Mirrors tonearmboy
 * AboutScreen's pattern — plain `ACTION_VIEW` with `CATEGORY_BROWSABLE`
 * + application-id extra to route through the configured browser
 * launcher rather than an in-app WebView.
 */
private fun openExternalBrowser(context: android.content.Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        putExtra("com.android.browser.application_id", context.packageName)
    }
    runCatching { context.startActivity(intent) }
}
