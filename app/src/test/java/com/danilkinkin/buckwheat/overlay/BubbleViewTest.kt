package com.danilkinkin.buckwheat.overlay

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.danilkinkin.buckwheat.ui.BuckwheatTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class BubbleViewTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `idle bubble shows mic and tap invokes onTap`() {
        var taps = 0
        composeRule.setContent {
            BuckwheatTheme {
                BubbleContent(
                    state = BubbleUiState(BubbleFeedbackState.IDLE),
                    onTap = { taps++ },
                )
            }
        }
        composeRule.onNodeWithContentDescription("voice input bubble").performClick()
        assertEquals(1, taps)
    }

    @Test
    fun `added bubble shows caption chip text`() {
        composeRule.setContent {
            BuckwheatTheme {
                BubbleContent(
                    state = BubbleUiState(BubbleFeedbackState.ADDED, "Added 150"),
                    onTap = {},
                )
            }
        }
        composeRule.onNodeWithContentDescription("Added 150").assertExists()
    }

    @Test
    fun `error bubble shows message chip text`() {
        composeRule.setContent {
            BuckwheatTheme {
                BubbleContent(
                    state = BubbleUiState(BubbleFeedbackState.ERROR, "Couldn\'t understand"),
                    onTap = {},
                )
            }
        }
        composeRule.onNodeWithContentDescription("Couldn\'t understand").assertExists()
    }
}
