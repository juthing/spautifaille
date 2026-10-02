package com.spautifaille.domain.youtube

/**
 * Résultat d'une réconciliation à trois voies d'ensembles. Les quatre ensembles `add*` / `remove*` sont les
 * opérations à appliquer de chaque côté pour que local et distant convergent vers [finalSet].
 */
data class SetReconciliation<T>(
    val finalSet: Set<T>,
    val addLocal: Set<T>,
    val removeLocal: Set<T>,
    val addRemote: Set<T>,
    val removeRemote: Set<T>,
    /**
     * Nouvel ensemble « vu côté distant » à persister : éléments observés distants au moins une fois et toujours
     * présents. Voir le paramètre `seenRemote` de [SetReconciler.reconcile].
     */
    val seenAfter: Set<T>,
)

/**
 * Réconciliation à trois voies (local, distant, instantané du dernier état synchronisé) pour des ensembles :
 * likes (identifiants de vidéo) et abonnements (identifiants de chaîne). Kotlin pur, sans effet de bord.
 *
 * Règles, avec `base` l'instantané du dernier état commun :
 *  - ajouté d'un côté (absent de `base`, présent d'un seul côté) : propagé à l'autre côté ;
 *  - supprimé d'un côté (présent dans `base`, absent d'un côté) : supprimé de l'autre côté aussi ;
 *  - ajouté des deux côtés, ou supprimé des deux côtés : rien à faire ;
 *  - ajout et suppression ne peuvent pas entrer en conflit sur un même élément (ajout = absent de `base`,
 *    suppression = présent dans `base`).
 *
 * Premier lien (`base == null`) : union des deux ensembles, **rien n'est supprimé**.
 */
object SetReconciler {

    /**
     * @param base dernier état synchronisé, `null` au premier lien.
     * @param seenRemote éléments que la vue distante a déjà montrés au moins une fois. Une absence côté distant
     *   n'est interprétée comme une suppression que pour ces éléments ; les autres sont « invisibles » (ex. like
     *   d'une vidéo non musicale quand seule « Musique likée » est lue, chaîne non musicale absente de la
     *   bibliothèque YouTube Music) : ils sont conservés localement et poussés une seule fois. `null` = tout
     *   `base` a été vu.
     */
    fun <T> reconcile(
        base: Set<T>?,
        local: Set<T>,
        remote: Set<T>,
        seenRemote: Set<T>? = null,
    ): SetReconciliation<T> {
        if (base == null) {
            return SetReconciliation(
                finalSet = local + remote,
                addLocal = remote - local,
                removeLocal = emptySet(),
                addRemote = local - remote,
                removeRemote = emptySet(),
                seenAfter = remote,
            )
        }
        val seen = seenRemote ?: base
        val addedLocal = local - base
        val removedLocal = base - local
        val addedRemote = remote - base
        val removedRemote = (base - remote).filterTo(HashSet()) { it in seen }
        val invisible = base.filterTo(HashSet()) { it !in seen }

        val finalSet = (base + addedLocal + addedRemote) - removedLocal - removedRemote
        return SetReconciliation(
            finalSet = finalSet,
            addLocal = finalSet - local,
            removeLocal = local - finalSet,
            addRemote = (finalSet - remote) - invisible,
            removeRemote = remote - finalSet,
            seenAfter = (seen + remote).filterTo(HashSet()) { it in finalSet },
        )
    }
}
