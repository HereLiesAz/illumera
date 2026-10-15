package com.hereliesaz.illumera.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.serialization.NavBackStackSerializer
import androidx.savedstate.SavedState
import androidx.savedstate.serialization.SavedStateConfiguration
import androidx.savedstate.serialization.decodeFromSavedState
import androidx.savedstate.serialization.encodeToSavedState
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic

/*
 * The app's root back stack: a list of these keys, saved across process death. Keys hold
 * only strings and ints so a restore after R8 never depends on Parcelable class names.
 */

/**
 * A main screen chosen in the menu (side rail or top bar). Exactly one is on the stack, always
 * at the bottom. [nonce] tells a re-selected screen from the one it replaces, so choosing it
 * again in the menu gives a fresh entry (new saveable state and entry ViewModels).
 */
sealed interface MainRootKey : NavKey {
    val nonce: Int
    val destination: NavDestination
}

@Serializable
data class HomeKey(override val nonce: Int = 0) : MainRootKey {
    override val destination: NavDestination get() = NavDestination.Home
}

@Serializable
data class MoviesKey(override val nonce: Int = 0) : MainRootKey {
    override val destination: NavDestination get() = NavDestination.Movies
}

@Serializable
data class SeriesKey(override val nonce: Int = 0) : MainRootKey {
    override val destination: NavDestination get() = NavDestination.Series
}

@Serializable
data class SearchKey(override val nonce: Int = 0) : MainRootKey {
    override val destination: NavDestination get() = NavDestination.Search
}

@Serializable
data class WatchlistKey(override val nonce: Int = 0) : MainRootKey {
    override val destination: NavDestination get() = NavDestination.Watchlist
}

@Serializable
data class AddonsKey(override val nonce: Int = 0) : MainRootKey {
    override val destination: NavDestination get() = NavDestination.Addons
}

@Serializable
data class SettingsKey(override val nonce: Int = 0) : MainRootKey {
    override val destination: NavDestination get() = NavDestination.Settings
}

/** The root key for a menu destination; null for the menu's actions (Log Out, Exit). */
fun mainRootKeyFor(destination: NavDestination, nonce: Int = 0): MainRootKey? = when (destination) {
    NavDestination.Home -> HomeKey(nonce)
    NavDestination.Movies -> MoviesKey(nonce)
    NavDestination.Series -> SeriesKey(nonce)
    NavDestination.Search -> SearchKey(nonce)
    NavDestination.Watchlist -> WatchlistKey(nonce)
    NavDestination.Addons -> AddonsKey(nonce)
    NavDestination.Settings -> SettingsKey(nonce)
    NavDestination.Profile, NavDestination.Exit -> null
}

/**
 * "View more" grid. Its items live in MainActivity's in-memory holder, not in the key, so
 * after process death the grid has nothing to show and returns to the screen below it.
 * Like the main screens, it shows the menu.
 */
@Serializable
data class GridKey(val title: String, val configId: String) : NavKey

/** Whether [key] is shown with the menu chrome (a main screen or the grid over one). */
fun isMainAreaKey(key: NavKey?): Boolean = key is MainRootKey || key is GridKey

/**
 * A title's Details page. [title], [poster] and [logo] are what the opener already knew, shown
 * until the page resolves its own and handed to the player when playback starts here.
 * [queueAutoPlayId] is the episode or movie a queue advance asks the page to start; the page
 * starts it once (DetailsViewModel remembers that it did). [instance] keeps two pages for the
 * same title (a queue advance within one show, or a recommendation loop) apart on the stack.
 */
@Serializable
data class DetailsKey(
    val type: String,
    val id: String,
    val addon: String? = null,
    val title: String = "",
    val poster: String = "",
    val logo: String = "",
    val queueAutoPlayId: String? = null,
    val instance: Int = 0
) : NavKey {
    /** Names this page to the playback session, which hands its resume hint back to it. */
    val playbackOwnerTag: String get() = "$type:$id:$instance"
}

/**
 * A cast member's filmography, opened from a Details page. [instance], like DetailsKey's, keeps
 * the same person opened twice on one stack (via another title) two distinct entries.
 */
@Serializable
data class CastKey(val personId: Int, val name: String, val instance: Int = 0) : NavKey

/** A studio, network or company's titles, opened from a Details page. */
@Serializable
data class StudioKey(
    val entityId: Int,
    val kind: String,
    val name: String,
    val sourceType: String,
    val instance: Int = 0
) : NavKey

/** Titles like [id] (TMDB recommendations), opened from the card menu or a Details page. */
@Serializable
data class MoreLikeThisKey(
    val type: String,
    val id: String,
    val title: String,
    val instance: Int = 0
) : NavKey

/** The internal player; the playback session holds what it plays. Trailers use it too. */
@Serializable
data object PlayerKey : NavKey

/** Saves the back stack by registered subclass, without reflection on class names. */
val AppBackStackConfiguration: SavedStateConfiguration = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclass(HomeKey::class, HomeKey.serializer())
            subclass(MoviesKey::class, MoviesKey.serializer())
            subclass(SeriesKey::class, SeriesKey.serializer())
            subclass(SearchKey::class, SearchKey.serializer())
            subclass(WatchlistKey::class, WatchlistKey.serializer())
            subclass(AddonsKey::class, AddonsKey.serializer())
            subclass(SettingsKey::class, SettingsKey.serializer())
            subclass(GridKey::class, GridKey.serializer())
            subclass(DetailsKey::class, DetailsKey.serializer())
            subclass(CastKey::class, CastKey.serializer())
            subclass(StudioKey::class, StudioKey.serializer())
            subclass(MoreLikeThisKey::class, MoreLikeThisKey.serializer())
            subclass(PlayerKey::class, PlayerKey.serializer())
        }
    }
}

private val AppBackStackSerializer = NavBackStackSerializer(PolymorphicSerializer(NavKey::class))

/** The saved-state key MainActivity's back stack is stored under. */
internal const val APP_BACK_STACK_SAVE_KEY = "app_back_stack"

/**
 * Restores a saved back stack, or null when it can't be used: a key this version no longer
 * has (a stack saved by an older app version, e.g. with the removed MainKey), a corrupt bundle,
 * or a stack without a main screen at the bottom. Null means "start from a fresh Home".
 */
internal fun restoreAppBackStackOrNull(saved: SavedState): NavBackStack<NavKey>? {
    val stack = try {
        decodeFromSavedState(AppBackStackSerializer, saved, AppBackStackConfiguration)
    } catch (_: Exception) {
        return null
    }
    return stack.takeIf { it.isNotEmpty() && it.first() is MainRootKey }
}

/** Saves the back stack by [AppBackStackConfiguration]; an unusable saved stack restores to null. */
internal val AppBackStackSaver: Saver<NavBackStack<NavKey>, SavedState> = Saver(
    save = { encodeToSavedState(AppBackStackSerializer, it, AppBackStackConfiguration) },
    restore = { restoreAppBackStackOrNull(it) }
)

/**
 * MainActivity's back stack, saved across process death. A saved stack this version can't read
 * (see [restoreAppBackStackOrNull]) gives way to a fresh Home instead of crashing or blanking.
 */
@Composable
fun rememberAppBackStack(): NavBackStack<NavKey> =
    rememberSaveable(saver = AppBackStackSaver, key = APP_BACK_STACK_SAVE_KEY) { NavBackStack<NavKey>(HomeKey()) }

/**
 * Every change MainActivity makes to the back stack. Plain list operations, so the
 * navigation contract is testable without a composition.
 *
 * - The stack is never empty, and its bottom is always exactly one [MainRootKey].
 * - Choosing a main screen in the menu clears the stack down to that screen's fresh root.
 * - Back pops one entry; at the root alone it pops nothing and the main screen handles Back.
 * - Leaving the player pops only the player, so Back lands on whatever opened it.
 * - A queue advance replaces the player with the next item's Details on top of the stack.
 * - Details, Cast and Studio pages are flat entries: each opened page is pushed, Back pops it.
 */
object BackStackOps {

    /**
     * The menu chose [destination]: the stack becomes that screen's root alone, as a new
     * entry even when it is the screen already showing. Returns false for the menu's
     * actions (Log Out, Exit), which leave the stack alone.
     */
    fun navigateToMainRoot(stack: MutableList<NavKey>, destination: NavDestination): Boolean {
        val previousNonce = (stack.firstOrNull() as? MainRootKey)?.nonce ?: -1
        val root = mainRootKeyFor(destination, previousNonce + 1) ?: return false
        // Add first so the stack is never empty in between.
        stack.add(root)
        while (stack.size > 1) stack.removeAt(0)
        return true
    }

    /** The main screen at the bottom of the stack. */
    fun currentRoot(stack: List<NavKey>): MainRootKey = stack.firstOrNull() as? MainRootKey ?: HomeKey()

    fun openDetails(
        stack: MutableList<NavKey>,
        type: String,
        id: String,
        addon: String? = null,
        title: String = "",
        poster: String = "",
        logo: String = "",
        queueAutoPlayId: String? = null
    ) {
        stack.add(
            DetailsKey(
                type = type,
                id = id,
                addon = addon,
                title = title,
                poster = poster,
                logo = logo,
                queueAutoPlayId = queueAutoPlayId,
                instance = stack.count { it is DetailsKey }
            )
        )
    }

    fun openCast(stack: MutableList<NavKey>, personId: Int, name: String) {
        stack.add(CastKey(personId, name, instance = stack.count { it is CastKey }))
    }

    fun openStudio(stack: MutableList<NavKey>, entityId: Int, kind: String, name: String, sourceType: String) {
        stack.add(StudioKey(entityId, kind, name, sourceType, instance = stack.count { it is StudioKey }))
    }

    fun openMoreLikeThis(stack: MutableList<NavKey>, type: String, id: String, title: String) {
        stack.add(MoreLikeThisKey(type, id, title, instance = stack.count { it is MoreLikeThisKey }))
    }

    fun openGrid(stack: MutableList<NavKey>, title: String, configId: String) {
        if (stack.lastOrNull() is GridKey) stack.removeAt(stack.lastIndex)
        stack.add(GridKey(title, configId))
    }

    fun openPlayer(stack: MutableList<NavKey>) {
        if (stack.lastOrNull() != PlayerKey) stack.add(PlayerKey)
    }

    /** Pops the player if it is on top; returns whether it was. */
    fun returnFromPlayer(stack: MutableList<NavKey>): Boolean {
        if (stack.lastOrNull() != PlayerKey) return false
        stack.removeAt(stack.lastIndex)
        ensureRoot(stack)
        return true
    }

    /** The queue moved on: the player gives way to the next item's Details, pushed on top. */
    fun queueAdvance(
        stack: MutableList<NavKey>,
        type: String,
        id: String,
        title: String,
        poster: String,
        queueAutoPlayId: String? = null
    ) {
        returnFromPlayer(stack)
        openDetails(stack, type = type, id = id, title = title, poster = poster, queueAutoPlayId = queueAutoPlayId)
    }

    /** Back: pops one entry unless only the main root is left. */
    fun pop(stack: MutableList<NavKey>): Boolean {
        if (stack.size <= 1) {
            ensureRoot(stack)
            return false
        }
        stack.removeAt(stack.lastIndex)
        return true
    }

    /** A grid restored with no items has nothing to show; returns to the screen before it. */
    fun dropEmptyGrid(stack: MutableList<NavKey>, hasItems: Boolean): Boolean {
        if (hasItems || stack.lastOrNull() !is GridKey) return false
        return pop(stack)
    }

    /** Logout: a fresh Home alone, so the next profile starts from the top. */
    fun resetToMain(stack: MutableList<NavKey>) {
        navigateToMainRoot(stack, NavDestination.Home)
    }

    private fun ensureRoot(stack: MutableList<NavKey>) {
        if (stack.firstOrNull() !is MainRootKey) stack.add(0, HomeKey())
    }
}
