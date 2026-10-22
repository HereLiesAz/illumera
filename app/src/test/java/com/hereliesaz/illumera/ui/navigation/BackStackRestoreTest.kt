package com.hereliesaz.illumera.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.serialization.NavBackStackSerializer
import androidx.savedstate.SavedState
import androidx.savedstate.serialization.SavedStateConfiguration
import androidx.savedstate.serialization.encodeToSavedState
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The main screen key an older app version saved, which this version no longer has. */
@Serializable
private data class MainKey(val tab: String = "home") : NavKey

/**
 * A back stack saved by an older app version must not crash or blank the app: restore falls
 * back to a fresh Home.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackStackRestoreTest {
    // v2 rules queue coroutines (StandardTestDispatcher); these tests rely on immediate dispatch.
    @Suppress("DEPRECATION")
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** What the older version wrote: its own key set, serialized the same way. */
    private fun savedByOldVersion(vararg keys: NavKey): SavedState {
        val oldConfiguration = SavedStateConfiguration {
            serializersModule = SerializersModule {
                polymorphic(NavKey::class) {
                    subclass(MainKey::class, MainKey.serializer())
                    subclass(DetailsKey::class, DetailsKey.serializer())
                }
            }
        }
        return encodeToSavedState(
            NavBackStackSerializer(PolymorphicSerializer(NavKey::class)),
            NavBackStack(*keys),
            oldConfiguration
        )
    }

    @Test
    fun aStackWithAnUnknownKeyRestoresToNull() {
        assertNull(restoreAppBackStackOrNull(savedByOldVersion(MainKey(), DetailsKey("movie", "tt1"))))
    }

    @Test
    fun aStackWithoutAMainRootAtTheBottomRestoresToNull() {
        assertNull(restoreAppBackStackOrNull(savedByOldVersion(DetailsKey("movie", "tt1"))))
    }

    @Test
    fun rememberAppBackStackStartsFreshFromAnOldVersionsSavedStack() {
        val registry = SaveableStateRegistry(
            restoredValues = mapOf(APP_BACK_STACK_SAVE_KEY to listOf(savedByOldVersion(MainKey(), DetailsKey("movie", "tt1")))),
            canBeSaved = { true }
        )
        var restored: NavBackStack<NavKey>? = null
        compose.setContent {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                restored = rememberAppBackStack()
            }
        }
        compose.waitForIdle()
        assertEquals(listOf<NavKey>(HomeKey()), restored!!.toList())
    }

    @Test
    fun aCurrentStackStillRoundTrips() {
        val tester = StateRestorationTester(compose)
        var stack: NavBackStack<NavKey>? = null
        tester.setContent { stack = rememberAppBackStack() }
        compose.runOnUiThread {
            BackStackOps.navigateToMainRoot(stack!!, NavDestination.Search)
            BackStackOps.openDetails(stack!!, "series", "tt2", title = "Show")
            BackStackOps.openCast(stack!!, 7, "Actor")
            BackStackOps.openPlayer(stack!!)
        }
        val before = stack!!.toList()
        stack = null
        tester.emulateSavedInstanceStateRestore()
        assertEquals(before, stack!!.toList())
    }
}
