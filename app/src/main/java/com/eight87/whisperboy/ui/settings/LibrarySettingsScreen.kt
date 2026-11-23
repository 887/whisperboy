package com.eight87.whisperboy.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eight87.whisperboy.R
import com.eight87.whisperboy.data.library.FolderType
import com.eight87.whisperboy.data.library.LibraryRoot
import com.eight87.whisperboy.data.library.PersistedUriPermissionStore
import kotlinx.coroutines.launch

/**
 * Library folders sub-page.
 *
 * Lists the configured root folders + add FAB + remove button. The
 * Library defaults (sort / grid mode / scan filters) that used to
 * share this hub now live as dialog pickers on the Settings root
 * (catalog port).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySettingsScreen(
    persistedUriPermissionStore: PersistedUriPermissionStore,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val roots by persistedUriPermissionStore.observeRoots()
        .collectAsStateWithLifecycle(initialValue = emptyList<LibraryRoot>())
    val scope = rememberCoroutineScope()
    var pendingUri by remember { mutableStateOf<Uri?>(null) }

    val pickFolder = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> if (uri != null) pendingUri = uri }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_library_hub_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.library_folders_back_cd),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { pickFolder.launch(null) }) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.library_folders_fab_add_cd),
                )
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (roots.isEmpty()) {
                    item("folders-empty") {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.library_folders_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                } else {
                    items(items = roots, key = { it.treeUri.toString() }) { root ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        ) {
                            LibraryRootRow(
                                root = root,
                                onRemove = {
                                    scope.launch { persistedUriPermissionStore.removeRoot(root.treeUri) }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    val pending = pendingUri
    if (pending != null) {
        FolderTypePickerSheet(
            onDismiss = { pendingUri = null },
            onSelect = { folderType ->
                scope.launch {
                    persistedUriPermissionStore.addRoot(pending, folderType)
                    pendingUri = null
                }
            },
        )
    }
}

@Composable
private fun LibraryRootRow(
    root: LibraryRoot,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.padding(end = 8.dp).fillMaxWidth(0.7f)) {
            Text(
                text = root.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(folderTypeTitleRes(root.folderType)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.padding(start = 4.dp))
        TextButton(onClick = onRemove) {
            Text(stringResource(R.string.library_folders_remove_action))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderTypePickerSheet(
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
                            text = stringResource(folderTypeTitleRes(type)),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stringResource(folderTypeSubtitleRes(type)),
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

private fun folderTypeTitleRes(type: FolderType): Int = when (type) {
    FolderType.SingleFile -> R.string.folder_type_singlefile_title
    FolderType.SingleFolder -> R.string.folder_type_singlefolder_title
    FolderType.Root -> R.string.folder_type_root_title
    FolderType.Author -> R.string.folder_type_author_title
}

private fun folderTypeSubtitleRes(type: FolderType): Int = when (type) {
    FolderType.SingleFile -> R.string.folder_type_singlefile_subtitle
    FolderType.SingleFolder -> R.string.folder_type_singlefolder_subtitle
    FolderType.Root -> R.string.folder_type_root_subtitle
    FolderType.Author -> R.string.folder_type_author_subtitle
}
