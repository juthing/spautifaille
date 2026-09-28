package com.spautifaille.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.spautifaille.ui.R
import com.spautifaille.ui.theme.SpautifailleTheme

/**
 * TODO(integration) : écran provisoire des routes dont l'implémentation vit dans d'autres packages
 * (library, playlist, remoteplaylist, artist, settings, downloads, import). L'intégrateur le remplace
 * dans [AppNavHost] par le vrai écran.
 */
@Composable
fun PlaceholderScreen(title: String, modifier: Modifier = Modifier) {
    Scaffold(modifier = modifier) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.placeholder_screen, title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PlaceholderScreenPreview() {
    SpautifailleTheme(dynamicColor = false) { PlaceholderScreen(title = "Bibliothèque") }
}
