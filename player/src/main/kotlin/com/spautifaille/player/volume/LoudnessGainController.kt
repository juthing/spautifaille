package com.spautifaille.player.volume

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.spautifaille.domain.loudness.LoudnessNormalizer
import com.spautifaille.domain.loudness.PlaybackGain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * « Volume égal entre les titres » : applique à chaque titre le gain qui le ramène au niveau de référence.
 *
 * Le gain suit le titre COURANT du lecteur (`onMediaItemTransition`), donc il est correct pour les enchaînements
 * automatiques, les sauts, les changements de file, la reprise et le préchargement : un titre préchargé n'a
 * aucun effet tant qu'il n'est pas devenu le titre courant. ExoPlayer déclenche la transition au moment où la
 * position de lecture (horloge de l'AudioTrack) franchit la frontière, et le volume agit en aval des tampons
 * audio : le décalage se limite au délai de dispatch du thread principal.
 *
 * Source du niveau sonore ([peek], mémoire) puis [lookup] (disque, puis résolution réseau en dernier recours,
 * par exemple pour un titre téléchargé avant l'arrivée de la fonction). Tant que le niveau est inconnu le gain
 * reste neutre ; il est appliqué dès qu'il arrive, si le titre est toujours le courant. Un titre dont la
 * recherche a échoué n'est pas réessayé avant la prochaine création du contrôleur (pas de requête répétée).
 *
 * À utiliser depuis le thread principal (celui du lecteur).
 *
 * @param peek niveau déjà en mémoire d'un titre (instantané), ou `null`.
 * @param lookup niveau d'un titre, éventuellement après une attente ; `null` si introuvable. Peut lever.
 */
class LoudnessGainController(
    private val player: Player,
    private val scope: CoroutineScope,
    private val volume: PlaybackVolume,
    private val peek: (String) -> Float?,
    private val lookup: suspend (String) -> Float?,
    private val lookupTimeoutMs: Long = DEFAULT_LOOKUP_TIMEOUT_MS,
) : Player.Listener {

    /** Coupé tant que le réglage n'a pas été lu (évite une normalisation fugace si l'utilisateur l'a désactivée). */
    private var enabled = false
    private var started = false
    private var lookupJob: Job? = null
    private val failed = HashSet<String>()

    /** Écoute le lecteur et applique tout de suite le gain du titre courant. */
    fun start() {
        if (started) return
        started = true
        player.addListener(this)
        refresh()
    }

    fun release() {
        if (!started) return
        started = false
        player.removeListener(this)
        lookupJob?.cancel()
        volume.gain = PlaybackGain.Neutral
    }

    /** Active ou coupe la normalisation, à chaud. */
    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        if (started) refresh()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = refresh()

    private fun refresh() {
        lookupJob?.cancel()
        lookupJob = null
        val id = player.currentMediaItem?.mediaId?.takeIf { it.isNotEmpty() }
        if (!enabled || id == null) {
            volume.gain = PlaybackGain.Neutral
            return
        }
        val known = peek(id)
        if (known != null) {
            volume.gain = LoudnessNormalizer.gainFor(known)
            return
        }
        // Inconnu : neutre en attendant, puis gain réel dès qu'il arrive (si c'est encore le titre courant).
        volume.gain = PlaybackGain.Neutral
        if (id in failed) return
        lookupJob = scope.launch {
            val level = try {
                withTimeout(lookupTimeoutMs) { lookup(id) }
            } catch (e: TimeoutCancellationException) {
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (level == null) {
                failed += id
                return@launch
            }
            if (enabled && player.currentMediaItem?.mediaId == id) {
                volume.gain = LoudnessNormalizer.gainFor(level)
            }
        }
    }

    companion object {
        const val DEFAULT_LOOKUP_TIMEOUT_MS = 12_000L
    }
}
