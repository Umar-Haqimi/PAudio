package my.umarh.paudio.audio.effect

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import my.umarh.paudio.core.dsp.DpLadderStep
import my.umarh.paudio.core.model.BypassController
import my.umarh.paudio.core.model.BypassReason
import my.umarh.paudio.core.model.BypassToken
import my.umarh.paudio.core.model.EffectBackend
import my.umarh.paudio.core.model.EffectCreationException
import my.umarh.paudio.core.model.EffectFailureKind
import my.umarh.paudio.core.model.EffectHandle
import my.umarh.paudio.core.model.EqConfig
import my.umarh.paudio.core.model.OutputDevice
import my.umarh.paudio.core.model.OutputDevices
import my.umarh.paudio.core.model.SessionEvent
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The system-wide effect engine (SPEC §4 item 8, F19-F22). Pure Kotlin over [EffectBackend] + flows.
 *
 * All mutable state is guarded by one lock; every public method is thread-safe. The engine never creates a
 * handle without a target config and never enables one unless
 * `running && personalized && processable device && target != null && no bypass token && has control`.
 *
 * It also is the [BypassController] (P0, SPEC §2.2 #1): [acquire] disables every owned handle synchronously.
 * Bypass is derived from live in-process tokens only; they cannot survive process death, so a freshly
 * created engine never starts bypassed.
 */
class EffectEngine(
    private val backend: EffectBackend,
    private val sourceFactory: SessionSourceFactory,
    private val musicActivity: MusicActivity,
    private val scope: CoroutineScope,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
) : BypassController {

    private class Entry(val session: Int, var pkg: String, var openedAt: Long) {
        var handle: EffectHandle? = null
        var job: Job? = null

        /** Config last passed to the handle; null = unknown, force an apply. */
        var config: EqConfig? = null

        /** Last enabled value written; null = unknown, force a write. */
        var enabled: Boolean? = null
        var control: Boolean = true
        var creationConflict: Boolean = false
    }

    private val lock = Any()

    // --- inputs (guarded by lock) ---
    private var mode = EngineMode.SESSION
    private var running = false
    private var device: OutputDevice? = null
    private var target: EqConfig? = null
    private var personalized = true
    private var tokens = 0

    // --- internal state (guarded by lock) ---
    private val sessions = LinkedHashMap<Int, Entry>()
    private var ignoredOverCap = 0
    private var releasedIdle = 0
    private var errorCause: EngineError? = null
    private var ladder = DpLadderStep.MAIN
    private var sourceJob: Job? = null
    private var tickerJob: Job? = null
    private var generation = 0
    private var sourceReady = false
    private var lastMusicAt = 0L
    private var noSessionSince: Long? = null

    private val _state = MutableStateFlow<EngineState>(EngineState.Off)
    val state: StateFlow<EngineState> = _state

    private val _diagnostics = MutableStateFlow(EngineDiagnostics())
    val diagnostics: StateFlow<EngineDiagnostics> = _diagnostics

    private val _noSessionHint = MutableStateFlow(false)

    /** "Your music app isn't announcing itself..." (no session for 60 s while music plays, session mode). */
    val noSessionHint: StateFlow<Boolean> = _noSessionHint

    private val _bypassed = MutableStateFlow(false)
    override val bypassed: StateFlow<Boolean> = _bypassed

    // ---------------------------------------------------------------- inputs

    /** Starts/stops the engine. Stopping releases every handle and forgets sessions; open bypass tokens stay valid. */
    fun setRunning(on: Boolean) {
        synchronized(lock) {
        if (running == on) return
        running = on
        if (on) {
            errorCause = null
            lastMusicAt = clockMs()
            startSourceLocked()
            startTickerLocked()
        } else {
            stopJobsLocked()
            releaseAllLocked()
            noSessionSince = null
            _noSessionHint.value = false
        }
        reconcileLocked()
    }
    }

    /** Session vs Legacy are mutually exclusive (SPEC §2.2 #14): all handles of the old mode are released first. */
    fun setMode(newMode: EngineMode) {
        synchronized(lock) {
        if (mode == newMode) return
        sourceJob?.cancel()
        sourceJob = null
        generation++
        releaseAllLocked()
        mode = newMode
        if (running) startSourceLocked()
        reconcileLocked()
    }
    }

    fun setOutputDevice(d: OutputDevice?) {
        synchronized(lock) {
        if (device == d) return
        device = d
        reconcileLocked()
    }
    }

    /** The resolved profile -> plan -> config for the current device; null = no profile (NoProfile state). */
    fun setTarget(cfg: EqConfig?) {
        synchronized(lock) {
        if (target == cfg) return
        target = cfg
        reconcileLocked()
    }
    }

    /** false = "Original": handles are kept but disabled. */
    fun setPersonalized(on: Boolean) {
        synchronized(lock) {
        if (personalized == on) return
        personalized = on
        reconcileLocked()
    }
    }

    /** Follows [devices] until the scope is cancelled. */
    fun bindOutputDevices(devices: OutputDevices): Job = scope.launch {
        devices.current.collect { setOutputDevice(it) }
    }

    /** User tap on Conflict/Error: release and recreate every handle (DECISIONS §3). */
    fun retry() {
        synchronized(lock) {
        errorCause = null
        for (e in sessions.values.toList()) {
            releaseHandleLocked(e)
            e.creationConflict = false
        }
        reconcileLocked()
    }
    }

    // ---------------------------------------------------------------- bypass (P0)

    override fun acquire(reason: BypassReason): BypassToken {
        synchronized(lock) {
            tokens++
            // Synchronous: every owned handle is off before we return.
            for (e in sessions.values) {
                val h = e.handle ?: continue
                try {
                    h.setEnabled(false)
                } catch (_: RuntimeException) {
                }
                e.enabled = false
            }
            publishLocked()
        }
        return object : BypassToken {
            private val closed = AtomicBoolean(false)
            override val reason: BypassReason = reason
            override fun close() {
                if (!closed.compareAndSet(false, true)) return
                synchronized(lock) {
                    tokens--
                    reconcileLocked()
                }
            }
        }
    }

    // ---------------------------------------------------------------- sessions

    private fun startSourceLocked() {
        sourceJob?.cancel()
        sourceReady = false
        val gen = ++generation
        val source = try {
            sourceFactory.create(mode)
        } catch (e: Exception) {
            errorCause = EngineError.SOURCE_FAILED
            return
        }
        sourceJob = scope.launch {
            try {
                source.events
                    .onStart { onSourceReady(gen) }
                    .collect { onEvent(gen, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onSourceFailed(gen)
            }
        }
    }

    private fun onSourceReady(gen: Int) {
        synchronized(lock) {
        if (gen != generation) return
        sourceReady = true
        reconcileLocked()
    }
    }

    private fun onSourceFailed(gen: Int) {
        synchronized(lock) {
        if (gen != generation) return
        errorCause = EngineError.SOURCE_FAILED
        sourceReady = true
        reconcileLocked()
    }
    }

    private fun onEvent(gen: Int, ev: SessionEvent) {
        synchronized(lock) {
        if (gen != generation || !running) return
        when (ev) {
            is SessionEvent.Open -> {
                if (mode == EngineMode.SESSION && ev.session <= 0) return
                if (mode == EngineMode.LEGACY && ev.session != 0) return
                val existing = sessions[ev.session]
                if (existing != null) {
                    existing.openedAt = clockMs()
                    existing.pkg = ev.pkg
                } else if (sessions.size >= MAX_LIVE_SESSIONS) {
                    ignoredOverCap++
                } else {
                    sessions[ev.session] = Entry(ev.session, ev.pkg, clockMs())
                }
            }
            is SessionEvent.Close -> sessions.remove(ev.session)?.let { releaseHandleLocked(it) }
        }
        reconcileLocked()
    }
    }

    // ---------------------------------------------------------------- ticker: idle sweep + "no session" hint

    private fun startTickerLocked() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                val music = try {
                    musicActivity.isMusicActive()
                } catch (_: Exception) {
                    false
                }
                tick(music)
            }
        }
    }

    private fun tick(music: Boolean) {
        synchronized(lock) {
        if (!running) return
        val now = clockMs()
        if (music) lastMusicAt = now
        var changed = false

        // Idle: an OPEN older than IDLE_MS with no re-OPEN, while no music has been heard for IDLE_MS either.
        // (Players only announce on session creation, so activity is judged by the global music flag.)
        if (mode == EngineMode.SESSION && !music) {
            val it = sessions.values.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (now - maxOf(e.openedAt, lastMusicAt) > IDLE_MS) {
                    it.remove()
                    releaseHandleLocked(e)
                    releasedIdle++
                    changed = true
                }
            }
        }

        val wantHint = mode == EngineMode.SESSION && sessions.isEmpty() && music
        if (wantHint) {
            val since = noSessionSince ?: now.also { noSessionSince = it }
            val hint = now - since >= NO_SESSION_HINT_MS
            if (_noSessionHint.value != hint) _noSessionHint.value = hint
        } else {
            noSessionSince = null
            if (_noSessionHint.value) _noSessionHint.value = false
        }
        if (changed) reconcileLocked()
    }
    }

    // ---------------------------------------------------------------- reconcile

    private fun reconcileLocked() {
        val t = target
        val dev = device
        val canHold = running && dev != null && dev.isProcessable && t != null
        // Enabling requires ALL of these; a bypass token always wins (P0).
        val wantOn = canHold && personalized && tokens == 0
        for (e in sessions.values.toList()) {
            if (!canHold) {
                releaseHandleLocked(e)
                continue
            }
            if (e.handle == null && errorCause == null && !e.creationConflict) createHandleLocked(e, t!!)
            val h = e.handle ?: continue
            if (e.config != t) {
                try {
                    h.apply(t!!)
                } catch (_: RuntimeException) {
                }
                e.config = t
            }
            if (!e.control && wantOn) continue
            if (e.enabled != wantOn) {
                try {
                    h.setEnabled(wantOn)
                } catch (_: RuntimeException) {
                }
                e.enabled = wantOn
            }
        }
        publishLocked()
    }

    private fun createHandleLocked(e: Entry, cfg: EqConfig) {
        try {
            val h = backend.create(e.session, cfg)
            e.handle = h
            e.config = cfg
            e.enabled = false // handles are created disabled
            e.control = true
            e.job = scope.launch {
                launch { h.control.collect { onControl(e, h, it) } }
                launch { h.clamped.collect { if (it) onClamped(e, h) } }
            }
        } catch (ex: EffectCreationException) {
            when (ex.kind) {
                EffectFailureKind.CONFLICT -> e.creationConflict = true
                EffectFailureKind.CREATION_FAILED -> errorCause = EngineError.CREATION_FAILED
            }
        } catch (ex: RuntimeException) {
            errorCause = EngineError.CREATION_FAILED
        }
    }

    private fun onControl(e: Entry, h: EffectHandle, granted: Boolean) {
        synchronized(lock) {
        if (e.handle !== h || e.control == granted) return
        e.control = granted
        // Lost or regained: whatever the effect now holds is unknown (a new controller inherits the enabled flag).
        e.enabled = null
        if (granted) e.config = null
        reconcileLocked()
    }
    }

    /** Readback showed the HAL clamped our layout: step down MAIN -> FB51 -> SPEC40 (DECISIONS §1). */
    private fun onClamped(e: Entry, h: EffectHandle) {
        synchronized(lock) {
        if (e.handle !== h) return
        val next = when (ladder) {
            DpLadderStep.MAIN -> DpLadderStep.FB51
            DpLadderStep.FB51 -> DpLadderStep.SPEC40
            DpLadderStep.SPEC40 -> return
        }
        ladder = next
        publishLocked()
    }
    }

    private fun releaseHandleLocked(e: Entry) {
        e.job?.cancel()
        e.job = null
        try {
            e.handle?.release()
        } catch (_: RuntimeException) {
        }
        e.handle = null
        e.config = null
        e.enabled = null
        e.control = true
    }

    private fun releaseAllLocked() {
        for (e in sessions.values) releaseHandleLocked(e)
        sessions.clear()
    }

    private fun stopJobsLocked() {
        sourceJob?.cancel()
        sourceJob = null
        tickerJob?.cancel()
        tickerJob = null
        generation++
        sourceReady = false
    }

    private fun publishLocked() {
        _bypassed.value = tokens > 0
        _diagnostics.value = EngineDiagnostics(
            ladder = ladder,
            bandCount = target?.bandCount ?: 0,
            liveSessions = sessions.size,
            sessionsIgnoredOverCap = ignoredOverCap,
            sessionsReleasedIdle = releasedIdle,
        )
        _state.value = computeStateLocked()
    }

    private fun computeStateLocked(): EngineState {
        val dev = device
        return when {
            !running -> EngineState.Off
            tokens > 0 -> EngineState.Bypassed
            !sourceReady && errorCause == null -> EngineState.Starting
            dev == null || !dev.isProcessable -> EngineState.SpeakerOutput
            target == null -> EngineState.NoProfile(dev)
            errorCause != null -> EngineState.Error(errorCause!!)
            sessions.values.any { it.creationConflict || (it.handle != null && !it.control) } -> EngineState.Conflict
            mode == EngineMode.LEGACY ->
                EngineState.Legacy(active = sessions.values.any { it.handle != null && it.enabled == true })
            sessions.isEmpty() -> EngineState.Waiting
            else -> EngineState.Active(sessions.values.map { it.pkg })
        }
    }

    companion object {
        const val MAX_LIVE_SESSIONS = 16
        const val IDLE_MS = 10 * 60 * 1000L
        const val NO_SESSION_HINT_MS = 60_000L
        const val TICK_MS = 5_000L
    }
}
