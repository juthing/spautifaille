package com.spautifaille.domain.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeChannelIdsTest {

    private val id = "UC2Eotb0QaPkaJI4Cw4oHZ6Q"

    @Test
    fun `extrait l identifiant d une URL de chaine`() {
        assertEquals(id, YouTubeChannelIds.fromUrl("https://www.youtube.com/channel/$id"))
        assertEquals(id, YouTubeChannelIds.fromUrl("https://music.youtube.com/channel/$id?si=x"))
        assertEquals(id, YouTubeChannelIds.fromUrl("https://m.youtube.com/channel/$id/videos"))
    }

    @Test
    fun `refuse les URL sans identifiant de chaine`() {
        assertNull(YouTubeChannelIds.fromUrl("https://www.youtube.com/@handle"))
        assertNull(YouTubeChannelIds.fromUrl("https://www.youtube.com/channel/court"))
        assertNull(YouTubeChannelIds.fromUrl("n'importe quoi"))
    }

    @Test
    fun `aller retour URL identifiant`() {
        assertEquals(id, YouTubeChannelIds.fromUrl(YouTubeChannelIds.toUrl(id)))
    }

    @Test
    fun `reconnait un identifiant de chaine`() {
        assertTrue(YouTubeChannelIds.isChannelId(id))
        assertFalse(YouTubeChannelIds.isChannelId("MPREb_5tjDwqVSJCv"))
        assertFalse(YouTubeChannelIds.isChannelId("$id-"))
    }
}
