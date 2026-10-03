package com.spautifaille.ui.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spautifaille.domain.model.AppSettings
import com.spautifaille.domain.model.AudioQuality
import com.spautifaille.domain.model.ColorSource
import com.spautifaille.domain.model.ThemeMode
import com.spautifaille.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class SettingsUiState(
    val isLoaded: Boolean = false,
    val settings: AppSettings = AppSettings(),
) {
    val hasLastFmKey: Boolean get() = !settings.lastFmApiKey.isNullOrBlank()
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = settingsRepository.settings
        .map { SettingsUiState(isLoaded = true, settings = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SettingsUiState())

    fun setAudioQuality(quality: AudioQuality) {
        viewModelScope.launch { settingsRepository.setAudioQuality(quality) }
    }

    fun setNormalizeVolume(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setNormalizeVolume(enabled) }
    }

    fun setDownloadOverWifiOnly(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDownloadOverWifiOnly(enabled) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    fun setColorSource(source: ColorSource) {
        viewModelScope.launch { settingsRepository.setColorSource(source) }
    }

    /** Ignore les tailles hors des choix proposés par l'UI (garde-fou contre une valeur absurde). */
    fun setStreamCacheSizeMb(sizeMb: Int) {
        if (sizeMb <= 0) return
        viewModelScope.launch { settingsRepository.setStreamCacheSizeMb(sizeMb) }
    }

    /** Une clé vide ou blanche efface la clé stockée. */
    fun setLastFmApiKey(key: String?) {
        val normalized = key?.trim()?.takeIf { it.isNotEmpty() }
        viewModelScope.launch { settingsRepository.setLastFmApiKey(normalized) }
    }

    companion object {
        /** Tailles de cache de streaming proposées, en Mo. */
        val CacheSizeOptionsMb: List<Int> = listOf(256, 512, 1024, 2048)
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
