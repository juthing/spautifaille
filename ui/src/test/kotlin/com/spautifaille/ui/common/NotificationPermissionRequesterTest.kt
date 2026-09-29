package com.spautifaille.ui.common

import app.cash.turbine.test
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPermissionRequesterTest {

    /** [UiPrefs] en mémoire : `asked` reflète le drapeau persisté. */
    private class PrefsState(var asked: Boolean = false)

    private fun prefs(state: PrefsState): UiPrefs = mockk {
        every { notificationPermissionAsked } answers { state.asked }
        every { notificationPermissionAsked = any() } answers { state.asked = firstArg() }
    }

    @Test
    fun `la premiere demande est emise et memorisee`() = runTest {
        val state = PrefsState()
        val requester = NotificationPermissionRequester(prefs(state), sdkInt = 34)

        requester.requests.test {
            requester.requestIfNeeded()
            awaitItem()
            assertTrue(state.asked)
        }
    }

    @Test
    fun `la permission n est demandee qu une seule fois meme depuis plusieurs sources`() = runTest {
        val state = PrefsState()
        val requester = NotificationPermissionRequester(prefs(state), sdkInt = 34)

        requester.requests.test {
            requester.requestIfNeeded() // lecture
            awaitItem()
            requester.requestIfNeeded() // téléchargement
            requester.requestIfNeeded() // import
            expectNoEvents()
        }
    }

    @Test
    fun `un refus precedent memorise n est pas reitere`() = runTest {
        val state = PrefsState(asked = true)
        val requester = NotificationPermissionRequester(prefs(state), sdkInt = 34)

        requester.requests.test {
            requester.requestIfNeeded()
            expectNoEvents()
        }
    }

    @Test
    fun `avant Android 13 rien n est demande et rien n est memorise`() = runTest {
        val state = PrefsState()
        val requester = NotificationPermissionRequester(prefs(state), sdkInt = 32)

        requester.requests.test {
            requester.requestIfNeeded()
            expectNoEvents()
        }
        assertFalse(state.asked)
    }

    @Test
    fun `une demande emise avant l abonnement du collecteur racine n est pas perdue`() = runTest {
        val state = PrefsState()
        val requester = NotificationPermissionRequester(prefs(state), sdkInt = 33)

        requester.requestIfNeeded()

        requester.requests.test { awaitItem() }
    }
}
