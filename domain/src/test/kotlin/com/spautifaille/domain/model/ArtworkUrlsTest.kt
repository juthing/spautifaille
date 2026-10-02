package com.spautifaille.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtworkUrlsTest {

    @Test
    fun `url nulle ou vide donne aucune candidate`() {
        assertNull(ArtworkUrls.square(null, 1000))
        assertNull(ArtworkUrls.square("  ", 1000))
        assertEquals(emptyList<String>(), ArtworkUrls.squareCandidates(null, 1000))
    }

    @Test
    fun `googleusercontent w h est reecrit a la taille demandee avec repli sur l origine`() {
        val url = "https://lh3.googleusercontent.com/abcDEF123_-xyz=w120-h120-l90-rj"
        assertEquals(
            listOf("https://lh3.googleusercontent.com/abcDEF123_-xyz=w1000-h1000-l90-rj", url),
            ArtworkUrls.squareCandidates(url, 1000),
        )
    }

    @Test
    fun `googleusercontent s88 c est reecrit`() {
        assertEquals(
            "https://yt3.ggpht.com/ytc/AIdro=w800-h800-l90-rj",
            ArtworkUrls.square("https://yt3.ggpht.com/ytc/AIdro=s88-c-k-c0x00ffffff-no-rj", 800),
        )
    }

    @Test
    fun `la taille est bornee`() {
        val url = "https://lh3.googleusercontent.com/id=w60-h60-l90-rj"
        assertEquals("https://lh3.googleusercontent.com/id=w1200-h1200-l90-rj", ArtworkUrls.square(url, 5000))
        assertEquals("https://lh3.googleusercontent.com/id=w64-h64-l90-rj", ArtworkUrls.square(url, 10))
    }

    @Test
    fun `googleusercontent sans parametre de taille reste inchange`() {
        val url = "https://lh3.googleusercontent.com/abc"
        assertEquals(listOf(url), ArtworkUrls.squareCandidates(url, 1000))
    }

    @Test
    fun `ytimg passe en maxresdefault et retire les parametres signes`() {
        val url = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hq720.jpg?sqp=-oaymwEXCNUGEOADIAQ&rs=AOn4CLB"
        assertEquals(
            listOf("https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg", url),
            ArtworkUrls.squareCandidates(url, 1000),
        )
    }

    @Test
    fun `ytimg webp garde le format`() {
        assertEquals(
            "https://i.ytimg.com/vi_webp/dQw4w9WgXcQ/maxresdefault.webp",
            ArtworkUrls.square("https://i.ytimg.com/vi_webp/dQw4w9WgXcQ/mqdefault.webp", 720),
        )
    }

    @Test
    fun `ytimg a petite taille reste inchange`() {
        val url = "https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg"
        assertEquals(listOf(url), ArtworkUrls.squareCandidates(url, 200))
    }

    @Test
    fun `maxresdefault deja present n est pas duplique`() {
        val url = "https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg"
        assertEquals(listOf(url), ArtworkUrls.squareCandidates(url, 1000))
    }

    @Test
    fun `url inconnue est renvoyee telle quelle`() {
        val url = "https://example.com/cover.jpg"
        assertEquals(listOf(url), ArtworkUrls.squareCandidates(url, 1000))
    }

    // --- landscapeCandidates ---

    @Test
    fun `paysage - pochette googleusercontent en 1200 px puis origine`() {
        val url = "https://lh3.googleusercontent.com/abcDEF123_-xyz=w544-h544-l90-rj"
        assertEquals(
            listOf("https://lh3.googleusercontent.com/abcDEF123_-xyz=w1200-h1200-l90-rj", url),
            ArtworkUrls.landscapeCandidates("dQw4w9WgXcQ", url),
        )
    }

    @Test
    fun `paysage - miniature ytimg donne maxres sd hq puis origine`() {
        val url = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hq720.jpg?sqp=-oaymw&rs=AOn4"
        assertEquals(
            listOf(
                "https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg",
                "https://i.ytimg.com/vi/dQw4w9WgXcQ/sddefault.jpg",
                "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
                url,
            ),
            ArtworkUrls.landscapeCandidates(null, url),
        )
    }

    @Test
    fun `paysage - l url d origine deja listee n est pas dupliquee`() {
        val url = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"
        assertEquals(3, ArtworkUrls.landscapeCandidates("dQw4w9WgXcQ", url).size)
    }

    @Test
    fun `paysage - sans miniature mais avec identifiant video`() {
        assertEquals(
            listOf(
                "https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg",
                "https://i.ytimg.com/vi/dQw4w9WgXcQ/sddefault.jpg",
                "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
            ),
            ArtworkUrls.landscapeCandidates("dQw4w9WgXcQ", null),
        )
    }

    @Test
    fun `paysage - identifiant invalide ou url inconnue`() {
        val url = "https://example.com/cover.jpg"
        assertEquals(listOf(url), ArtworkUrls.landscapeCandidates("pas-un-id", url))
        assertEquals(listOf(url), ArtworkUrls.landscapeCandidates("dQw4w9WgXcQ".take(5), url))
        assertEquals(emptyList<String>(), ArtworkUrls.landscapeCandidates(null, null))
        assertEquals(emptyList<String>(), ArtworkUrls.landscapeCandidates(null, "  "))
    }

    @Test
    fun `paysage - la pochette google prime sur l identifiant video`() {
        val url = "https://lh3.googleusercontent.com/abc=w120-h120-l90-rj"
        assertEquals(url, ArtworkUrls.landscapeCandidates("dQw4w9WgXcQ", url).last())
    }
}
