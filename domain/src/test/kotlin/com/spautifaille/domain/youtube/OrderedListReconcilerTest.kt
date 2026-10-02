package com.spautifaille.domain.youtube

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderedListReconcilerTest {

    private fun items(slots: List<Slot<String>>) = slots.map { it.item }

    @Test
    fun `premier lien - ordre distant puis elements locaux restants, rien n est supprime`() {
        val r = OrderedListReconciler.reconcile(base = null, local = listOf("c", "a"), remote = listOf("a", "b"))
        assertEquals(listOf("a", "b", "c"), items(r.finalOrder))
        assertEquals(listOf("b"), items(r.addLocal))
        assertEquals(listOf("c"), items(r.addRemote))
        assertTrue(r.removeLocal.isEmpty() && r.removeRemote.isEmpty())
        // Le local doit etre reordonne (a, c, b -> a, b, c) ; le distant (a, b, c) est deja bon.
        assertTrue(r.localNeedsReorder)
        assertFalse(r.remoteNeedsReorder)
    }

    @Test
    fun `ajouts et suppressions croises`() {
        // local : -b, +x ; distant : -a, +y.
        val r = OrderedListReconciler.reconcile(
            base = listOf("a", "b", "c"),
            local = listOf("a", "c", "x"),
            remote = listOf("b", "c", "y"),
        )
        assertEquals(setOf("c", "x", "y"), items(r.finalOrder).toSet())
        assertEquals(listOf("y"), items(r.addLocal))
        assertEquals(listOf("x"), items(r.addRemote))
        assertEquals(listOf("a"), items(r.removeLocal))
        assertEquals(listOf("b"), items(r.removeRemote))
    }

    @Test
    fun `reordonnancement local seul est pousse vers le distant`() {
        val r = OrderedListReconciler.reconcile(
            base = listOf("a", "b", "c"),
            local = listOf("c", "a", "b"),
            remote = listOf("a", "b", "c"),
        )
        assertEquals(listOf("c", "a", "b"), items(r.finalOrder))
        assertTrue(r.remoteNeedsReorder)
        assertFalse(r.localNeedsReorder)
    }

    @Test
    fun `reordonnancement distant seul est applique en local`() {
        val r = OrderedListReconciler.reconcile(
            base = listOf("a", "b", "c"),
            local = listOf("a", "b", "c"),
            remote = listOf("b", "c", "a"),
        )
        assertEquals(listOf("b", "c", "a"), items(r.finalOrder))
        assertTrue(r.localNeedsReorder)
        assertFalse(r.remoteNeedsReorder)
    }

    @Test
    fun `reordonnancement des deux cotes - le distant gagne`() {
        val r = OrderedListReconciler.reconcile(
            base = listOf("a", "b", "c"),
            local = listOf("c", "b", "a"),
            remote = listOf("b", "a", "c"),
        )
        assertEquals(listOf("b", "a", "c"), items(r.finalOrder))
        assertTrue(r.localNeedsReorder)
        assertFalse(r.remoteNeedsReorder)
    }

    @Test
    fun `ajout local pendant un reordonnancement distant`() {
        val r = OrderedListReconciler.reconcile(
            base = listOf("a", "b"),
            local = listOf("a", "b", "n"),
            remote = listOf("b", "a"),
        )
        assertEquals(listOf("b", "a", "n"), items(r.finalOrder))
        assertEquals(listOf("n"), items(r.addRemote))
        assertTrue(r.localNeedsReorder)
        assertFalse("n est ajouté en fin côté distant : l'ordre est déjà bon", r.remoteNeedsReorder)
    }

    @Test
    fun `suppression distante pendant un reordonnancement local`() {
        val r = OrderedListReconciler.reconcile(
            base = listOf("a", "b", "c"),
            local = listOf("c", "b", "a"),
            remote = listOf("a", "c"),
        )
        // Seul le local a réordonné les éléments communs : l'ordre local est retenu, sans "b".
        assertEquals(listOf("c", "a"), items(r.finalOrder))
        assertEquals(listOf("b"), items(r.removeLocal))
        assertTrue(r.remoteNeedsReorder)
    }

    @Test
    fun `aucun changement ne produit aucune operation`() {
        val l = listOf("a", "b", "c")
        val r = OrderedListReconciler.reconcile(l, l, l)
        assertEquals(l, items(r.finalOrder))
        assertTrue(r.addLocal.isEmpty() && r.removeLocal.isEmpty() && r.addRemote.isEmpty() && r.removeRemote.isEmpty())
        assertFalse(r.localNeedsReorder)
        assertFalse(r.remoteNeedsReorder)
    }

    @Test
    fun `les doublons sont traites par occurrence`() {
        // Le titre « a » est présent deux fois en local, une seule fois à distance.
        val r = OrderedListReconciler.reconcile(
            base = listOf("a"),
            local = listOf("a", "a"),
            remote = listOf("a"),
        )
        assertEquals(listOf("a", "a"), items(r.finalOrder))
        assertEquals(listOf(Slot("a", 1)), r.addRemote)
        assertTrue(r.addLocal.isEmpty())
    }

    @Test
    fun `suppression d un doublon cote distant retire la derniere occurrence locale`() {
        val r = OrderedListReconciler.reconcile(
            base = listOf("a", "a"),
            local = listOf("a", "a"),
            remote = listOf("a"),
        )
        assertEquals(listOf(Slot("a", 1)), r.removeLocal)
        assertEquals(listOf("a"), items(r.finalOrder))
    }

    @Test
    fun `moves transforme current en target`() {
        val current = listOf("a", "b", "c", "d")
        val target = listOf("d", "a", "c", "b")
        assertEquals(target, apply(current, OrderedListReconciler.moves(current, target)))
    }

    @Test
    fun `moves est vide si les listes sont identiques`() {
        assertTrue(OrderedListReconciler.moves(listOf("a", "b"), listOf("a", "b")).isEmpty())
    }

    @Test
    fun `moves - proprietes sur permutations aleatoires`() {
        val random = Random(42)
        repeat(200) {
            val size = random.nextInt(1, 12)
            val current = (0 until size).map { "i$it" }
            val target = current.shuffled(random)
            val moves = OrderedListReconciler.moves(current, target)
            assertEquals(target, apply(current, moves))
            assertTrue("au plus un déplacement par position", moves.size <= size)
        }
    }

    @Test
    fun `convergence apres application des operations`() {
        val random = Random(7)
        repeat(200) {
            val universe = ('a'..'h').map { it.toString() }
            val base = universe.shuffled(random).take(random.nextInt(0, 6))
            val local = mutate(base, universe, random)
            val remote = mutate(base, universe, random)
            val r = OrderedListReconciler.reconcile(base, local, remote)

            val localAfter = local.slotsFor().filter { it in r.finalOrder } + r.addLocal
            val remoteAfter = remote.slotsFor().filter { it in r.finalOrder } + r.addRemote
            assertEquals(r.finalOrder.toSet(), localAfter.toSet())
            assertEquals(r.finalOrder.toSet(), remoteAfter.toSet())
            assertEquals(r.localNeedsReorder, localAfter != r.finalOrder)
            assertEquals(r.remoteNeedsReorder, remoteAfter != r.finalOrder)
        }
    }

    private fun mutate(base: List<String>, universe: List<String>, random: Random): List<String> {
        var list = base
        if (list.isNotEmpty() && random.nextBoolean()) list = list.filterIndexed { i, _ -> i != random.nextInt(list.size) }
        if (random.nextBoolean()) list = list + universe.filter { it !in list }.shuffled(random).take(1)
        if (random.nextBoolean()) list = list.shuffled(random)
        return list
    }

    private fun List<String>.slotsFor(): List<Slot<String>> {
        val counts = HashMap<String, Int>()
        return map { val n = counts.getOrDefault(it, 0); counts[it] = n + 1; Slot(it, n) }
    }

    private fun <E> apply(current: List<E>, moves: List<Move<E>>): List<E> {
        val list = current.toMutableList()
        for (m in moves) {
            list.remove(m.item)
            list.add(list.indexOf(m.before), m.item)
        }
        return list
    }
}
