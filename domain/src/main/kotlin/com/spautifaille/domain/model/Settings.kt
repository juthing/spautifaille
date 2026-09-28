package com.spautifaille.domain.model

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val audioQuality: AudioQuality = AudioQuality.BEST,
    val downloadOverWifiOnly: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    /** Taille max du cache de streaming, en Mo. */
    val streamCacheSizeMb: Int = 512,
    /** Clé Last.fm saisie par l'utilisateur (jamais embarquée dans l'APK). Réservé à une extension future. */
    val lastFmApiKey: String? = null,
    val contentCountry: String = "FR",
)
