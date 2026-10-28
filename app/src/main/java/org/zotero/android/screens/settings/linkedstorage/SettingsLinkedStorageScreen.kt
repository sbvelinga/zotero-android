package org.zotero.android.screens.settings.linkedstorage

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import org.zotero.android.architecture.LinkedAttachmentStorageType
import org.zotero.android.architecture.ui.ObserveLifecycleEvent
import org.zotero.android.microsoft.MicrosoftGraphProvider
import org.zotero.android.screens.settings.elements.NewSettingsDivider
import org.zotero.android.screens.settings.elements.NewSettingsItem
import org.zotero.android.uicomponents.CustomScaffoldM3
import org.zotero.android.uicomponents.themem3.AppThemeM3

@Composable
internal fun SettingsLinkedStorageScreen(
    onBack: () -> Unit,
    viewModel: SettingsLinkedStorageViewModel = hiltViewModel(),
) {
    AppThemeM3 {
        val viewState by viewModel.viewStates.observeAsState(SettingsLinkedStorageViewState())
        val viewEffect by viewModel.viewEffects.observeAsState()
        val snackbarHostState = remember { SnackbarHostState() }
        val context = LocalContext.current

        val folderPickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    viewModel.onFolderSelected(uri)
                }
            }
        }

        ObserveLifecycleEvent { event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    viewModel.refreshOneDriveSignInState()
                }
                else -> {}
            }
        }

        LaunchedEffect(key1 = viewModel) {
            viewModel.init()
        }

        LaunchedEffect(key1 = viewEffect) {
            when (val consumedEffect = viewEffect?.consume()) {
                null -> Unit
                is SettingsLinkedStorageViewEffect.OnBack -> {
                    onBack()
                }
                is SettingsLinkedStorageViewEffect.OpenFolderPicker -> {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    folderPickerLauncher.launch(intent)
                }
                is SettingsLinkedStorageViewEffect.LaunchOneDriveAuth -> {
                    val authIntent = consumedEffect.intent
                    authIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(authIntent)
                    } catch (e: ActivityNotFoundException) {
                        viewModel.onAuthError(e.message ?: "No browser available to sign in")
                    }
                }
            }
        }

        LaunchedEffect(viewState.errorMessage) {
            viewState.errorMessage?.let { error ->
                snackbarHostState.showSnackbar(error)
                viewModel.clearError()
            }
        }

        CustomScaffoldM3(
            topBar = {
                SettingsLinkedStorageTopBar(
                    onBack = onBack,
                )
            },
            snackbarHostState = snackbarHostState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Linked Attachment Storage",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                NewSettingsDivider()

                Text(
                    text = "Storage Type",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.onStorageTypeChanged(LinkedAttachmentStorageType.SAF) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = viewState.storageType == LinkedAttachmentStorageType.SAF,
                        onClick = { viewModel.onStorageTypeChanged(LinkedAttachmentStorageType.SAF) }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Local folder (SAF)",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.onStorageTypeChanged(LinkedAttachmentStorageType.ONEDRIVE) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = viewState.storageType == LinkedAttachmentStorageType.ONEDRIVE,
                        onClick = { viewModel.onStorageTypeChanged(LinkedAttachmentStorageType.ONEDRIVE) }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "OneDrive",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                NewSettingsDivider()

                when (viewState.storageType) {
                    LinkedAttachmentStorageType.SAF -> {
                        NewSettingsItem(
                            title = if (viewState.currentPath != null) {
                                "Current folder: ${viewState.currentPath}"
                            } else {
                                "No folder selected"
                            },
                            onItemTapped = { },
                        )

                        NewSettingsDivider()

                        NewSettingsItem(
                            title = "Select folder",
                            onItemTapped = viewModel::onSelectFolder,
                        )

                        if (viewState.currentPath != null) {
                            NewSettingsItem(
                                title = "Clear folder selection",
                                onItemTapped = viewModel::onClearFolder,
                            )
                        }
                    }

                    LinkedAttachmentStorageType.ONEDRIVE -> {
                        if (viewState.isLoading) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.width(16.dp))
                                Text("Connecting to OneDrive...")
                            }
                        } else if (!viewState.isOneDriveSignedIn) {
                            NewSettingsItem(
                                title = "Sign in to OneDrive",
                                onItemTapped = viewModel::onSignInOneDrive,
                            )
                        } else {
                            NewSettingsItem(
                                title = if (viewState.currentPath != null) {
                                    "Current folder: ${viewState.currentPath}"
                                } else {
                                    "No folder selected"
                                },
                                onItemTapped = { },
                            )

                            NewSettingsDivider()

                            NewSettingsItem(
                                title = "Browse OneDrive folders",
                                onItemTapped = viewModel::onBrowseOneDriveFolders,
                            )

                            NewSettingsItem(
                                title = "Sign out",
                                onItemTapped = viewModel::onClearFolder,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "For SAF mode, select a folder (e.g., in OneDrive or Google Drive) that has been configured to appear in the file picker. For OneDrive mode, sign in with your Microsoft account and browse to your Zotero folder.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }

        if (viewState.isFolderBrowserOpen) {
            OneDriveFolderPickerDialog(
                breadcrumb = viewState.folderBrowserBreadcrumb,
                items = viewState.folderBrowserItems,
                isLoading = viewState.folderBrowserLoading,
                errorMessage = viewState.folderBrowserError,
                onItemTapped = viewModel::onFolderBrowserItemTapped,
                onUp = viewModel::onFolderBrowserUp,
                onSelectCurrent = viewModel::onFolderBrowserSelectCurrent,
                onDismiss = viewModel::onFolderBrowserDismiss,
            )
        }
    }
}

@Composable
private fun OneDriveFolderPickerDialog(
    breadcrumb: List<Pair<String, String>>,
    items: List<MicrosoftGraphProvider.DriveItemResult>,
    isLoading: Boolean,
    errorMessage: String?,
    onItemTapped: (MicrosoftGraphProvider.DriveItemResult) -> Unit,
    onUp: () -> Unit,
    onSelectCurrent: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (breadcrumb.size > 1) {
                        IconButton(onClick = onUp) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up")
                        }
                    }
                    Text(
                        text = breadcrumb.joinToString(" / ") { it.second },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                }

                NewSettingsDivider()

                Box(modifier = Modifier.weight(1f)) {
                    when {
                        isLoading -> {
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        }
                        errorMessage != null -> {
                            Text(
                                text = errorMessage,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(16.dp),
                            )
                        }
                        items.isEmpty() -> {
                            Text(
                                text = "No subfolders here.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(16.dp),
                            )
                        }
                        else -> {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(items, key = { it.id }) { item ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onItemTapped(item) }
                                            .padding(horizontal = 16.dp, vertical = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            Icons.Filled.Folder,
                                            contentDescription = null,
                                            modifier = Modifier.padding(end = 12.dp),
                                        )
                                        Text(text = item.name, style = MaterialTheme.typography.bodyMedium)
                                    }
                                    NewSettingsDivider()
                                }
                            }
                        }
                    }
                }

                NewSettingsDivider()

                NewSettingsItem(
                    title = "Use this folder",
                    onItemTapped = onSelectCurrent,
                )
            }
        }
    }
}
