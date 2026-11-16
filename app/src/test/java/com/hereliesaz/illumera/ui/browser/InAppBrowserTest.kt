package com.hereliesaz.illumera.ui.browser

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp")
class InAppBrowserTest {
    @Suppress("DEPRECATION")
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the toolbar shows the page host and Close closes the browser`() {
        var closed = 0
        compose.setContent {
            com.hereliesaz.illumera.ui.theme.LumeraTheme {
                InAppBrowser(url = "https://www.example.com/configure", onClose = { closed++ })
            }
        }
        compose.onNodeWithText("example.com").assertExists()
        compose.onNodeWithText("CLOSE").performClick()
        assertEquals(1, closed)
    }
}
