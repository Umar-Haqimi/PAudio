package my.umarh.paudio.service.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import my.umarh.paudio.audio.effect.EffectEngine
import my.umarh.paudio.audio.effect.EngineDiagnostics
import my.umarh.paudio.audio.effect.EngineState
import my.umarh.paudio.core.model.EqConfig
import my.umarh.paudio.core.model.OutputDevice
import my.umarh.paudio.data.repo.ProfileRepository
import my.umarh.paudio.data.repo.ProfileSettings
import my.umarh.paudio.data.settings.SettingsRepository
import my.umarh.paudio.service.HeadsetPromptSink
import my.umarh.paudio.service.NotificationPermission
import my.umarh.paudio.service.R
import my.umarh.paudio.service.MixerSampleRate
import my.umarh.paudio.service.ServiceFlags
import my.umarh.paudio.service.ServiceLauncher
import my.umarh.paudio.service.di.ServiceScope
import javax.inject.Inject
import javax.inject.Singleton
import my.umarh.paudio.audio.effect.EngineMode as EffectMode
import my.umarh.paudio.data.settings.EngineMode as StoredMode

/**
 * What the engine should be doing for one route; the single unit applied through `EffectEngine.setRoute`.
 * [target] is null when nothing may be processed (no profile, speaker, ...) or while it is still being computed.
 */
internal data class Route(val device: OutputDevice?, val target: EqConfig?, val personalized: Boolean)

/**
 * The facade features consume (SPEC §5.3): owns the wiring between persisted state and the [EffectEngine].
 *
 * While the foreground service runs ([onServiceStarted] .. [onServiceStopped]) a pipeline observes the
 * [ActiveProfileResolver], [ProfileCurveService], the stored mode and the engine's ladder and pushes ONE
 * `setRoute(device, config)` per change (never `setOutputDevice` + `setTarget` separately, so a BT <-> wired
 * switch can never apply the old headset's curve to the new route). Everything it needs is derived from persisted
 * state, so a restart after process death restores mode, Personalized/Original, route and target on its own.
 *
 * Engine setters are asynchronous (posted to the engine's single thread); `acquire()` is synchronous.
 */
@Singleton
class EngineController @Inject constructor(
    private val engine: EffectEngine,
    private val resolver: ActiveProfileResolver,
    private val curves: ProfileCurveService,
    private val settings: SettingsRepository,
    private val profiles: ProfileRepository,
    private val launcher: ServiceLauncher,
    private val flags: ServiceFlags,
    private val mixerRate: MixerSampleRate,
    private val notificationPermission: NotificationPermission,
    private val prompts: HeadsetPromptSink,
    @ServiceScope private val scope: CoroutineScope,
) {
    val state: StateFlow<EngineState> get() = engine.state
    val noSessionHint: StateFlow<Boolean> get() = engine.noSessionHint
    val diagnostics: StateFlow<EngineDiagnostics> get() = engine.diagnostics

    /** The profile (or absence of one) the engine currently follows. */
    val activeProfile: StateFlow<ActiveProfile> get() = resolver.activeProfile

    /** The pending "Which headphones are these?" question (answer through [answerWhichHeadphones]). */
    val whichHeadphones: StateFlow<WhichHeadphones?> get() = resolver.pendingQuestion

    private val _running = MutableStateFlow(false)

    /** The foreground service is up. */
    val running: StateFlow<Boolean> = _running

    /** Personalized (true) / Original (false) of the active profile; false when no profile applies. */
    val personalized: StateFlow<Boolean> = resolver.activeProfile
        .map { (it as? ActiveProfile.Resolved)?.profile?.personalizationOn ?: false }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val _notificationsBlocked = MutableStateFlow(false)

    /**
     * True when notifications are off for PAudio (permission denied): show
     * `R.string.service_notification_permission_denied` ([NOTIFICATION_PERMISSION_DENIED_COPY]).
     * Call [refreshNotificationPermission] on resume.
     */
    val notificationsBlocked: StateFlow<Boolean> = _notificationsBlocked

    private val lifecycleLock = Any()
    private var pipeline: Job? = null

    // ------------------------------------------------------------------ commands for features

    /** Starts the foreground service (call from the foreground). Clears "the user turned it off". */
    fun start(): Boolean {
        flags.userTurnedOff = false
        return try {
            launcher.start()
            true
        } catch (_: IllegalStateException) { // incl. ForegroundServiceStartNotAllowedException
            false
        } catch (_: SecurityException) {
            false
        }
    }

    /** Starts the service on app start when onboarding is done and the user did not turn it off. */
    suspend fun startIfAllowed(): Boolean {
        if (_running.value || flags.userTurnedOff || !settings.onboardingDone.first()) return false
        return start()
    }

    /** Stops the service and remembers that the user turned it off (no boot / sticky revival). */
    fun stop() {
        flags.userTurnedOff = true
        launcher.stop()
    }

    /**
     * Personalized (true) / Original (false) for the active profile, persisted in the profile.
     * Returns false when no profile applies (nothing changed).
     */
    suspend fun setPersonalized(on: Boolean): Boolean {
        val active = resolver.activeProfile.value as? ActiveProfile.Resolved ?: return false
        val current = profiles.get(active.profile.id)?.profile ?: return false
        if (current.personalizationOn != on) {
            profiles.updateSettings(
                current.id,
                ProfileSettings(current.compStrength, current.gB, current.gP, current.gT, personalizationOn = on),
            )
        }
        return true
    }

    suspend fun togglePersonalized(): Boolean = setPersonalized(!personalized.value)

    /** User tap on Conflict/Error: recreate the effects (DECISIONS §3). */
    fun retry() = engine.retry()

    /** Persists the mode; the running pipeline applies it (Session and Legacy are mutually exclusive). */
    suspend fun setMode(mode: EffectMode) {
        settings.setEngineMode(mode.toStored())
    }

    fun answerWhichHeadphones(profileId: Long?) = resolver.answerWhichHeadphones(profileId)

    fun refreshNotificationPermission() {
        _notificationsBlocked.value = !notificationPermission.allowed()
    }

    // ------------------------------------------------------------------ service lifecycle

    /** Called by the service (idempotent): restores the persisted state and starts following it. */
    fun onServiceStarted() {
        synchronized(lifecycleLock) {
            if (pipeline != null) return
            _running.value = true
            refreshNotificationPermission()
            pipeline = scope.launch {
                // Mode first so the engine never opens a session receiver for the wrong mode.
                engine.setMode(settings.engineMode.first().toEngine())
                engine.setRunning(true)
                launch { settings.engineMode.drop(1).collect { engine.setMode(it.toEngine()) } }
                launch { routePipeline().collect(::apply) }
                launch { UnknownHeadsetPrompter(resolver.activeProfile, prompts).run() }
            }
        }
    }

    /** Called by the service when it is destroyed. */
    fun onServiceStopped() {
        synchronized(lifecycleLock) {
            pipeline?.cancel()
            pipeline = null
            _running.value = false
        }
        engine.setRunning(false)
        prompts.clear()
    }

    // ------------------------------------------------------------------ pipeline

    private fun apply(route: Route) {
        // Turning off first / on last, so a profile that is "Original" is never briefly audible.
        if (!route.personalized) {
            engine.setPersonalized(false)
            engine.setRoute(route.device, route.target)
        } else {
            engine.setRoute(route.device, route.target)
            engine.setPersonalized(true)
        }
    }

    private data class Ident(val deviceKey: String?, val kind: Int, val profileId: Long?)

    private fun identOf(a: ActiveProfile): Ident = when (a) {
        ActiveProfile.Loading -> Ident(null, 0, null)
        is ActiveProfile.Resolved -> Ident(a.device.headsetKey.value, 1, a.profile.id)
        is ActiveProfile.NoProfile -> Ident(a.device.headsetKey.value, 2, null)
        is ActiveProfile.NotProcessable -> Ident(a.device?.headsetKey?.value, 3, null)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun routePipeline(): Flow<Route> {
        val fs = mixerRate.hz()
        val ladder = engine.diagnostics.map { it.ladder }.distinctUntilChanged()
        var lastDeviceKey: String? = null
        return resolver.activeProfile
            .filter { it != ActiveProfile.Loading }
            .distinctUntilChangedBy(::identOf)
            .flatMapLatest { first ->
                val ident = identOf(first)
                val deviceChanged = ident.deviceKey != lastDeviceKey
                lastDeviceKey = ident.deviceKey
                when (first) {
                    ActiveProfile.Loading -> flowOf<Route>()
                    is ActiveProfile.NoProfile -> flowOf(Route(first.device, null, false))
                    is ActiveProfile.NotProcessable -> flowOf(Route(first.device, null, false))
                    is ActiveProfile.Resolved -> flow {
                        // The new route must not run the old route's curve while this profile's tests load.
                        if (deviceChanged) emit(Route(first.device, null, false))
                        emitAll(
                            combine(
                                resolver.activeProfile
                                    .filterIsInstance<ActiveProfile.Resolved>()
                                    .filter { identOf(it) == ident },
                                curves.observeSource(first.profile.id),
                                ladder,
                            ) { resolved, source, step ->
                                Route(
                                    resolved.device,
                                    curves.config(resolved.profile, source, fs, step),
                                    resolved.profile.personalizationOn,
                                )
                            },
                        )
                    }
                }
            }
    }

    companion object {
        /** The copy for a denied notification permission (SPEC §4 item 8). */
        const val NOTIFICATION_PERMISSION_DENIED_COPY: Int = R.string.service_notification_permission_denied

        private fun StoredMode.toEngine(): EffectMode = when (this) {
            StoredMode.SESSION -> EffectMode.SESSION
            StoredMode.LEGACY -> EffectMode.LEGACY
        }

        private fun EffectMode.toStored(): StoredMode = when (this) {
            EffectMode.SESSION -> StoredMode.SESSION
            EffectMode.LEGACY -> StoredMode.LEGACY
        }
    }
}
