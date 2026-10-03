package com.spautifaille.ui.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginSessionTest {

    @Test
    fun `only https music youtube pages are music pages`() {
        assertTrue(LoginSession.isMusicPage("https://music.youtube.com/"))
        assertTrue(LoginSession.isMusicPage("https://music.youtube.com?cbrd=1"))
        assertTrue(LoginSession.isMusicPage("https://music.youtube.com/library#x"))
        assertFalse(LoginSession.isMusicPage("http://music.youtube.com/"))
        assertFalse(LoginSession.isMusicPage("https://accounts.google.com/ServiceLogin?continue=https://music.youtube.com"))
        assertFalse(LoginSession.isMusicPage("https://music.youtube.com.evil.example/"))
        assertFalse(LoginSession.isMusicPage(null))
    }

    @Test
    fun `session cookie is detected by name`() {
        assertTrue(LoginSession.hasSessionCookie("VISITOR_INFO1_LIVE=x; SAPISID=abc; PREF=1"))
        assertTrue(LoginSession.hasSessionCookie("__Secure-3PAPISID=abc"))
        assertFalse(LoginSession.hasSessionCookie("VISITOR_INFO1_LIVE=x; PREF=1"))
        // Le nom doit correspondre exactement, pas seulement contenir SAPISID.
        assertFalse(LoginSession.hasSessionCookie("NOT_SAPISID=abc"))
        assertFalse(LoginSession.hasSessionCookie(""))
        assertFalse(LoginSession.hasSessionCookie(null))
    }

    @Test
    fun `parses the javascript result`() {
        // evaluateJavascript renvoie un littéral JSON de chaîne contenant l'objet.
        val raw = "\"{\\\"v\\\":\\\"CgtWaXNpdG9y\\\",\\\"d\\\":\\\"1234||\\\",\\\"i\\\":\\\"1\\\"}\""
        val config = LoginSession.parseConfig(raw)
        assertEquals(LoginSession.PageConfig("CgtWaXNpdG9y", "1234||", "1"), config)
    }

    @Test
    fun `unreadable javascript results give no config`() {
        assertNull(LoginSession.parseConfig(null))
        assertNull(LoginSession.parseConfig("null"))
        assertNull(LoginSession.parseConfig(""))
        assertNull(LoginSession.parseConfig("\"pas du json\""))
        assertNull(LoginSession.parseConfig("42"))
    }

    @Test
    fun `missing session index falls back to the first account`() {
        val config = LoginSession.parseConfig("\"{\\\"v\\\":\\\"vd\\\",\\\"d\\\":null}\"")
        assertEquals("0", config?.sessionIndex)
        assertNull(config?.dataSyncId)
    }

    @Test
    fun `credentials need visitor data and keep the part of datasync id before the separator`() {
        val credentials = LoginSession.credentials("SAPISID=abc", LoginSession.PageConfig("vd", "1234||5678", "2"))
        assertNotNull(credentials)
        assertEquals("SAPISID=abc", credentials!!.cookie)
        assertEquals("vd", credentials.visitorData)
        assertEquals("1234", credentials.dataSyncId)
        assertEquals("2", credentials.authUser)

        assertNull(LoginSession.credentials("SAPISID=abc", LoginSession.PageConfig(null, "1", "0")))
        assertNull(LoginSession.credentials("SAPISID=abc", LoginSession.PageConfig("  ", "1", "0")))
        assertNull(LoginSession.credentials("SAPISID=abc", null))
    }

    @Test
    fun `credentials never print the cookie`() {
        val credentials = LoginSession.credentials("SAPISID=secret-value", LoginSession.PageConfig("vd", "1", "0"))!!
        assertFalse(credentials.toString().contains("secret-value"))
    }

    @Test
    fun `user agent looks like Chrome mobile rather than a WebView`() {
        val webView = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/AP1A; wv) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Version/4.0 Chrome/126.0.0.0 Mobile Safari/537.36"
        val browser = LoginSession.browserUserAgent(webView)
        assertFalse(browser.contains("; wv"))
        assertFalse(browser.contains("Version/4.0"))
        assertTrue(browser.contains("Chrome/126.0.0.0 Mobile Safari/537.36"))
    }
}
