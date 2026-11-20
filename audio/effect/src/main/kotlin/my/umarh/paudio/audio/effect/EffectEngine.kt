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
 * Threading: every input setter and every session event is processed on [scope]'s dispatcher, which MUST be
 * single-threaded in production (serial engine thread; tests use an unconfined test dispatcher so calls run
 * inline). Callers (UI/main) therefore never block on binder calls. State is additionally guarded by one lock so
 * [acquire] (synchronous, any thread) is safe; the lock is only held for cheap operations (handle creation, the
 * slow binder call, runs OUTSIDE the lock and the result is re-validated afterwards).
 *
 * A handle is enabled iff `running && personalized && processable device && target != null && no bypass token &&
 * has control`. [acquire] disables every owned handle synchronously (P0, SPEC §2.2 #1); bypass is derived from live
 * in-process tokens only, which cannot survive process death, so a new engine never starts bypassed.
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

        /** Bumped on every release so a creation in flight for an older incarnation is discarded. */
        var epoch = 0

        /** Config last passed to the handle; null = unknown, force an apply. */
        var config: EqConfig? = null

        /** Last enabled value written; null = unknown, force a write. */
        var enabled: Boolean? = null
        var control: Boolean = true

        /** Creation was refused as a conflict; persists across device changes until [retry] (or a mode switch). */
        var creationConflict: Boolean = false
        var failed: EffectFailureKind? = null
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
    private var evicted = 0
    private var releasedIdle = 0
    private var errorCause: EngineError? = null
    private var ladder = DpLadderStep.MAIN
    private var steppedForBandCount: Int? = null
    private var sourceJob: Job? = null
    private var tickerJob: Job? = null
    private var generation = 0
    private var sourceReady = false
    private var lastMusicAt = 0L
    private var noSessionSince: Long? = null
    private var creating = false

    /** Backend creations currently running outside the lock; [acquire] waits (bounded) for them to settle. */
    private var inFlightCreates = 0
    private val createTimes = ArrayDeque<Long>()

    private val _state = MutableStateFlow<EngineState>(EngineState.Off)
    val state: StateFlow<EngineState> = _state

    private val _diagnostics = MutableStateFlow(EngineDiagnostics())
    val diagnostics: StateFlow<EngineDiagnostics> = _diagnostics

    private val _noSessionHint = MutableStateFlow(false)

    /** "Your music app isn't announcing itself..." (no session for 60 s while music plays, session mode). */
    val noSessionHint: StateFlow<Boolean> = _noSessionHint

    private val _bypassed = MutableStateFlow(false)
    override val bypassed: StateFlow<Boolean> = _bypassed

    private inline fun post(crossinline block: () -> Unit) {
        scope.launch { synchronized(lock) { block() } }
    }

    // ---------------------------------------------------------------- inputs

    /** Starts/stops the engine. Stopping releases every handle and forgets sessions; open bypass tokens stay valid. */
    fun setRunning(on: Boolean) = post {
        if (running != on) {
            running = on
            if (on) {
                errorCause = null
                ladder = DpLadderStep.MAIN
                steppedForBandCount = null
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

    /**
     * Session vs Legacy are mutually exclusive (SPEC §2.2 #14): all handles of the old mode are released first.
     * Clears a sticky CREATION_FAILED error so "Try Legacy mode" can work.
     */
    fun setMode(newMode: EngineMode) = post {
        if (mode != newMode) {
            sourceJob?.cancel()
            sourceJob = null
            generation++
            releaseAllLocked()
            mode = newMode
            errorCause = null
            if (running) startSourceLocked()
            reconcileLocked()
        }
    }

    fun setOutputDevice(d: OutputDevice?) = post { applyRouteLocked(d, target) }

    /** The resolved profile -> plan -> config for the current device; null = no profile (NoProfile state). */
    fun setTarget(cfg: EqConfig?) = post { applyRouteLocked(device, cfg) }

    /**
     * Atomic device + target change: a BT <-> wired switch never applies the old headset's curve to the new route.
     * Prefer this over the two single setters.
     */
    fun setRoute(device: OutputDevice?, target: EqConfig?) = post { applyRouteLocked(device, target) }

    private fun applyRouteLocked(d: OutputDevice?, t: EqConfig?) {
        if (device == d && target == t) return
        if (device != d && errorCause == EngineError.CREATION_FAILED) errorCause = null
        device = d
        target = t
        reconcileLocked()
    }

    /** false = "Original": handles are kept but disabled. */
    fun setPersonalized(on: Boolean) = post {
        if (personalized != on) {
            personalized = on
            reconcileLocked()
        }
    }

    /** Follows [devices] until the scope is cancelled. */
    fun bindOutputDevices(devices: OutputDevices): Job = scope.launch {
        devices.current.collect { setOutputDevice(it) }
    }

    /** User tap on Conflict/Error: release and recreate every handle, restart a failed source (DECISIONS §3). */
    fun retry() = post {
        val sourceFailed = errorCause == EngineError.SOURCE_FAILED
        errorCause = null
        for (e in sessions.values.toList()) {
            releaseHandleLocked(e)
            e.creationConflict = false
        }
        if (sourceFailed && running) startSourceLocked()
        reconcileLocked()
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
            // The DP constructor inherits the previous controller's enabled flag; until the creating thread has run
            // its explicit setEnabled(false) the new effect may process audio. Wait (bounded) for in-flight
            // creations; wait() releases the monitor so the creating thread's completion can run. On timeout we
            // proceed: the creation path re-reconciles on completion and sees tokens > 0.
            val deadline = System.nanoTime() + ACQUIRE_WAIT_MS * 1_000_000
            while (inFlightCreates > 0) {
                val remainingMs = (deadline - System.nanoTime()) / 1_000_000
                if (remainingMs <= 0) break
                try {
                    (lock as Object).wait(remainingMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            publishLocked()
        }
        return object : BypassToken {
            private val closed = AtomicBoolean(false)
            override val reason: BypassReason = reason
            override fun close() {
                if (!closed.compareAndSet(false, true)) return
                // Un-bypass is not time-critical and reconcile makes binder calls: keep it off the caller's (maybe main)
                // thread. The count drops at once so `bypassed` and a following acquire() stay exact.
                synchronized(lock) {
                    tokens--
                    publishLocked()
                }
                scope.launch { synchronized(lock) { reconcileLocked() } }
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

    private fun onSourceReady(gen: Int) = synchronized(lock) {
        if (gen == generation) {
            sourceReady = true
            reconcileLocked()
        }
    }

    private fun onSourceFailed(gen: Int) = synchronized(lock) {
        if (gen == generation) {
            errorCause = EngineError.SOURCE_FAILED
            sourceReady = true
            reconcileLocked()
        }
    }

    private fun onEvent(gen: Int, ev: SessionEvent) = synchronized(lock) {
        if (gen != generation || !running) return@synchronized
        when (ev) {
            is SessionEvent.Open -> {
                if (mode == EngineMode.SESSION && ev.session <= 0) return@synchronized
                if (mode == EngineMode.LEGACY && ev.session != 0) return@synchronized
                val existing = sessions[ev.session]
                if (existing != null) {
                    existing.openedAt = clockMs()
                    existing.pkg = ev.pkg
                } else {
                    if (sessions.size >= MAX_LIVE_SESSIONS) {
                        // Spoofing resistance: the least recently opened entry makes room (never ignore the newest).
                        sessions.values.minByOrNull { it.openedAt }?.let {
                            sessions.remove(it.session)
                            releaseHandleLocked(it)
                            evicted++
                        }
                    }
                    sessions[ev.session] = Entry(ev.session, ev.pkg, clockMs())
                }
            }
            is SessionEvent.Close -> {
                val e = sessions[ev.session]
                // A CLOSE naming a different package than the OPEN is spoofed/stale: ignore.
                if (e != null && e.pkg == ev.pkg) {
                    sessions.remove(ev.session)
                    releaseHandleLocked(e)
                }
            }
        }
        reconcileLocked()
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

    private fun tick(music: Boolean) = synchronized(lock) {
        if (!running) return@synchronized
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

        // The hint is meaningless while WE are making sound (bypass) or when nothing would be processed anyway.
        val wantHint = mode == EngineMode.SESSION && sessions.isEmpty() && music &&
            tokens == 0 && device?.isProcessable == true && target != null
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

    // ---------------------------------------------------------------- reconcile

    private fun canHoldLocked(): Boolean = running && device?.isProcessable == true && target != null

    private fun reconcileLocked() {
        val t = target
        val canHold = canHoldLocked()
        // Enabling requires ALL of these; a bypass token always wins (P0).
        val wantOn = canHold && personalized && tokens == 0
        for (e in sessions.values.toList()) {
            if (!canHold) {
                releaseHandleLocked(e)
                continue
            }
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
        pumpCreationsLocked()
    }

    // --- handle creation: serial, rate limited, binder call OUTSIDE the lock ---

    private fun needsCreation(e: Entry) = e.handle == null && !e.creationConflict

    private fun pumpCreationsLocked() {
        if (creating || errorCause != null || !canHoldLocked()) return
        if (sessions.values.none(::needsCreation)) return
        creating = true
        scope.launch { createLoop() }
    }

    private sealed interface Step {
        data class Create(val e: Entry, val epoch: Int, val cfg: EqConfig) : Step
        data class Wait(val ms: Long) : Step
        data object Done : Step
    }

    private suspend fun createLoop() {
        var cleared = false
        try {
            while (true) {
                val step = synchronized(lock) { nextStepLocked() }
                when (step) {
                    is Step.Done -> {
                        cleared = true
                        return
                    }
                    is Step.Wait -> delay(step.ms)
                    is Step.Create -> {
                        var handle: EffectHandle? = null
                        var failure: Exception? = null
                        var settled = false
                        try {
                            try {
                                handle = backend.create(step.e.session, step.cfg)
                            } catch (ex: CancellationException) {
                                throw ex
                            } catch (ex: Exception) {
                                failure = ex
                            }
                            synchronized(lock) {
                                settled = true
                                inFlightCreates--
                                (lock as Object).notifyAll()
                                finishCreationLocked(step, handle, failure)
                            }
                        } finally {
                            if (!settled) {
                                synchronized(lock) {
                                    inFlightCreates--
                                    (lock as Object).notifyAll()
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            if (!cleared) synchronized(lock) { creating = false }
        }
    }

    /** Decides the next creation; on Done `creating` is cleared in the same critical section (no missed wake-up). */
    private fun nextStepLocked(): Step {
        val e = if (errorCause != null || !canHoldLocked()) null else sessions.values.firstOrNull(::needsCreation)
        if (e == null) {
            creating = false
            return Step.Done
        }
        val now = clockMs()
        while (createTimes.isNotEmpty() && now - createTimes.first() >= CREATE_WINDOW_MS) createTimes.removeFirst()
        if (createTimes.size >= MAX_CREATES_PER_WINDOW) {
            return Step.Wait((createTimes.first() + CREATE_WINDOW_MS - now).coerceAtLeast(1))
        }
        createTimes.addLast(now)
        inFlightCreates++
        return Step.Create(e, e.epoch, target!!)
    }

    private fun finishCreationLocked(step: Step.Create, handle: EffectHandle?, failure: Exception?) {
        val e = step.e
        val valid = canHoldLocked() && sessions[e.session] === e && e.epoch == step.epoch && e.handle == null
        if (handle != null) {
            if (valid) {
                attachLocked(e, handle, step.cfg)
            } else {
                try {
                    handle.release()
                } catch (_: RuntimeException) {
                }
            }
        } else if (valid) {
            val kind = (failure as? EffectCreationException)?.kind ?: EffectFailureKind.CREATION_FAILED
            when (kind) {
                EffectFailureKind.CONFLICT -> e.creationConflict = true
                EffectFailureKind.CREATION_FAILED -> errorCause = EngineError.CREATION_FAILED
            }
        }
        reconcileLocked()
    }

    private fun attachLocked(e: Entry, h: EffectHandle, cfg: EqConfig) {
        e.handle = h
        e.config = cfg
        // Do NOT trust "created disabled": AOSP hands over the previous controller's enabled flag. null forces
        // the reconcile that follows to write the desired state (false during a bypass).
        e.enabled = null
        e.control = true
        e.job = scope.launch {
            launch { h.control.collect { onControl(e, h, it) } }
            launch { h.clamped.collect { onClamped(e, h, it) } }
            launch { h.failed.collect { onFailed(e, h, it) } }
        }
    }

    private fun onControl(e: Entry, h: EffectHandle, granted: Boolean) = synchronized(lock) {
        if (e.handle !== h || e.control == granted) return@synchronized
        e.control = granted
        // Lost or regained: whatever the effect now holds is unknown (a new controller inherits the enabled flag).
        e.enabled = null
        if (granted) e.config = null
        reconcileLocked()
    }

    private fun onFailed(e: Entry, h: EffectHandle, kind: EffectFailureKind?) = synchronized(lock) {
        if (e.handle !== h) return@synchronized
        e.failed = kind
        if (kind != null) {
            // A failed rebuild leaves a zombie (released port, writes silently skipped): drop the handle so it is
            // recreated once the cause clears (CREATION_FAILED: after a device change/retry; CONFLICT: on retry).
            if (kind == EffectFailureKind.CREATION_FAILED) errorCause = EngineError.CREATION_FAILED
            else e.creationConflict = true
            releaseHandleLocked(e)
        }
        reconcileLocked()
    }

    /**
     * Readback showed the HAL clamped a layout of [bandCount] bands: step MAIN -> FB51 -> SPEC40 (DECISIONS §1),
     * at most once per band count (N clamped handles report the same event) and only for the CURRENT plan's layout.
     */
    private fun onClamped(e: Entry, h: EffectHandle, bandCount: Int) = synchronized(lock) {
        if (e.handle !== h) return@synchronized
        val t = target ?: return@synchronized
        if (bandCount != t.bandCount || bandCount == steppedForBandCount) return@synchronized
        val next = when (ladder) {
            DpLadderStep.MAIN -> DpLadderStep.FB51
            DpLadderStep.FB51 -> DpLadderStep.SPEC40
            DpLadderStep.SPEC40 -> return@synchronized
        }
        steppedForBandCount = bandCount
        ladder = next
        publishLocked()
    }

    private fun releaseHandleLocked(e: Entry) {
        e.epoch++
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
        e.failed = null
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
            sessionsEvicted = evicted,
            sessionsReleasedIdle = releasedIdle,
        )
        _state.value = computeStateLocked()
    }

    private fun computeStateLocked(): EngineState {
        val dev = device
        val cause = errorCause
        return when {
            !running -> EngineState.Off
            tokens > 0 -> EngineState.Bypassed
            !sourceReady && cause == null -> EngineState.Starting
            dev == null || !dev.isProcessable -> EngineState.SpeakerOutput
            target == null -> EngineState.NoProfile(dev)
            cause != null -> EngineState.Error(cause)
            sessions.values.any { it.creationConflict || it.failed == EffectFailureKind.CONFLICT || (it.handle != null && !it.control) } ->
                EngineState.Conflict
            mode == EngineMode.LEGACY ->
                EngineState.Legacy(active = sessions.values.any { it.handle != null && it.enabled == true })
            else -> {
                // Only sessions that actually have a live handle, de-duplicated (several sessions of one app).
                val apps = sessions.values.filter { it.handle != null }.map { it.pkg }.distinct()
                if (apps.isEmpty()) EngineState.Waiting else EngineState.Active(apps)
            }
        }
    }

    companion object {
        const val MAX_LIVE_SESSIONS = 16
        const val IDLE_MS = 10 * 60 * 1000L
        const val NO_SESSION_HINT_MS = 60_000L
        const val TICK_MS = 5_000L

        /** Upper bound [acquire] waits for an in-flight handle creation to apply its disable. */
        const val ACQUIRE_WAIT_MS = 250L

        /** Handle-creation rate limit: at most this many creations per window (burst 16, sustained 4/s). */
        const val MAX_CREATES_PER_WINDOW = 16
        const val CREATE_WINDOW_MS = 4_000L
    }
}
