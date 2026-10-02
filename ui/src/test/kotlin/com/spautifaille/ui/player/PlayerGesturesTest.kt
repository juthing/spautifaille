package com.spautifaille.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerGesturesTest {
    private val threshold = 100f
    private val fling = 800f

    @Test
    fun `un glissement court et lent n'est pas valide`() {
        assertEquals(SwipeResult.None, resolveHorizontalSwipe(40f, 100f, threshold, fling))
        assertEquals(SwipeResult.None, resolveHorizontalSwipe(-40f, -100f, threshold, fling))
        assertEquals(SwipeResult.None, resolveHorizontalSwipe(0f, 2_000f, threshold, fling))
    }

    @Test
    fun `depasser le seuil vers la gauche passe au suivant, vers la droite au precedent`() {
        assertEquals(SwipeResult.Next, resolveHorizontalSwipe(-120f, 0f, threshold, fling))
        assertEquals(SwipeResult.Previous, resolveHorizontalSwipe(120f, 0f, threshold, fling))
    }

    @Test
    fun `un geste vif dans le sens du deplacement est valide meme court`() {
        assertEquals(SwipeResult.Next, resolveHorizontalSwipe(-40f, -1_500f, threshold, fling))
        assertEquals(SwipeResult.Previous, resolveHorizontalSwipe(40f, 1_500f, threshold, fling))
    }

    @Test
    fun `un geste vif dans le sens oppose ou presque immobile est ignore`() {
        assertEquals(SwipeResult.None, resolveHorizontalSwipe(-40f, 1_500f, threshold, fling))
        assertEquals(SwipeResult.None, resolveHorizontalSwipe(-5f, -1_500f, threshold, fling))
    }

    @Test
    fun `le lecteur se referme au dela du seuil ou sur un geste vif vers le bas`() {
        assertTrue(shouldCollapsePlayer(300f, 0f, 200f, 1_000f))
        assertTrue(shouldCollapsePlayer(30f, 2_000f, 200f, 1_000f))
        assertFalse(shouldCollapsePlayer(30f, 200f, 200f, 1_000f))
        assertFalse(shouldCollapsePlayer(0f, 2_000f, 200f, 1_000f))
    }

    @Test
    fun `le cran haptique de la barre de progression change toutes les 10 secondes`() {
        assertEquals(0L, seekHapticBucket(0))
        assertEquals(0L, seekHapticBucket(9_999))
        assertEquals(1L, seekHapticBucket(10_000))
        assertEquals(6L, seekHapticBucket(65_000))
        assertEquals(0L, seekHapticBucket(-5))
    }
}
