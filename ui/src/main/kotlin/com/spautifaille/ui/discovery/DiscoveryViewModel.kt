package com.spautifaille.ui.discovery

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.model.Track
import com.spautifaille.domain.player.PlaybackController
import com.spautifaille.domain.recommendation.Discovery
import com.spautifaille.domain.recommendation.DiscoveryRepository
import com.spautifaille.ui.common.UiMessenger
import com.spautifaille.ui.common.UiText
import com.spautifaille.ui.common.toAppError
import com.spautifaille.ui.common.toMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class DiscoveryStatus { LOADING, EMPTY, CONTENT, ERROR }

@Immutable
data class DiscoveryUiState(
    val tracks: List<Track> = emptyList(),
    val generatedAt: Long? = null,
    /** Vrai tant que le cache local n'a pas encore été lu. */
    val isLoading: Boolean = true,
    /** Recalcul réseau en cours (automatique ou demandé). */
    val isRefreshing: Boolean = false,
    /** Dernière erreur de rafraîchissement, conservée seulement quand il n'y a rien à afficher. */
    val error: AppError? = null,
) {
    val status: DiscoveryStatus
        get() = when {
            tracks.isNotEmpty() -> DiscoveryStatus.CONTENT
            isLoading || isRefreshing -> DiscoveryStatus.LOADING
            error != null -> DiscoveryStatus.ERROR
            else -> DiscoveryStatus.EMPTY
        }
}

/**
 * Découverte : observe le cache local, déclenche la planification périodique (idempotente) et un
 * rafraîchissement à l'ouverture si le cache est absent ou périmé (> 12 h). Partagé par la section de l'accueil
 * et par l'écran complet (chacun a sa propre instance).
 */
@HiltViewModel
class DiscoveryViewModel internal constructor(
    private val repository: DiscoveryRepository,
    private val playbackController: PlaybackController,
    private val messenger: UiMessenger,
    private val clock: () -> Long,
) : ViewModel() {

    @Inject
    constructor(
        repository: DiscoveryRepository,
        playbackController: PlaybackController,
        messenger: UiMessenger,
    ) : this(repository, playbackController, messenger, System::currentTimeMillis)

    private val refreshing = MutableStateFlow(false)
    private val error = MutableStateFlow<AppError?>(null)

    /** Dernier contenu du cache ; `null` tant que le cache n'a pas été lu. */
    private val cache = MutableStateFlow<Loaded?>(null)

    val uiState: StateFlow<DiscoveryUiState> = combine(cache, refreshing, error) { loaded, isRefreshing, err ->
        DiscoveryUiState(
            tracks = loaded?.discovery?.tracks.orEmpty(),
            generatedAt = loaded?.discovery?.generatedAt,
            isLoading = loaded == null,
            isRefreshing = isRefreshing,
            error = err,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DiscoveryUiState())

    init {
        repository.scheduleRefresh()
        viewModelScope.launch {
            var first = true
            repository.observe().collect { discovery ->
                cache.value = Loaded(discovery)
                if (first) {
                    first = false
                    if (discovery == null || discovery.isStale(clock())) refresh()
                }
            }
        }
    }

    /** Recalcule la découverte. Sans effet si un calcul est déjà en cours. */
    fun refresh() {
        if (!refreshing.compareAndSet(expect = false, update = true)) return
        error.value = null
        viewModelScope.launch {
            try {
                repository.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val appError = e.toAppError()
                if (currentTracks().isEmpty()) {
                    error.value = appError
                } else {
                    // Du contenu reste affichable : simple message, on garde la liste.
                    messenger.show(UiText.of(appError.toMessage()))
                }
            } finally {
                refreshing.update { false }
            }
        }
    }

    /** Lit toute la découverte, dans l'ordre ou mélangée. */
    fun playAll(shuffle: Boolean) {
        val tracks = currentTracks()
        if (tracks.isEmpty()) return
        playbackController.play(tracks, 0, shuffle)
    }

    /** Lit la découverte à partir du titre d'index [index]. */
    fun playFrom(index: Int) {
        val tracks = currentTracks()
        if (tracks.isEmpty()) return
        playbackController.play(tracks, index.coerceIn(tracks.indices), false)
    }

    private fun currentTracks(): List<Track> = cache.value?.discovery?.tracks.orEmpty()

    private data class Loaded(val discovery: Discovery?)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
