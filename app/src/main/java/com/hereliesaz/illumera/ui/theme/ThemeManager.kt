package com.hereliesaz.illumera.ui.theme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.illumera.data.local.AddonDao
import com.hereliesaz.illumera.data.model.ThemeEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ThemeManager @Inject constructor(
    private val dao: AddonDao
) : ViewModel() {

    // Current profile ID for theme resolution
    private val _currentProfileId = MutableStateFlow<Int?>(null)
    
    // All available themes (built-in + custom from database)
    val availableThemes: StateFlow<List<ThemeEntity>> = dao.getAllThemes()
        .combine(MutableStateFlow(DefaultThemes.ALL)) { dbThemes, builtIn ->
            // Merge: built-in themes + any custom themes from DB
            val builtInIds = builtIn.map { it.id }.toSet()
            builtIn + dbThemes.filter { it.id !in builtInIds }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, DefaultThemes.ALL)

    // Currently selected theme based on profile
    private val _currentTheme = MutableStateFlow<ThemeEntity>(DefaultThemes.ILLUMERA)
    val currentTheme: StateFlow<ThemeEntity> = _currentTheme.asStateFlow()

    init {
        // Ensure built-in themes are in database
        viewModelScope.launch {
            seedBuiltInThemes()
        }
    }

    private suspend fun seedBuiltInThemes() {
        DefaultThemes.ALL.forEach { theme ->
            val existing = dao.getThemeById(theme.id)
            if (existing == null) {
                dao.insertTheme(theme)
            }
        }
    }

    /**
     * Set the current profile to resolve its theme
     */
    fun resetTheme() {
        _currentProfileId.value = null
        _currentTheme.value = DefaultThemes.ILLUMERA
    }

    fun setCurrentProfile(profileId: Int, themeId: String) {
        _currentProfileId.value = profileId
        viewModelScope.launch {
            val theme = dao.getThemeById(themeId) ?: DefaultThemes.getById(themeId)
            _currentTheme.value = theme
        }
    }

    /**
     * Assign a theme to a profile
     */
    fun selectTheme(profileId: Int, themeId: String) {
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) {
                dao.insertProfile(profile.copy(themeId = themeId))
            }
            // Update current theme if this is the active profile
            if (_currentProfileId.value == profileId) {
                val theme = dao.getThemeById(themeId) ?: DefaultThemes.getById(themeId)
                _currentTheme.value = theme
            }
        }
    }

    /**
     * Create a new custom theme. If [selectForProfileId] is given, the new theme is
     * assigned to that profile in the same coroutine as the insert — calling
     * selectTheme() separately right after this returns races the insert (both are
     * independently dispatched to Dispatchers.IO with no ordering guarantee), and a
     * loss makes selectTheme() find no row yet and silently fall back to Void.
     */
    fun createCustomTheme(
        name: String,
        primaryColor: Long,
        backgroundColor: Long,
        selectForProfileId: Int? = null
    ): String {
        val id = "custom_${UUID.randomUUID().toString().take(8)}"
        val theme = ThemeEntity(
            id = id,
            name = name,
            primaryColor = primaryColor,
            backgroundColor = backgroundColor,
            surfaceColor = darkenColor(backgroundColor, 0.1f),
            textColor = 0xFFEEEEEE,
            textMutedColor = 0xFF9AA0A6,
            errorColor = 0xFFFF5252,
            isBuiltIn = false,
            category = "custom"
        )
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            dao.insertTheme(theme)
            if (selectForProfileId != null) {
                dao.insertProfile(
                    (dao.getProfileById(selectForProfileId) ?: return@launch).copy(themeId = id)
                )
                if (_currentProfileId.value == selectForProfileId) {
                    _currentTheme.value = theme
                }
            }
        }
        return id
    }

    /**
     * Update an existing custom theme
     */
    fun updateCustomTheme(theme: ThemeEntity) {
        if (theme.isBuiltIn) return // Don't allow modifying built-in themes
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            dao.insertTheme(theme)
            // Update current theme if it's the one being edited
            if (_currentTheme.value.id == theme.id) {
                _currentTheme.value = theme
            }
        }
    }

    /**
     * Delete a custom theme
     */
    fun deleteCustomTheme(themeId: String) {
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            val theme = dao.getThemeById(themeId)
            if (theme != null && !theme.isBuiltIn) {
                dao.deleteTheme(theme)
            }
        }
    }

    // Helper to darken a color for surface
    private fun darkenColor(color: Long, factor: Float): Long {
        val r = ((color shr 16) and 0xFF).toInt()
        val g = ((color shr 8) and 0xFF).toInt()
        val b = (color and 0xFF).toInt()
        val a = ((color shr 24) and 0xFF).toInt()
        
        val newR = (r * (1 - factor)).toInt().coerceIn(0, 255)
        val newG = (g * (1 - factor)).toInt().coerceIn(0, 255)
        val newB = (b * (1 - factor)).toInt().coerceIn(0, 255)
        
        return (a.toLong() shl 24) or (newR.toLong() shl 16) or (newG.toLong() shl 8) or newB.toLong()
    }
}
