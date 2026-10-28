package org.zotero.android.microsoft

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.zotero.android.architecture.Defaults
import timber.log.Timber
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MicrosoftGraphProvider @Inject constructor(
    private val context: Context,
    private val defaults: Defaults,
) {

    sealed class AuthEvent {
        object Success : AuthEvent()
        data class Failure(val message: String) : AuthEvent()
    }

    private val _authEvents = MutableSharedFlow<AuthEvent>(replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val authEvents: SharedFlow<AuthEvent> = _authEvents.asSharedFlow()

    private val httpClient = OkHttpClient()

    companion object {
        private const val CLIENT_ID = "af48ac3c-d013-4f0d-a7e8-e49c7d6946cb"
        private const val TENANT_ID = "common"
        private val SCOPES = listOf("Files.Read", "Files.Read.All", "offline_access")
        private const val REDIRECT_URI = "org.zotero.android://oauth2redirect"
        private const val AUTH_URL = "https://login.microsoftonline.com/$TENANT_ID/oauth2/v2.0/authorize"
        private const val TOKEN_URL = "https://login.microsoftonline.com/$TENANT_ID/oauth2/v2.0/token"

        //Treat a token as expired if it is within this many milliseconds of its real expiration.
        private const val TOKEN_EXPIRY_SKEW_MS = 5 * 60 * 1000L
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        return this.optString(key).takeIf { it.isNotBlank() }
    }

    private fun randomUrlSafeToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun codeChallengeFor(codeVerifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun clearPkceState() {
        defaults.setOneDrivePkceVerifier(null)
        defaults.setOneDriveOAuthState(null)
    }

    private fun storeTokens(accessToken: String, refreshToken: String?, expiresInSeconds: Int) {
        defaults.setOneDriveAccessToken(accessToken)
        if (refreshToken != null) {
            defaults.setOneDriveRefreshToken(refreshToken)
        }
        val expirationTime = System.currentTimeMillis() + (expiresInSeconds * 1000L)
        defaults.setOneDriveTokenExpirationTime(expirationTime)
    }

    fun buildAuthIntent(): Intent? {
        return try {
            val codeVerifier = randomUrlSafeToken()
            val state = randomUrlSafeToken()
            defaults.setOneDrivePkceVerifier(codeVerifier)
            defaults.setOneDriveOAuthState(state)

            val params = mapOf(
                "client_id" to CLIENT_ID,
                "response_type" to "code",
                "redirect_uri" to REDIRECT_URI,
                "scope" to SCOPES.joinToString(" "),
                "response_mode" to "query",
                "code_challenge" to codeChallengeFor(codeVerifier),
                "code_challenge_method" to "S256",
                "state" to state,
            )

            val queryString = params.entries.joinToString("&") { "${it.key}=${Uri.encode(it.value)}" }
            val authUrl = "$AUTH_URL?$queryString"

            val customTabsIntent = CustomTabsIntent.Builder()
                .build()
            customTabsIntent.intent.apply {
                data = Uri.parse(authUrl)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            customTabsIntent.intent
        } catch (e: Exception) {
            Timber.e(e, "MicrosoftGraphProvider: Failed to build auth intent")
            null
        }
    }

    suspend fun handleAuthorizationResponse(intent: Intent): Result<Unit> {
        val result: Result<Unit> = try {
            val uri = intent.data
            if (uri == null) {
                Result.failure(Exception("No data in intent"))
            } else {
                val code = uri.getQueryParameter("code")
                val error = uri.getQueryParameter("error")
                val errorDescription = uri.getQueryParameter("error_description")
                val returnedState = uri.getQueryParameter("state")

                when {
                    error != null -> Result.failure(Exception("Auth error: $error - $errorDescription"))
                    code == null -> Result.failure(Exception("No authorization code"))
                    else -> {
                        val expectedState = defaults.getOneDriveOAuthState()
                        val codeVerifier = defaults.getOneDrivePkceVerifier()
                        when {
                            expectedState.isNullOrBlank() || returnedState != expectedState ->
                                Result.failure(Exception("OAuth state mismatch"))

                            codeVerifier.isNullOrBlank() ->
                                Result.failure(Exception("Missing PKCE code verifier"))

                            else -> exchangeCodeForTokens(code, codeVerifier)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "MicrosoftGraphProvider: Token exchange failed")
            Result.failure(e)
        }

        clearPkceState()
        result.fold(
            onSuccess = {
                _authEvents.tryEmit(AuthEvent.Success)
            },
            onFailure = { e ->
                _authEvents.tryEmit(AuthEvent.Failure(e.message ?: "Unknown error"))
            }
        )
        return result
    }

    private suspend fun exchangeCodeForTokens(code: String, codeVerifier: String): Result<Unit> {
        val mediaType = "application/x-www-form-urlencoded".toMediaType()
        val bodyString = listOf(
            "client_id" to CLIENT_ID,
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to REDIRECT_URI,
            "code_verifier" to codeVerifier,
            "scope" to SCOPES.joinToString(" "),
        ).joinToString("&") { (key, value) -> "$key=${Uri.encode(value)}" }
        val body = bodyString.toRequestBody(mediaType)

        val request = Request.Builder()
            .url(TOKEN_URL)
            .post(body)
            .build()

        return withContext(Dispatchers.IO) {
            try {
                httpClient.newCall(request).execute().use { response ->
                    val json = JSONObject(response.body?.string() ?: "{}")

                    if (response.isSuccessful) {
                        val newAccessToken = json.optStringOrNull("access_token")
                        if (newAccessToken == null) {
                            Result.failure(Exception("Token exchange succeeded but access_token was missing"))
                        } else {
                            val newRefreshToken = json.optStringOrNull("refresh_token")
                            val expiresIn = json.optInt("expires_in", 3600)
                            storeTokens(newAccessToken, newRefreshToken, expiresIn)
                            Timber.d("MicrosoftGraphProvider: Token exchange successful")
                            Result.success(Unit)
                        }
                    } else {
                        Result.failure(Exception("Token exchange failed: ${json.optStringOrNull("error")} - ${json.optStringOrNull("error_description")}"))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun signOut(): Result<Unit> {
        return try {
            defaults.setOneDriveAccessToken(null)
            defaults.setOneDriveRefreshToken(null)
            defaults.setOneDriveTokenExpirationTime(0L)
            Timber.d("MicrosoftGraphProvider: Signed out")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "MicrosoftGraphProvider: Sign out failed")
            Result.failure(e)
        }
    }

    fun isSignedIn(): Boolean {
        val refreshToken = defaults.getOneDriveRefreshToken()
        if (!refreshToken.isNullOrBlank()) {
            return true
        }
        val accessToken = defaults.getOneDriveAccessToken()
        if (accessToken.isNullOrBlank()) {
            return false
        }
        return System.currentTimeMillis() < (defaults.getOneDriveTokenExpirationTime() - TOKEN_EXPIRY_SKEW_MS)
    }

    suspend fun ensureValidToken(): Boolean {
        val accessToken = defaults.getOneDriveAccessToken()
        val isAccessTokenValid = !accessToken.isNullOrBlank() &&
            System.currentTimeMillis() < (defaults.getOneDriveTokenExpirationTime() - TOKEN_EXPIRY_SKEW_MS)
        if (isAccessTokenValid) {
            return true
        }

        val refreshToken = defaults.getOneDriveRefreshToken()
        if (refreshToken.isNullOrBlank()) {
            return false
        }

        return try {
            val result = refreshAccessToken(refreshToken)
            result.isSuccess
        } catch (e: Exception) {
            Timber.e(e, "MicrosoftGraphProvider: Token refresh failed")
            false
        }
    }

    private suspend fun refreshAccessToken(refreshToken: String): Result<Unit> {
        val mediaType = "application/x-www-form-urlencoded".toMediaType()
        val bodyString = listOf(
            "client_id" to CLIENT_ID,
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
            "scope" to SCOPES.joinToString(" "),
        ).joinToString("&") { (key, value) -> "$key=${Uri.encode(value)}" }
        val body = bodyString.toRequestBody(mediaType)

        val request = Request.Builder()
            .url(TOKEN_URL)
            .post(body)
            .build()

        return withContext(Dispatchers.IO) {
            try {
                httpClient.newCall(request).execute().use { response ->
                    val json = JSONObject(response.body?.string() ?: "{}")

                    if (response.isSuccessful) {
                        val newAccessToken = json.optStringOrNull("access_token")
                        if (newAccessToken == null) {
                            Result.failure(Exception("Token refresh succeeded but access_token was missing"))
                        } else {
                            val newRefreshToken = json.optStringOrNull("refresh_token") ?: refreshToken
                            val expiresIn = json.optInt("expires_in", 3600)
                            storeTokens(newAccessToken, newRefreshToken, expiresIn)
                            Result.success(Unit)
                        }
                    } else {
                        Result.failure(Exception("Token refresh failed: ${json.optStringOrNull("error")}"))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    //startItemId lets a caller resolve a path relative to a folder it already knows the id of,
    //rather than always walking from the drive root by name.
    suspend fun searchDriveItem(relativePath: String, startItemId: String = "root"): DriveItemResult? {
        if (!ensureValidToken()) return null
        val token = defaults.getOneDriveAccessToken()
        if (token.isNullOrBlank()) return null

        return try {
            val pathSegments = relativePath.split("/").filter { it.isNotEmpty() }
            var currentItemId = startItemId
            //Report the name OneDrive actually stores, not the segment we searched for, since
            //matching is case-insensitive and callers use this name for the cached file.
            var currentItem: DriveItemResult? = null

            for (segment in pathSegments) {
                val children = getChildren(currentItemId, token) ?: return null
                val found = children.find { it.name.equals(segment, ignoreCase = true) } ?: return null
                currentItemId = found.id
                currentItem = found
            }

            currentItem ?: DriveItemResult(id = startItemId, name = "", isFolder = true)
        } catch (e: Exception) {
            Timber.e(e, "MicrosoftGraphProvider: Failed to search item")
            null
        }
    }

    //Used by the folder browser in settings, so the user picks a real OneDrive folder instead
    //of typing a path that has to match exactly.
    suspend fun listFolders(itemId: String): List<DriveItemResult>? {
        if (!ensureValidToken()) return null
        val token = defaults.getOneDriveAccessToken()
        if (token.isNullOrBlank()) return null

        return try {
            getChildren(itemId, token)
                ?.filter { it.isFolder }
                ?.sortedBy { it.name.lowercase() }
        } catch (e: Exception) {
            Timber.e(e, "MicrosoftGraphProvider: Failed to list folders")
            null
        }
    }

    private suspend fun getChildren(itemId: String, token: String): List<DriveItemResult>? {
        var url: String? = if (itemId == "root") {
            "https://graph.microsoft.com/v1.0/me/drive/root/children"
        } else {
            "https://graph.microsoft.com/v1.0/me/drive/items/$itemId/children"
        }

        val result = mutableListOf<DriveItemResult>()

        return withContext(Dispatchers.IO) {
            try {
                while (url != null) {
                    val request = Request.Builder()
                        .url(url!!)
                        .addHeader("Authorization", "Bearer $token")
                        .get()
                        .build()

                    val nextLink = httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            Timber.e("MicrosoftGraphProvider: Failed to get children, code=${response.code}")
                            return@withContext null
                        }
                        val body = response.body?.string()
                        if (body == null) {
                            return@withContext null
                        }
                        val json = JSONObject(body)
                        val items = json.optJSONArray("value")
                        if (items != null) {
                            for (i in 0 until items.length()) {
                                val item = items.getJSONObject(i)
                                result.add(
                                    DriveItemResult(
                                        id = item.optString("id", ""),
                                        name = item.optString("name", ""),
                                        isFolder = item.has("folder"),
                                    )
                                )
                            }
                        }
                        json.optStringOrNull("@odata.nextLink")
                    }
                    url = nextLink
                }
                result
            } catch (e: Exception) {
                Timber.e(e, "MicrosoftGraphProvider: Failed to get children")
                null
            }
        }
    }

    suspend fun getFileContent(itemId: String): ByteArray? {
        if (!ensureValidToken()) return null
        val token = defaults.getOneDriveAccessToken()
        if (token.isNullOrBlank()) return null

        return withContext(Dispatchers.IO) {
            try {
                val url = "https://graph.microsoft.com/v1.0/me/drive/items/$itemId/content"
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $token")
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Timber.e("MicrosoftGraphProvider: Failed to get file content, code=${response.code}")
                        null
                    } else {
                        response.body?.bytes()
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "MicrosoftGraphProvider: Failed to get file content")
                null
            }
        }
    }

    data class DriveItemResult(
        val id: String,
        val name: String,
        val isFolder: Boolean = false,
    )
}
