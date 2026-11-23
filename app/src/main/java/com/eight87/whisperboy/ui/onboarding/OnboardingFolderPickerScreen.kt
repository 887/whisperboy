package com.eight87.whisperboy.ui.onboarding

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eight87.whisperboy.R
import com.eight87.whisperboy.data.library.FolderType
import com.eight87.whisperboy.data.library.PersistedUriPermissionStore
import com.eight87.whisperboy.data.onboarding.OnboardingSettings
import kotlinx.coroutines.launch

/**
 * Phase L.3 — folder picker explainer.
 *
 * Walks the user through the four `FolderType` modes (one row each, icon +
 * title + subtitle) so they know what they're picking *before* the SAF
 * tree picker drops them into a foreign system surface. A single CTA at
 * the bottom launches `OpenDocumentTree`; the result feeds the
 * [FolderTypeSheet] bottom sheet which writes the root + type to
 * [PersistedUriPermissionStore].
 *
 * **Issue-1 (onboarding loop) fix:** the moment the user confirms a root +
 * [FolderType], we flip [OnboardingSettings.setCompleted] to `true` and call
 * [onFinish] — onboarding is "configured", not "scanned to completion". The
 * library screen renders its empty/skeleton state while the background scan
 * runs (Voice / tonearmboy pattern). The retired
 * `OnboardingFirstScanScreen` previously blocked onboarding on scan
 * settling; if the user closed the app mid-scan, the flag never persisted
 * and onboarding looped on every cold start.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingFolderPickerScreen(
    persistedUriPermissionStore: PersistedUriPermissionStore,
    onboardingSettings: OnboardingSettings,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var pendingUri by remember { mutableStateOf<Uri?>(null) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> if (uri != null) pendingUri = uri }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 80.dp),
        ) {
            Text(
                text = stringResource(R.string.onboarding_folder_picker_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.onboarding_folder_picker_intro),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            FolderType.allOrdered.forEach { type ->
                FolderTypeExplainerRow(type)
                Spacer(Modifier.height(12.dp))
            }
        }
        Button(
            onClick = { launcher.launch(null) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        ) {
            Text(stringResource(R.string.onboarding_folder_picker_cta))
        }
    }

    val pending = pendingUri
    if (pending != null) {
        FolderTypeSheet(
            onDismiss = { pendingUri = null },
            onSelect = { folderType ->
                scope.launch {
                    persistedUriPermissionStore.addRoot(pending, folderType)
                    // Issue-1 fix: persist the onboarding-completed flag the moment the
                    // user has configured a root + FolderType. addRoot() suspends to
                    // DataStore so the flag flip runs after the root write has landed;
                    // a process death between addRoot and setCompleted simply re-enters
                    // onboarding on the next launch, which is recoverable.
                    onboardingSettings.setCompleted(true)
                    pendingUri = null
                    onFinish()
                }
            },
        )
    }
}

@Composable
private fun FolderTypeExplainerRow(type: FolderType, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = folderTypeIcon(type),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.size(16.dp))
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.Top) {
            Text(
                text = stringResource(folderTypeTitle(type)),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(folderTypeSubtitle(type)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderTypeSheet(
    onDismiss: () -> Unit,
    onSelect: (FolderType) -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = stringResource(R.string.folder_type_dialog_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(12.dp))
            FolderType.allOrdered.forEach { type ->
                TextButton(
                    onClick = { onSelect(type) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = stringResource(folderTypeTitle(type)),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stringResource(folderTypeSubtitle(type)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.dialog_cancel))
            }
        }
    }
}

private fun folderTypeIcon(type: FolderType): ImageVector = when (type) {
    FolderType.SingleFile -> Icons.Outlined.AudioFile
    FolderType.SingleFolder -> Icons.Outlined.Folder
    FolderType.Root -> Icons.Outlined.FolderOpen
    FolderType.Author -> Icons.Outlined.Person
}

private fun folderTypeTitle(type: FolderType): Int = when (type) {
    FolderType.SingleFile -> R.string.folder_type_singlefile_title
    FolderType.SingleFolder -> R.string.folder_type_singlefolder_title
    FolderType.Root -> R.string.folder_type_root_title
    FolderType.Author -> R.string.folder_type_author_title
}

private fun folderTypeSubtitle(type: FolderType): Int = when (type) {
    FolderType.SingleFile -> R.string.folder_type_singlefile_subtitle
    FolderType.SingleFolder -> R.string.folder_type_singlefolder_subtitle
    FolderType.Root -> R.string.folder_type_root_subtitle
    FolderType.Author -> R.string.folder_type_author_subtitle
}
