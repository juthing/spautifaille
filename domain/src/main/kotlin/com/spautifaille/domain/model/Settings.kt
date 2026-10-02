package com.spautifaille.domain.model

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Origine des couleurs de l'interface. */
enum class ColorSource {
    /** Schéma statique de l'application. */
    STATIC,

    /** Couleurs du fond d'écran (Material You, Android 12+ ; repli sur [STATIC] avant). */
    DYNAMIC,

    /** Couleurs générées à partir de la pochette du titre en cours (repli sur [STATIC] / [DYNAMIC] à l'arrêt). */
    NOW_PLAYING,
}

data class AppSettings(
    val audioQuality: AudioQuality = AudioQuality.BEST,
    val downloadOverWifiOnly: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val colorSource: ColorSource = ColorSource.DYNAMIC,
    /** Taille max du cache de streaming, en Mo. */
    val streamCacheSizeMb: Int = 512,
    /** Clé Last.fm saisie par l'utilisateur (jamais embarquée dans l'APK). Réservé à une extension future. */
    val lastFmApiKey: String? = null,
    val contentCountry: String = "FR",
    /** « Volume égal entre les titres » : ramène chaque titre au même niveau perçu. */
    val normalizeVolume: Boolean = true,
)
