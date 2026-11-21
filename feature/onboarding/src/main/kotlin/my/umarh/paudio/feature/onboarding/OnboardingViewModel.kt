package my.umarh.paudio.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import my.umarh.paudio.data.repo.ListenerRepository
import my.umarh.paudio.data.repo.ProfileRepository
import my.umarh.paudio.data.settings.SettingsRepository

/** The only thing onboarding needs from the engine: start the foreground service. */
interface ServiceStarter {
    /** Returns false when the platform refused to start the service. */
    fun start(): Boolean
}

enum class OnboardingStep { WELCOME, HOW_IT_WORKS, DISCLAIMER, NOTIFICATIONS, LISTENER }

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val disclaimerAccepted: Boolean = false,
    /** The user refused the notification permission: show the copy, then continue. */
    val notificationsDenied: Boolean = false,
    val listenerName: String = "",
    val saving: Boolean = false,
    val error: Boolean = false,
    val finished: Boolean = false,
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val listeners: ListenerRepository,
    private val profiles: ProfileRepository,
    private val settings: SettingsRepository,
    private val starter: ServiceStarter,
) : ViewModel() {
    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    private val steps = OnboardingStep.entries

    fun next() = _state.update { s ->
        // The disclaimer can only be left through acknowledgeDisclaimer().
        if (s.step == OnboardingStep.DISCLAIMER && !s.disclaimerAccepted) return@update s
        val i = steps.indexOf(s.step)
        if (i >= steps.lastIndex) s else s.copy(step = steps[i + 1])
    }

    fun back() = _state.update { s ->
        val i = steps.indexOf(s.step)
        if (i <= 0) s else s.copy(step = steps[i - 1])
    }

    /** "I understand": records the acknowledgement and moves on. */
    fun acknowledgeDisclaimer() {
        _state.update {
            if (it.step == OnboardingStep.DISCLAIMER) it.copy(disclaimerAccepted = true, step = OnboardingStep.NOTIFICATIONS) else it
        }
    }

    /** Result of the system permission dialog. Granted moves on; denied shows the copy and waits for "Continue". */
    fun onNotificationResult(granted: Boolean) {
        if (granted) {
            _state.update { it.copy(notificationsDenied = false, step = OnboardingStep.LISTENER) }
        } else {
            _state.update { it.copy(notificationsDenied = true) }
        }
    }

    fun setListenerName(name: String) = _state.update { it.copy(listenerName = name.take(MAX_NAME), error = false) }

    /** Creates the listener (default [defaultName]), starts the service, marks onboarding done. */
    fun finish(defaultName: String) {
        val s = _state.value
        if (s.saving || s.finished || !s.disclaimerAccepted) return
        _state.update { it.copy(saving = true, error = false) }
        viewModelScope.launch {
            try {
                val name = s.listenerName.trim().ifBlank { defaultName }
                val listenerId = listeners.getOrCreate(name)
                settings.setActiveListenerId(listenerId)
                profiles.ensureAnyHeadphonesProfile(listenerId)
                starter.start()
                settings.setOnboardingDone(true)
                _state.update { it.copy(saving = false, finished = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, error = true) }
            }
        }
    }

    companion object {
        const val MAX_NAME = 40
    }
}
