package com.spautifaille.domain.youtube

/**
 * Occurrence d'un élément dans une liste ordonnée : la n-ième apparition (0-based) de [item]. Elle donne une
 * identité stable aux doublons (une playlist peut contenir deux fois le même titre).
 */
data class Slot<T>(val item: T, val occurrence: Int)

/** Résultat d'une réconciliation à trois voies de listes ordonnées. Toutes les listes sont dans l'ordre de [finalOrder]. */
data class OrderedReconciliation<T>(
    val finalOrder: List<Slot<T>>,
    val addLocal: List<Slot<T>>,
    val removeLocal: List<Slot<T>>,
    val addRemote: List<Slot<T>>,
    val removeRemote: List<Slot<T>>,
    /** Après application des ajouts / suppressions (ajouts en fin de liste), l'ordre local diffère de [finalOrder]. */
    val localNeedsReorder: Boolean,
    /** Idem côté distant. */
    val remoteNeedsReorder: Boolean,
)

/** Déplacement : placer [item] juste avant [before] (« avant le successeur », comme `ACTION_MOVE_VIDEO_BEFORE`). */
data class Move<E>(val item: E, val before: E)

/**
 * Réconciliation à trois voies d'une liste ordonnée (playlist liée). Kotlin pur.
 *
 * Contenu : même logique que [SetReconciler] sur les [Slot]. Ordre :
 *  - si un seul côté a réordonné les éléments communs avec `base`, on prend l'ordre de ce côté ;
 *  - sinon (aucun ou les deux), on prend l'ordre distant ;
 *  - les éléments absents de l'ordre retenu (ajoutés de l'autre côté) sont placés à la fin, dans l'ordre de l'autre côté.
 *
 * Premier lien (`base == null`) : union, ordre distant puis éléments locaux restants ; rien n'est supprimé.
 */
object OrderedListReconciler {

    fun <T> reconcile(base: List<T>?, local: List<T>, remote: List<T>): OrderedReconciliation<T> {
        val baseSlots = base?.slots()
        val localSlots = local.slots()
        val remoteSlots = remote.slots()

        val sets = SetReconciler.reconcile(baseSlots?.toSet(), localSlots.toSet(), remoteSlots.toSet())
        val finalSet = sets.finalSet

        val localReordered = baseSlots != null && reordered(baseSlots, localSlots)
        val remoteReordered = baseSlots != null && reordered(baseSlots, remoteSlots)
        val (primary, secondary) =
            if (localReordered && !remoteReordered) localSlots to remoteSlots else remoteSlots to localSlots

        val primarySet = primary.toSet()
        val finalOrder = primary.filter { it in finalSet } +
            secondary.filter { it in finalSet && it !in primarySet }

        val addLocal = finalOrder.filter { it in sets.addLocal }
        val addRemote = finalOrder.filter { it in sets.addRemote }
        val localAfter = localSlots.filter { it in finalSet } + addLocal
        val remoteAfter = remoteSlots.filter { it in finalSet } + addRemote
        return OrderedReconciliation(
            finalOrder = finalOrder,
            addLocal = addLocal,
            removeLocal = localSlots.filter { it in sets.removeLocal },
            addRemote = addRemote,
            removeRemote = remoteSlots.filter { it in sets.removeRemote },
            localNeedsReorder = localAfter != finalOrder,
            remoteNeedsReorder = remoteAfter != finalOrder,
        )
    }

    /**
     * Séquence de déplacements transformant [current] en [target] (mêmes éléments, sans doublon : utiliser des
     * [Slot]). Au plus un déplacement par position : on parcourt [target] et, chaque fois que l'élément attendu
     * n'est pas à sa place, on le place avant celui qui l'occupe. Un déplacement = un `ACTION_MOVE_VIDEO_BEFORE`.
     */
    fun <E> moves(current: List<E>, target: List<E>): List<Move<E>> {
        require(current.size == target.size && current.toSet() == target.toSet()) {
            "current et target doivent contenir les mêmes éléments"
        }
        val working = current.toMutableList()
        val moves = ArrayList<Move<E>>()
        for (index in target.indices) {
            val wanted = target[index]
            val occupant = working[index]
            if (wanted == occupant) continue
            working.remove(wanted)
            working.add(index, wanted)
            moves += Move(wanted, occupant)
        }
        return moves
    }

    private fun <T> reordered(base: List<Slot<T>>, side: List<Slot<T>>): Boolean {
        val sideSet = side.toSet()
        val baseSet = base.toSet()
        return base.filter { it in sideSet } != side.filter { it in baseSet }
    }

    private fun <T> List<T>.slots(): List<Slot<T>> {
        val counts = HashMap<T, Int>()
        return map { item ->
            val occurrence = counts.getOrDefault(item, 0)
            counts[item] = occurrence + 1
            Slot(item, occurrence)
        }
    }
}
