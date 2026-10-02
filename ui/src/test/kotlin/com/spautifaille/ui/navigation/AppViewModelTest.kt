package com.spautifaille.ui.navigation

import app.cash.turbine.test
import com.spautifaille.domain.model.AppSettings
import com.spautifaille.domain.model.ColorSource
import com.spautifaille.domain.model.ThemeMode
import com.spautifaille.domain.model.Track
import com.spautifaille.domain.player.PlaybackController
import com.spautifaille.domain.player.PlayerState
import com.spautifaille.domain.repository.SettingsRepository
import com.spautifaille.ui.common.NotificationPermissionRequester
import com.spautifaille.ui.common.UiMessenger
import com.spautifaille.ui.library.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class AppViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val settings = MutableStateFlow(AppSettings())
    private val playerState = MutableStateFlow(PlayerState())

    private val settingsRepository = mockk<SettingsRepository> {
        every { this@mockk.settings } returns this@AppViewModelTest.settings
    }
    private val controller = mockk<PlaybackController> {
        every { state } returns playerState
        every { events } returns emptyFlow()
    }

    private fun viewModel() = AppViewModel(
        settingsRepository = settingsRepository,
        playbackController = controller,
        messenger = UiMessenger(),
        permissionRequester = mockk<NotificationPermissionRequester>(relaxed = true),
    )

    private fun track(id: String, thumbnail: String?) = Track(id = id, title = id, artist = "a", thumbnailUrl = thumbnail)

    @Test
    fun `le theme est nul tant que les reglages ne sont pas charges puis reflete le mode et la source`() = runTest {
        settings.value = AppSettings(themeMode = ThemeMode.DARK, colorSource = ColorSource.STATIC)
        val vm = viewModel()
        vm.themeSettings.test {
            var item = awaitItem()
            if (item == null) item = awaitItem()
            assertEquals(ThemeSettings(ThemeMode.DARK, ColorSource.STATIC, null), item)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `la pochette du titre en cours n est exposee qu avec la source musique en cours`() = runTest {
        playerState.value = PlayerState(currentTrack = track("a", "https://img/a.jpg"))
        settings.value = AppSettings(colorSource = ColorSource.DYNAMIC)
        val vm = viewModel()
        vm.themeSettings.test {
            var item = awaitItem()
            if (item == null) item = awaitItem()
            assertNull(item?.nowPlayingArtworkUrl)

            settings.value = AppSettings(colorSource = ColorSource.NOW_PLAYING)
            assertEquals("https://img/a.jpg", awaitItem()?.nowPlayingArtworkUrl)

            playerState.value = PlayerState(currentTrack = track("b", "https://img/b.jpg"))
            assertEquals("https://img/b.jpg", awaitItem()?.nowPlayingArtworkUrl)

            playerState.value = PlayerState(currentTrack = null)
            assertNull(awaitItem()?.nowPlayingArtworkUrl)

            settings.value = AppSettings(colorSource = ColorSource.STATIC)
            assertEquals(ThemeSettings(ThemeMode.SYSTEM, ColorSource.STATIC, null), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `un changement de titre ne touche pas le theme hors musique en cours`() = runTest {
        settings.value = AppSettings(colorSource = ColorSource.DYNAMIC)
        val vm = viewModel()
        vm.themeSettings.test {
            var item = awaitItem()
            if (item == null) item = awaitItem()
            playerState.value = PlayerState(currentTrack = track("a", "https://img/a.jpg"))
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
