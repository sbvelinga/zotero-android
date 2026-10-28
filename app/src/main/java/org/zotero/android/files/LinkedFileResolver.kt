package org.zotero.android.files

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.zotero.android.architecture.Defaults
import org.zotero.android.architecture.LinkedAttachmentStorageType
import org.zotero.android.microsoft.MicrosoftGraphProvider
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LinkedFileResolver @Inject constructor(
    private val context: Context,
    private val defaults: Defaults,
    private val microsoftGraphProvider: MicrosoftGraphProvider,
) {
    sealed class Result {
        data class Success(val file: File) : Result()

        //A reason worded for the user, since a silent failure looks like the tap did nothing.
        data class Failure(val reason: String) : Result()
    }

    //Zotero stores a linked file's location either relative to the linked-attachment base
    //directory, prefixed with "attachments:", or as an absolute path from the desktop machine.
    //An absolute path cannot be mapped into the chosen folder, so fall back to its file name.
    private fun pathSegments(rawPath: String): List<String> {
        var path = rawPath.trim()
        if (path.startsWith(ATTACHMENTS_PREFIX, ignoreCase = true)) {
            path = path.substring(ATTACHMENTS_PREFIX.length)
        }
        path = path.replace('\\', '/')

        val isAbsolute = path.startsWith("/") || Regex("^[A-Za-z]:/").containsMatchIn(path)
        if (isAbsolute) {
            val name = path.substringAfterLast('/')
            Timber.w("LinkedFileResolver: absolute linked path, matching on file name only: $rawPath")
            return if (name.isEmpty()) emptyList() else listOf(name)
        }

        return path.split("/").filter { it.isNotEmpty() }
    }

    //An absolute desktop path that runs through a folder literally named "OneDrive" (or
    //"OneDrive - CompanyName" for a work account) already tells us exactly where the file
    //lives in the cloud: everything after that folder is the real path from the drive root.
    //This lets resolution work regardless of which folder was picked in settings, since the
    //absolute path is a more precise source of truth than a manually chosen base folder.
    private fun oneDriveRootAnchoredSegments(rawPath: String): List<String>? {
        val path = rawPath.trim()
        if (path.startsWith(ATTACHMENTS_PREFIX, ignoreCase = true)) {
            return null
        }
        val normalized = path.replace('\\', '/')
        val isAbsolute = normalized.startsWith("/") || Regex("^[A-Za-z]:/").containsMatchIn(normalized)
        if (!isAbsolute) {
            return null
        }

        val segments = normalized.split("/").filter { it.isNotEmpty() }
        val anchorIndex = segments.indexOfFirst { it.startsWith("onedrive", ignoreCase = true) }
        if (anchorIndex == -1 || anchorIndex == segments.lastIndex) {
            return null
        }
        return segments.subList(anchorIndex + 1, segments.size)
    }

    //Returns a readable file on local storage. The app only holds a document URI for files in
    //the chosen folder, not a filesystem path, and Zotero's viewers open files by path, so the
    //document is copied into the cache first.
    suspend fun resolve(path: String): Result {
        Timber.i("LinkedFileResolver: resolving linked path: $path")
        val result = when (defaults.getLinkedAttachmentStorageType()) {
            LinkedAttachmentStorageType.SAF -> resolveViaSaf(path)
            LinkedAttachmentStorageType.ONEDRIVE -> resolveViaOneDrive(path)
        }
        if (result is Result.Failure) {
            Timber.e("LinkedFileResolver: ${result.reason}")
        }
        return result
    }

    private suspend fun resolveViaSaf(path: String): Result {
        val segments = pathSegments(path)
        if (segments.isEmpty()) {
            return Result.Failure("This linked file has an empty path: $path")
        }

        return withContext(Dispatchers.IO) {
            val baseDir = defaults.getLinkedAttachmentBaseDir()
                ?: return@withContext Result.Failure(
                    "No folder chosen for linked files. Pick one in Settings, Linked Attachment Storage."
                )

            val baseDocumentFile = DocumentFile.fromTreeUri(context, baseDir)
            if (baseDocumentFile == null || !baseDocumentFile.canRead()) {
                return@withContext Result.Failure(
                    "The linked files folder is no longer accessible. Pick it again in Settings."
                )
            }

            var current: DocumentFile = baseDocumentFile
            for (segment in segments) {
                val next = current.findFile(segment)
                    ?: current.listFiles().firstOrNull { it.name.equals(segment, ignoreCase = true) }
                if (next == null) {
                    return@withContext Result.Failure(
                        "Could not find \"$segment\" in ${current.name ?: "the chosen folder"} (linked path: $path)"
                    )
                }
                current = next
            }
            if (!current.isFile) {
                return@withContext Result.Failure("The linked path points to a folder, not a file: $path")
            }

            copyToCache(current.uri, sanitizeFileName(segments.last()))
        }
    }

    private fun copyToCache(documentUri: Uri, name: String): Result {
        return try {
            val cacheDir = File(File(context.cacheDir, SAF_CACHE_DIR), documentUri.toString().hashCode().toString())
            cacheDir.mkdirs()
            val target = File(cacheDir, name)
            val input = context.contentResolver.openInputStream(documentUri)
                ?: return Result.Failure("Could not open $name for reading.")
            input.use { stream ->
                FileOutputStream(target).use { output -> stream.copyTo(output) }
            }
            Timber.i("LinkedFileResolver: linked file cached at: ${target.absolutePath}")
            Result.Success(target)
        } catch (e: Exception) {
            Timber.e(e, "LinkedFileResolver: failed to copy $documentUri")
            Result.Failure("Could not read $name: ${e.message}")
        }
    }

    private suspend fun resolveViaOneDrive(path: String): Result {
        if (!microsoftGraphProvider.ensureValidToken()) {
            return Result.Failure("Not signed in to OneDrive. Sign in from Settings, Linked Attachment Storage.")
        }

        val anchoredSegments = oneDriveRootAnchoredSegments(path)
        val (item, displayFullPath) = if (anchoredSegments != null) {
            //The absolute path already names a real "OneDrive" folder, so it is a more precise
            //source of truth than whatever folder was picked in settings. Search from the true
            //drive root using everything after that folder, ignoring the picked folder entirely.
            val anchoredPath = anchoredSegments.joinToString("/")
            microsoftGraphProvider.searchDriveItem(anchoredPath) to anchoredPath
        } else {
            val relativePath = pathSegments(path).joinToString("/")
            if (relativePath.isEmpty()) {
                return Result.Failure("This linked file has an empty path: $path")
            }

            //A folder chosen through the browser is looked up by its stable id, which cannot be
            //mistyped. Older configurations that only have a hand-typed display path fall back
            //to searching by name from the drive root, which is the fragile path the browser
            //replaces.
            val folderId = defaults.getLinkedAttachmentOneDriveFolderId()
            if (folderId != null) {
                microsoftGraphProvider.searchDriveItem(relativePath, startItemId = folderId) to
                    "${defaults.getLinkedAttachmentOneDrivePath() ?: ""}/$relativePath"
            } else {
                val basePath = defaults.getLinkedAttachmentOneDrivePath()?.trim('/') ?: ""
                val fullPath = if (basePath.isEmpty()) relativePath else "$basePath/$relativePath"
                microsoftGraphProvider.searchDriveItem(fullPath) to fullPath
            }
        }

        return try {
            if (item == null) {
                return Result.Failure("Not found in OneDrive: $displayFullPath")
            }

            val content = microsoftGraphProvider.getFileContent(item.id)
                ?: return Result.Failure("Could not download $displayFullPath from OneDrive.")

            val file = withContext(Dispatchers.IO) {
                //One directory per drive item, so the cached file can keep its original
                //name and extension without colliding with other items.
                val itemDir = File(File(context.cacheDir, ONEDRIVE_CACHE_DIR), sanitizeFileName(item.id))
                itemDir.mkdirs()
                val fileName = item.name.takeIf { it.isNotBlank() }?.let { sanitizeFileName(it) } ?: item.id
                File(itemDir, fileName).also { target ->
                    FileOutputStream(target).use { it.write(content) }
                }
            }
            Timber.i("LinkedFileResolver: OneDrive file cached at: ${file.absolutePath}")
            Result.Success(file)
        } catch (e: Exception) {
            Timber.e(e, "LinkedFileResolver: OneDrive resolve failed for path: $displayFullPath")
            Result.Failure("OneDrive error for $displayFullPath: ${e.message}")
        }
    }

    private fun sanitizeFileName(name: String): String {
        val sanitized = name.map { if (it.isLetterOrDigit() || it in "-_. ") it else '_' }.joinToString("")
        return sanitized.trim().takeIf { it.isNotEmpty() && it != "." && it != ".." } ?: "file"
    }

    fun isConfigured(): Boolean {
        return when (defaults.getLinkedAttachmentStorageType()) {
            LinkedAttachmentStorageType.SAF -> defaults.getLinkedAttachmentBaseDir() != null
            LinkedAttachmentStorageType.ONEDRIVE -> {
                microsoftGraphProvider.isSignedIn() &&
                    (defaults.getLinkedAttachmentOneDriveFolderId() != null || defaults.getLinkedAttachmentOneDrivePath() != null)
            }
        }
    }

    fun isOneDriveSignedIn(): Boolean = microsoftGraphProvider.isSignedIn()

    companion object {
        private const val ATTACHMENTS_PREFIX = "attachments:"
        private const val SAF_CACHE_DIR = "saf_linked"
        private const val ONEDRIVE_CACHE_DIR = "onedrive_linked"
    }
}
