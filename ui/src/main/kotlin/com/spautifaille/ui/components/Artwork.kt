package com.spautifaille.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import coil3.compose.AsyncImage
import com.spautifaille.domain.model.ArtworkUrls
import coil3.request.ImageRequest
import coil3.request.crossfade

/**
 * Pochette / avatar. Le dégradé tonal (couleurs du thème) et l'icône servent de placeholder tant que l'image
 * charge (ou si [url] est nul ou en erreur) ; l'image opaque les recouvre ensuite. [shape] vaut
 * `MaterialTheme.shapes.medium` par défaut ; passer `CircleShape` pour un avatar d'artiste.
 *
 * Avec [highResolution] (lecteur plein écran), on tente d'abord une version haute résolution carrée de l'URL
 * ([ArtworkUrls.squareCandidates], dimensionnée sur le plus petit côté de la fenêtre) puis, si son chargement
 * échoue, l'URL d'origine. Les vignettes de liste gardent l'URL d'origine.
 */
@Composable
fun Artwork(
    url: String?,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    contentDescription: String? = null,
    placeholderIcon: ImageVector = Icons.Filled.MusicNote,
    highResolution: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val placeholderBrush = remember(colors.secondaryContainer, colors.tertiaryContainer) {
        Brush.linearGradient(listOf(colors.secondaryContainer, colors.tertiaryContainer))
    }
    Box(
        modifier = modifier
            .clip(shape)
            .background(placeholderBrush),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = placeholderIcon,
            contentDescription = null,
            tint = colors.onSecondaryContainer.copy(alpha = 0.7f),
            modifier = Modifier.fillMaxSize(0.4f),
        )
        val windowInfo = LocalWindowInfo.current
        val targetPx = if (highResolution) windowInfo.containerSize.let { minOf(it.width, it.height) } else 0
        val candidates = remember(url, highResolution, targetPx) {
            if (highResolution) ArtworkUrls.squareCandidates(url, targetPx) else listOfNotNull(url?.takeIf { it.isNotBlank() })
        }
        var attempt by remember(candidates) { mutableIntStateOf(0) }
        val current = candidates.getOrNull(attempt)
        if (current != null) {
            val context = LocalContext.current
            val request = remember(current) {
                ImageRequest.Builder(context).data(current).crossfade(true).build()
            }
            AsyncImage(
                model = request,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                onError = { if (attempt < candidates.lastIndex) attempt++ },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
