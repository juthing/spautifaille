package com.spautifaille.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Formes M3 aux coins généreux et cohérents : vignettes et puces (`small`), cartes et pochettes (`medium`),
 * grandes cartes et listes (`large`), feuilles et boîtes de dialogue (`extraLarge`).
 */
val SpautifailleShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)
