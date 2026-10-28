package org.zotero.android.screens.oauth

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.zotero.android.architecture.BaseActivity
import org.zotero.android.microsoft.MicrosoftGraphProvider
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class OAuthCallbackActivity : BaseActivity() {

    @Inject
    lateinit var microsoftGraphProvider: MicrosoftGraphProvider

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Timber.d("OAuthCallbackActivity: onCreate with intent=$intent")

        val uri = intent?.data
        Timber.d("OAuthCallbackActivity: uri=$uri")

        if (uri != null) {
            handleCallback()
        } else {
            Timber.e("OAuthCallbackActivity: No data in intent")
            finish()
        }
    }

    private fun handleCallback() {
        Timber.d("OAuthCallbackActivity: handleCallback starting")
        lifecycleScope.launch {
            try {
                Timber.d("OAuthCallbackActivity: calling handleAuthorizationResponse")
                val result = microsoftGraphProvider.handleAuthorizationResponse(intent!!)
                result.fold(
                    onSuccess = {
                        Timber.d("OAuthCallbackActivity: Auth successful")
                    },
                    onFailure = { error ->
                        Timber.e(error, "OAuthCallbackActivity: Auth failed")
                    }
                )
            } catch (e: Exception) {
                Timber.e(e, "OAuthCallbackActivity: Exception during handleCallback")
            } finally {
                returnToApp()
                finish()
            }
        }
    }

    //The custom tab that handled the sign-in is still in front of us once the redirect lands.
    //Re-launching through the launcher intent resumes the existing task if there is one, and
    //otherwise cold-starts the app through RootActivity, which owns the logged-in/out decision.
    private fun returnToApp() {
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent == null) {
                Timber.e("OAuthCallbackActivity: no launch intent for $packageName")
                return
            }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
        } catch (e: Exception) {
            Timber.e(e, "OAuthCallbackActivity: failed to return to the app")
        }
    }
}
