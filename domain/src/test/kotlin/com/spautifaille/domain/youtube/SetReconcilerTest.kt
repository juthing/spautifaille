package com.spautifaille.domain.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SetReconcilerTest {

    private fun reconcile(
        base: Set<String>?,
        local: Set<String>,
        remote: Set<String>,
        seen: Set<String>? = null,
    ) = SetReconciler.reconcile(base, local, remote, seen)

    @Test
    fun `premier lien - union des deux ensembles, rien n est supprime`() {
        val r = reconcile(base = null, local = setOf("a", "b"), remote = setOf("b", "c"))
        assertEquals(setOf("a", "b", "c"), r.finalSet)
        assertEquals(setOf("c"), r.addLocal)
        assertEquals(setOf("a"), r.addRemote)
        assertTrue(r.removeLocal.isEmpty())
        assertTrue(r.removeRemote.isEmpty())
    }

    @Test
    fun `premier lien avec un cote vide importe ou pousse tout`() {
        val fromRemote = reconcile(null, emptySet(), setOf("x", "y"))
        assertEquals(setOf("x", "y"), fromRemote.addLocal)
        val fromLocal = reconcile(null, setOf("x", "y"), emptySet())
        assertEquals(setOf("x", "y"), fromLocal.addRemote)
    }

    @Test
    fun `aucun changement depuis le dernier etat synchronise ne produit aucune operation`() {
        val s = setOf("a", "b")
        val r = reconcile(s, s, s)
        assertEquals(s, r.finalSet)
        assertTrue(r.addLocal.isEmpty() && r.removeLocal.isEmpty() && r.addRemote.isEmpty() && r.removeRemote.isEmpty())
    }

    @Test
    fun `ajout local est pousse vers le distant`() {
        val r = reconcile(setOf("a"), local = setOf("a", "b"), remote = setOf("a"))
        assertEquals(setOf("b"), r.addRemote)
        assertTrue(r.addLocal.isEmpty())
        assertEquals(setOf("a", "b"), r.finalSet)
    }

    @Test
    fun `ajout distant est importe en local`() {
        val r = reconcile(setOf("a"), local = setOf("a"), remote = setOf("a", "c"))
        assertEquals(setOf("c"), r.addLocal)
        assertTrue(r.addRemote.isEmpty())
    }

    @Test
    fun `suppression locale est propagee au distant`() {
        val r = reconcile(setOf("a", "b"), local = setOf("a"), remote = setOf("a", "b"))
        assertEquals(setOf("b"), r.removeRemote)
        assertEquals(setOf("a"), r.finalSet)
        assertTrue(r.addLocal.isEmpty())
    }

    @Test
    fun `suppression distante est propagee au local`() {
        val r = reconcile(setOf("a", "b"), local = setOf("a", "b"), remote = setOf("a"))
        assertEquals(setOf("b"), r.removeLocal)
        assertEquals(setOf("a"), r.finalSet)
        assertTrue(r.addRemote.isEmpty())
    }

    @Test
    fun `suppression des deux cotes ne declenche rien`() {
        val r = reconcile(setOf("a", "b"), local = setOf("a"), remote = setOf("a"))
        assertEquals(setOf("a"), r.finalSet)
        assertTrue(r.removeLocal.isEmpty() && r.removeRemote.isEmpty())
    }

    @Test
    fun `ajout des deux cotes du meme element ne declenche rien`() {
        val r = reconcile(emptySet(), local = setOf("n"), remote = setOf("n"))
        assertEquals(setOf("n"), r.finalSet)
        assertTrue(r.addLocal.isEmpty() && r.addRemote.isEmpty())
    }

    @Test
    fun `changements croises sur des elements differents sont tous appliques`() {
        // local : +c, -a ; distant : +d, -b.
        val r = reconcile(setOf("a", "b"), local = setOf("b", "c"), remote = setOf("a", "d"))
        assertEquals(setOf("c", "d"), r.finalSet)
        assertEquals(setOf("d"), r.addLocal)
        assertEquals(setOf("b"), r.removeLocal)
        assertEquals(setOf("c"), r.addRemote)
        assertEquals(setOf("a"), r.removeRemote)
    }

    @Test
    fun `retire en local mais deja retire a distance puis rajoute en local reste coherent`() {
        // base {a}; local a retiré puis re-ajouté : local == base ; distant a supprimé -> suppression distante gagne.
        val r = reconcile(setOf("a"), local = setOf("a"), remote = emptySet())
        assertEquals(emptySet<String>(), r.finalSet)
        assertEquals(setOf("a"), r.removeLocal)
    }

    @Test
    fun `element invisible cote distant n est ni supprime ni repousse`() {
        // « v » est liké en local et dans base mais n'a jamais été vu côté distant (vidéo non musicale, vue LM).
        val r = reconcile(
            base = setOf("m", "v"),
            local = setOf("m", "v"),
            remote = setOf("m"),
            seen = setOf("m"),
        )
        assertEquals(setOf("m", "v"), r.finalSet)
        assertTrue(r.removeLocal.isEmpty())
        assertTrue("pas de nouvelle poussée à chaque synchro", r.addRemote.isEmpty())
    }

    @Test
    fun `element vu puis disparu du distant est bien supprime en local`() {
        val r = reconcile(
            base = setOf("m", "v"),
            local = setOf("m", "v"),
            remote = setOf("v"),
            seen = setOf("m", "v"),
        )
        assertEquals(setOf("v"), r.finalSet)
        assertEquals(setOf("m"), r.removeLocal)
    }

    @Test
    fun `element invisible supprime en local est retire du distant`() {
        val r = reconcile(
            base = setOf("m", "v"),
            local = setOf("m"),
            remote = setOf("m"),
            seen = setOf("m"),
        )
        assertEquals(setOf("m"), r.finalSet)
        // Absent du distant : la suppression distante est un no-op ; rien à retirer.
        assertTrue(r.removeRemote.isEmpty())
    }

    @Test
    fun `seenAfter garde les elements vus et toujours presents`() {
        val r = reconcile(
            base = setOf("a"),
            local = setOf("a", "b"),
            remote = setOf("a", "c"),
            seen = setOf("a"),
        )
        assertEquals(setOf("a", "b", "c"), r.finalSet)
        assertEquals(setOf("a", "c"), r.seenAfter)
    }

    @Test
    fun `la reconciliation est idempotente apres application`() {
        val base = setOf("a", "b", "c")
        val local = setOf("a", "c", "x")
        val remote = setOf("a", "b", "y")
        val first = reconcile(base, local, remote)
        val second = reconcile(first.finalSet, first.finalSet, first.finalSet)
        assertEquals(first.finalSet, second.finalSet)
        assertTrue(second.addLocal.isEmpty() && second.removeLocal.isEmpty())
        assertTrue(second.addRemote.isEmpty() && second.removeRemote.isEmpty())
    }
}
