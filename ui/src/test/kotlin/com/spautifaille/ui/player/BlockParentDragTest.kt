package com.spautifaille.ui.player

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Le lecteur plein écran se referme par un glissement vertical sur tout l'écran : un glissement qui démarre
 * sur la barre de progression ou les boutons ne doit pas y participer ([blockParentDrag]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalTestApi::class)
class BlockParentDragTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var parentDelta = 0f

    private fun setContent() {
        composeRule.setContent {
            Column(
                Modifier
                    .fillMaxSize()
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { parentDelta += it },
                    ),
            ) {
                Box(Modifier.size(200.dp).testTag("blocked").blockParentDrag())
                Box(Modifier.size(200.dp).testTag("free"))
            }
        }
    }

    @Test
    fun `un glissement vertical commence sur la zone protegee ne tire pas le parent`() {
        setContent()

        composeRule.onNodeWithTag("blocked").performTouchInput { swipeDown() }

        assertEquals(0f, parentDelta, 0f)
    }

    @Test
    fun `un glissement vertical ailleurs tire le parent`() {
        setContent()

        composeRule.onNodeWithTag("free").performTouchInput { swipeDown() }

        assertTrue(parentDelta > 0f)
    }
}
