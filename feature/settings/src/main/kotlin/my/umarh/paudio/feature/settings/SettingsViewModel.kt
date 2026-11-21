package my.umarh.paudio.feature.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import my.umarh.paudio.data.settings.EngineMode
import my.umarh.paudio.data.settings.SettingsRepository

/** What Settings needs from the engine (adapter over `EngineController`; faked in tests). */
interface SettingsEngine {
    val running: StateFlow<Boolean>

    /** Persists the system-wide mode; the running pipeline applies it (Session and Legacy are mutually exclusive). */
    suspend fun setMode(mode: EngineMode)
    fun start(): Boolean
    fun stop()
}

@Immutable
data class SettingsUiState(
    val loading: Boolean = true,
    val mode: EngineMode = EngineMode.SESSION,
    val startOnBoot: Boolean = false,
    val defaultStrength: Float = 0.5f,
    val serviceRunning: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val engine: SettingsEngine,
) : ViewModel() {
    val uiState: StateFlow<SettingsUiState> = combine(
        settings.engineMode,
        settings.startOnBoot,
        settings.defaultCompStrength,
        engine.running,
    ) { mode, boot, strength, running ->
        SettingsUiState(loading = false, mode = mode, startOnBoot = boot, defaultStrength = strength, serviceRunning = running)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setMode(mode: EngineMode) {
        if (mode == uiState.value.mode) return
        viewModelScope.launch { engine.setMode(mode) }
    }

    fun setStartOnBoot(enabled: Boolean) {
        viewModelScope.launch { settings.setStartOnBoot(enabled) }
    }

    fun setDefaultStrength(strength: Float) {
        viewModelScope.launch { settings.setDefaultCompStrength(strength) }
    }

    fun setServiceOn(on: Boolean) {
        if (on) engine.start() else engine.stop()
    }
}
