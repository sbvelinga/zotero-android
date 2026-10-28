package org.zotero.android.screens.settings.linkedstorage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.zotero.android.architecture.BaseViewModel2
import org.zotero.android.architecture.Defaults
import org.zotero.android.architecture.LinkedAttachmentStorageType
import org.zotero.android.architecture.ViewEffect
import org.zotero.android.architecture.ViewState
import org.zotero.android.architecture.coroutines.Dispatchers
import org.zotero.android.files.LinkedFileResolver
import org.zotero.android.microsoft.MicrosoftGraphProvider
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
internal class SettingsLinkedStorageViewModel @Inject constructor(
    private val defaults: Defaults,
    private val dispatchers: Dispatchers,
    @ApplicationContext private val context: Context,
    private val microsoftGraphProvider: MicrosoftGraphProvider,
    private val linkedFileResolver: LinkedFileResolver,
) : BaseViewModel2<SettingsLinkedStorageViewState, SettingsLinkedStorageViewEffect>(
    SettingsLinkedStorageViewState()
) {

    init {
        viewModelScope.launch {
            microsoftGraphProvider.authEvents.collect { event ->
                when (event) {
                    is MicrosoftGraphProvider.AuthEvent.Success -> {
                        refreshOneDriveSignInState()
                    }
                    is MicrosoftGraphProvider.AuthEvent.Failure -> {
                        onAuthError(event.message)
                    }
                }
            }
        }
    }

    fun init() = initOnce {
        val storageType = defaults.getLinkedAttachmentStorageType()
        updateState {
            copy(
                storageType = storageType,
                isOneDriveSignedIn = linkedFileResolver.isOneDriveSignedIn(),
                currentPath = when (storageType) {
                    LinkedAttachmentStorageType.SAF -> {
                        val baseDir = defaults.getLinkedAttachmentBaseDir()
                        baseDir?.let { uri ->
                            try {
                                val docFile = DocumentFile.fromTreeUri(context, uri)
                                docFile?.name ?: uri.lastPathSegment ?: "Selected folder"
                            } catch (e: Exception) {
                                Timber.e(e, "Failed to get folder name")
                                "Selected folder"
                            }
                        }
                    }
                    LinkedAttachmentStorageType.ONEDRIVE -> {
                        defaults.getLinkedAttachmentOneDrivePath()
                    }
                }
            )
        }
    }

    fun onBack() {
        triggerEffect(SettingsLinkedStorageViewEffect.OnBack)
    }

    fun onSelectFolder() {
        triggerEffect(SettingsLinkedStorageViewEffect.OpenFolderPicker)
    }

    fun onFolderSelected(uri: Uri) {
        //updateState must run on the thread the view model was created on, so the coroutine
        //stays on the main dispatcher and only the potentially blocking call below is moved off it.
        viewModelScope.launch {
            try {
                withContext(dispatchers.io) {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }
                defaults.setLinkedAttachmentBaseDir(uri)
                defaults.setLinkedAttachmentStorageType(LinkedAttachmentStorageType.SAF)
                updateState {
                    copy(
                        storageType = LinkedAttachmentStorageType.SAF,
                        currentPath = "Selected folder"
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to persist URI permission")
                updateState {
                    copy(errorMessage = "Failed to access folder: ${e.message}")
                }
            }
        }
    }

    fun onClearFolder() {
        when (defaults.getLinkedAttachmentStorageType()) {
            LinkedAttachmentStorageType.SAF -> {
                defaults.setLinkedAttachmentBaseDir(null)
                updateState {
                    copy(currentPath = null)
                }
            }
            LinkedAttachmentStorageType.ONEDRIVE -> {
                defaults.setLinkedAttachmentOneDrivePath(null)
                defaults.setLinkedAttachmentOneDriveFolderId(null)
                viewModelScope.launch {
                    microsoftGraphProvider.signOut()
                    updateState {
                        copy(
                            currentPath = null,
                            isOneDriveSignedIn = false,
                            isLoading = false,
                        )
                    }
                }
            }
        }
    }

    fun onStorageTypeChanged(type: LinkedAttachmentStorageType) {
        defaults.setLinkedAttachmentStorageType(type)
        updateState {
            copy(
                storageType = type,
                currentPath = when (type) {
                    LinkedAttachmentStorageType.SAF -> {
                        val baseDir = defaults.getLinkedAttachmentBaseDir()
                        baseDir?.let { "Selected folder" }
                    }
                    LinkedAttachmentStorageType.ONEDRIVE -> {
                        defaults.getLinkedAttachmentOneDrivePath()
                    }
                }
            )
        }
    }

    fun onSignInOneDrive() {
        try {
            updateState { copy(isLoading = true) }
            val intent = microsoftGraphProvider.buildAuthIntent()
            if (intent != null) {
                triggerEffect(SettingsLinkedStorageViewEffect.LaunchOneDriveAuth(intent))
            } else {
                updateState {
                    copy(
                        errorMessage = "Failed to build auth intent",
                        isLoading = false
                    )
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to sign in to OneDrive")
            updateState {
                copy(
                    errorMessage = "Failed to sign in: ${e.message}",
                    isLoading = false
                )
            }
        }
    }

    fun onOneDriveAuthResult(intent: android.content.Intent?) {
        viewModelScope.launch {
            if (intent == null) {
                updateState { copy(isLoading = false) }
                return@launch
            }

            val result = microsoftGraphProvider.handleAuthorizationResponse(intent)
            result.fold(
                onSuccess = {
                    updateState {
                        copy(
                            isOneDriveSignedIn = true,
                            isLoading = false
                        )
                    }
                },
                onFailure = { error ->
                    updateState {
                        copy(
                            errorMessage = "Failed to sign in: ${error.message}",
                            isLoading = false
                        )
                    }
                }
            )
        }
    }

    //Folder browser: lets the user pick a real OneDrive folder instead of typing a path that
    //has to match exactly. The breadcrumb's first entry is always the drive root.
    fun onBrowseOneDriveFolders() {
        updateState {
            copy(
                isFolderBrowserOpen = true,
                folderBrowserBreadcrumb = listOf(ROOT_FOLDER_ID to "OneDrive"),
                folderBrowserItems = emptyList(),
                folderBrowserError = null,
            )
        }
        loadFolderBrowserChildren(ROOT_FOLDER_ID)
    }

    private fun loadFolderBrowserChildren(folderId: String) {
        //updateState must run on the thread the view model was created on. listFolders already
        //hops to Dispatchers.IO internally for the network call, so staying on the default
        //(main) dispatcher here is both correct and sufficient.
        viewModelScope.launch {
            updateState { copy(folderBrowserLoading = true, folderBrowserError = null) }
            try {
                val folders = microsoftGraphProvider.listFolders(folderId)
                if (folders == null) {
                    updateState {
                        copy(
                            folderBrowserLoading = false,
                            folderBrowserError = "Could not load folders. Check your connection and try again."
                        )
                    }
                } else {
                    updateState { copy(folderBrowserLoading = false, folderBrowserItems = folders) }
                }
            } catch (e: Exception) {
                //An exception here would otherwise crash the app, since a coroutine launched
                //from viewModelScope has nothing else to catch it.
                Timber.e(e, "SettingsLinkedStorageViewModel: failed to load OneDrive folder $folderId")
                updateState {
                    copy(
                        folderBrowserLoading = false,
                        folderBrowserError = "Could not load folders: ${e.message}"
                    )
                }
            }
        }
    }

    fun onFolderBrowserItemTapped(item: MicrosoftGraphProvider.DriveItemResult) {
        updateState { copy(folderBrowserBreadcrumb = viewState.folderBrowserBreadcrumb + (item.id to item.name)) }
        loadFolderBrowserChildren(item.id)
    }

    fun onFolderBrowserUp() {
        val crumbs = viewState.folderBrowserBreadcrumb
        if (crumbs.size <= 1) {
            return
        }
        val newCrumbs = crumbs.dropLast(1)
        updateState { copy(folderBrowserBreadcrumb = newCrumbs) }
        loadFolderBrowserChildren(newCrumbs.last().first)
    }

    fun onFolderBrowserSelectCurrent() {
        val crumbs = viewState.folderBrowserBreadcrumb
        val current = crumbs.lastOrNull() ?: return
        val displayPath = crumbs.drop(1).joinToString("/") { it.second }.ifEmpty { "/" }
        defaults.setLinkedAttachmentOneDriveFolderId(current.first)
        defaults.setLinkedAttachmentOneDrivePath(displayPath)
        updateState {
            copy(
                currentPath = displayPath,
                isFolderBrowserOpen = false,
            )
        }
    }

    fun onFolderBrowserDismiss() {
        updateState { copy(isFolderBrowserOpen = false) }
    }

    fun refreshOneDriveSignInState() {
        updateState {
            copy(
                isOneDriveSignedIn = microsoftGraphProvider.isSignedIn(),
                isLoading = false
            )
        }
    }

    fun onAuthError(error: String) {
        updateState {
            copy(
                errorMessage = "Auth failed: $error",
                isLoading = false
            )
        }
    }

    fun clearError() {
        updateState {
            copy(errorMessage = null)
        }
    }

    companion object {
        private const val ROOT_FOLDER_ID = "root"
    }
}

internal data class SettingsLinkedStorageViewState(
    val storageType: LinkedAttachmentStorageType = LinkedAttachmentStorageType.SAF,
    val currentPath: String? = null,
    val errorMessage: String? = null,
    val isOneDriveSignedIn: Boolean = false,
    val isLoading: Boolean = false,
    val isFolderBrowserOpen: Boolean = false,
    val folderBrowserLoading: Boolean = false,
    val folderBrowserError: String? = null,
    val folderBrowserItems: List<MicrosoftGraphProvider.DriveItemResult> = emptyList(),
    //First entry is always the drive root; each further entry is a folder id paired with the
    //name shown in the breadcrumb.
    val folderBrowserBreadcrumb: List<Pair<String, String>> = emptyList(),
) : ViewState

internal sealed class SettingsLinkedStorageViewEffect : ViewEffect {
    object OnBack : SettingsLinkedStorageViewEffect()
    object OpenFolderPicker : SettingsLinkedStorageViewEffect()
    data class LaunchOneDriveAuth(val intent: android.content.Intent) : SettingsLinkedStorageViewEffect()
}
