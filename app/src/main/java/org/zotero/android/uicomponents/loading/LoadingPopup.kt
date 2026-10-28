package org.zotero.android.uicomponents.loading

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

//A full-screen scrim with a centered spinner and label, for a brief blocking operation such as
//downloading or copying a file before it can be opened. Matches the look already used elsewhere
//in the app for other blocking operations (see GeneratingBibliographyLoadingIndicator,
//GeneratingCitationLoadingIndicator) so a new one doesn't look inconsistent.
@Composable
fun LoadingPopup(message: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(color = Color.Black.copy(alpha = 0.6f)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp)
        )
        Text(
            modifier = Modifier.padding(top = 40.dp),
            text = message,
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
        )
    }
}
