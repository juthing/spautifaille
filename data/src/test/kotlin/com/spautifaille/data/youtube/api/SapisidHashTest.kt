package com.spautifaille.data.youtube.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SapisidHashTest {

    @Test
    fun `valeur attendue pour un cookie et un horodatage fixes`() {
        // Valeur calculée avec la formule de ytmusicapi (helpers.get_authorization) :
        //   sha1(str(ts) + " " + (sapisid + " " + origin)).hexdigest()
        // avec ts = 1700000000, sapisid = "AbCdEf123_sapisid-VALUE", origin = "https://music.youtube.com".
        assertEquals(
            "SAPISIDHASH 1700000000_7b256a560598a3c89560973a2bd6a5f8a465b023",
            SapisidHash.authorization("AbCdEf123_sapisid-VALUE", "https://music.youtube.com", 1_700_000_000L),
        )
    }

    @Test
    fun `le hash change avec l origine, le cookie et l horodatage`() {
        val reference = SapisidHash.authorization("sap", "https://music.youtube.com", 10)
        assertEquals(reference, SapisidHash.authorization("sap", "https://music.youtube.com", 10))
        listOf(
            SapisidHash.authorization("sap", "https://www.youtube.com", 10),
            SapisidHash.authorization("sap2", "https://music.youtube.com", 10),
            SapisidHash.authorization("sap", "https://music.youtube.com", 11),
        ).forEach { assert(it != reference) }
    }

    @Test
    fun `le cookie SAPISID est prefere puis 3PAPISID puis 1PAPISID`() {
        assertEquals("a", SapisidHash.sapisidFromCookie("SID=x; SAPISID=a; __Secure-3PAPISID=b"))
        assertEquals("b", SapisidHash.sapisidFromCookie("SID=x; __Secure-3PAPISID=b; __Secure-1PAPISID=c"))
        assertEquals("c", SapisidHash.sapisidFromCookie("__Secure-1PAPISID=c"))
        assertNull(SapisidHash.sapisidFromCookie("SID=x; HSID=y"))
        assertNull(SapisidHash.sapisidFromCookie(""))
    }

    @Test
    fun `valeurs de cookie avec guillemets ou signe egal`() {
        val cookies = SapisidHash.parseCookieHeader("a=\"quoted\"; b=x=y; =oops; c")
        assertEquals("quoted", cookies["a"])
        assertEquals("x=y", cookies["b"])
        assertEquals(2, cookies.size)
    }
}
