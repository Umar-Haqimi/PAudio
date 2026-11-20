package my.umarh.paudio.audio.effect

import android.media.audiofx.AudioEffect
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import my.umarh.paudio.core.model.EffectBackend
import my.umarh.paudio.core.model.EffectCreationException
import my.umarh.paudio.core.model.EffectFailureKind
import my.umarh.paudio.core.model.EffectHandle
import my.umarh.paudio.core.model.EqConfig
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/** DECISIONS §3: wins over normal (0) users of DynamicsProcessing, does not out-bid `Int.MAX_VALUE` EQ apps. */
const val EFFECT_PRIORITY = 100

/** Minimum gap between two write batches on the worker (DECISIONS §3). */
const val MIN_WRITE_SPACING_MS = 50L

/** Readback tolerance (DECISIONS §1). */
internal const val READBACK_TOLERANCE = 0.05f

private const val TAG = "PAudio.DP"

/** True if the platform lists a DynamicsProcessing effect at all (DECISIONS §19: UOE without it is no "conflict"). */
internal fun platformHasDynamicsProcessing(): Boolean = try {
    AudioEffect.queryEffects()?.any { it.type == AudioEffect.EFFECT_TYPE_DYNAMICS_PROCESSING } ?: true
} catch (_: Throwable) {
    true // can't tell: assume present so the conflict path (retryable) is used
}

/**
 * DynamicsProcessing-backed [EffectBackend]. All band writes of all handles go through ONE dedicated worker
 * thread (never the caller's/main thread), with >= [MIN_WRITE_SPACING_MS] between write batches, measured at
 * execution time.
 *
 * Every created/rebuilt effect is explicitly set to the desired enabled state (default off) before it is handed
 * out: AOSP gives a new controller the previous controller's enabled flag (DECISIONS §3), which would otherwise
 * let our EQ process PAudio's own test audio during a bypass (SPEC §2.2 #1).
 *
 * Nothing sensitive is ever logged: no gains, curves, cutoffs or package names.
 */
@Singleton
class DynamicsProcessingBackend internal constructor(
    private val portFactory: DpPortFactory,
    private val minSpacingMs: Long,
    private val worker: ScheduledExecutorService,
    private val dpAvailable: () -> Boolean,
) : EffectBackend {

    @Inject constructor() : this(PlatformDpPortFactory, MIN_WRITE_SPACING_MS, newWorker(), ::platformHasDynamicsProcessing)

    private val lastBatchEndNanos = AtomicLong(0L)

    override fun create(session: Int, cfg: EqConfig): EffectHandle {
        val port = createPort(session, cfg, enabled = false)
        return DpEffectHandle(session, cfg, port, this)
    }

    /**
     * First attempt as-is; a non-conflict failure retries once with 1 MBC passthrough band (DECISIONS §3).
     * `UnsupportedOperationException` is a [EffectFailureKind.CONFLICT] only if the platform has a DP effect.
     * The returned port has had `setEnabled([enabled])` applied.
     */
    internal fun createPort(session: Int, cfg: EqConfig, enabled: Boolean): DpPort {
        val port = try {
            portFactory.create(EFFECT_PRIORITY, session, cfg, false)
        } catch (e: UnsupportedOperationException) {
            throw uoeFailure(e)
        } catch (e: RuntimeException) {
            Log.w(TAG, "create failed (${e.javaClass.simpleName}); retrying with MBC passthrough")
            try {
                portFactory.create(EFFECT_PRIORITY, session, cfg, true)
            } catch (e2: UnsupportedOperationException) {
                throw uoeFailure(e2)
            } catch (e2: RuntimeException) {
                Log.w(TAG, "create failed after retry (${e2.javaClass.simpleName})")
                throw EffectCreationException(EffectFailureKind.CREATION_FAILED, cause = e2)
            }
        }
        try {
            port.setEnabled(enabled)
        } catch (e: RuntimeException) {
            runCatching { port.release() }
            throw EffectCreationException(EffectFailureKind.CONFLICT, cause = e)
        }
        return port
    }

    private fun uoeFailure(e: UnsupportedOperationException): EffectCreationException {
        val kind = if (dpAvailable()) EffectFailureKind.CONFLICT else EffectFailureKind.CREATION_FAILED
        Log.w(TAG, "create refused: $kind (${e.javaClass.simpleName})")
        return EffectCreationException(kind, cause = e)
    }

    /** Runs [task] on the worker, at least [minSpacingMs] after the previous batch ended (checked at execution). */
    internal fun submit(task: () -> Unit) {
        try {
            worker.schedule({ runSpaced(task) }, 0, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            // worker shut down: nothing to do
        }
    }

    private fun runSpaced(task: () -> Unit) {
        val last = lastBatchEndNanos.get()
        if (last != 0L) {
            val wait = minSpacingMs - (System.nanoTime() - last) / 1_000_000
            if (wait > 0) {
                try {
                    worker.schedule({ runSpaced(task) }, wait, TimeUnit.MILLISECONDS)
                } catch (_: RejectedExecutionException) {
                }
                return
            }
        }
        try {
            task()
        } catch (e: Throwable) {
            Log.w(TAG, "worker task failed (${e.javaClass.simpleName})")
        } finally {
            lastBatchEndNanos.set(System.nanoTime())
        }
    }

    internal companion object {
        fun newWorker(): ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "paudio-dp-writer").apply { isDaemon = true }
        }
    }
}

/**
 * One effect instance. `setEnabled` runs synchronously on the caller (P0 bypass must be effective when it
 * returns) and never waits for a rebuild; `apply` is coalesced and written by the worker, diffing against what is
 * already on the effect.
 */
internal class DpEffectHandle(
    private val session: Int,
    initial: EqConfig,
    initialPort: DpPort,
    private val backend: DynamicsProcessingBackend,
) : EffectHandle {

    private val portLock = Any()

    @Volatile private var port: DpPort = initialPort

    /** True between releasing the old port and installing the rebuilt one; setEnabled only records intent then. */
    private var rebuilding = false
    private val released = AtomicBoolean(false)

    // What is currently programmed on the effect (guarded by portLock).
    private var applied: EqConfig = initial
    private var dirty = false
    private var needsReadback = true

    // Latest requested config, coalescing slot (guarded by stateLock).
    private val stateLock = Any()
    private var pending: EqConfig? = null
    private var scheduled = false
    private var desiredEnabled = false

    private val _control = MutableStateFlow(true)
    override val control: StateFlow<Boolean> = _control
    private val _clamped = MutableSharedFlow<Int>(extraBufferCapacity = 16)
    override val clamped: Flow<Int> = _clamped
    private val _failed = MutableStateFlow<EffectFailureKind?>(null)
    override val failed: StateFlow<EffectFailureKind?> = _failed

    init {
        installListener(initialPort)
        _control.value = safeHasControl(initialPort)
        schedule()
    }

    private fun installListener(p: DpPort) {
        try {
            p.setControlListener { granted -> onControlStatus(p, granted) }
        } catch (_: RuntimeException) {
            // best effort; the setters still detect control loss
        }
    }

    private fun safeHasControl(p: DpPort): Boolean = try {
        p.hasControl()
    } catch (_: RuntimeException) {
        false
    }

    private fun desired(): Boolean = synchronized(stateLock) { desiredEnabled }

    private fun onControlStatus(from: DpPort, granted: Boolean) {
        if (released.get() || from !== port) return
        if (granted) {
            // Regained: a new controller inherits the previous controller's enabled flag, so re-assert OUR desired
            // state immediately (no window where a bypassed handle processes audio), then rewrite everything.
            val last = synchronized(portLock) {
                if (from !== port || rebuilding) return
                dirty = true
                needsReadback = true
                try {
                    from.setEnabled(desired())
                } catch (_: RuntimeException) {
                }
                applied
            }
            _control.value = true
            apply(synchronized(stateLock) { pending } ?: last)
        } else {
            _control.value = false
        }
    }

    override fun apply(cfg: EqConfig) {
        if (released.get()) return
        synchronized(stateLock) { pending = cfg }
        schedule()
    }

    private fun schedule() {
        synchronized(stateLock) {
            if (scheduled) return
            scheduled = true
        }
        backend.submit(::runApply)
    }

    private fun runApply() {
        val cfg = synchronized(stateLock) {
            scheduled = false
            pending.also { pending = null }
        }
        if (released.get()) return
        try {
            if (cfg != null) write(cfg)
            if (synchronized(portLock) { needsReadback } && !released.get()) readback()
        } catch (e: UnsupportedOperationException) {
            lostControl()
        } catch (e: IllegalStateException) {
            lostControl()
        } catch (e: RuntimeException) {
            Log.w(TAG, "write failed (${e.javaClass.simpleName})")
            synchronized(portLock) { dirty = true }
        }
        // A newer config may have arrived while we were writing.
        if (synchronized(stateLock) { pending != null }) schedule()
    }

    private fun lostControl() {
        synchronized(portLock) {
            dirty = true
            needsReadback = true
        }
        _control.value = false
    }

    private fun write(cfg: EqConfig) {
        val cur = synchronized(portLock) { applied }
        if (cfg.bandCount != cur.bandCount) {
            rebuildWith(cfg)
            return
        }
        val full = synchronized(portLock) { dirty }
        if (full || cfg.preampDb != cur.preampDb) {
            for (ch in 0 until EqConfig.CHANNELS) locked { port.setInputGain(ch, cfg.preampDb) }
        }
        if (full || cfg.limiterEnabled != cur.limiterEnabled) {
            for (ch in 0 until EqConfig.CHANNELS) locked { port.setLimiterEnabled(ch, cfg.limiterEnabled) }
        }
        var wrote = full
        // channel 0 first, then channel 1 (DECISIONS §3)
        for (ch in 0 until EqConfig.CHANNELS) {
            val newGains = cfg.channelGainsDb[ch]
            val oldGains = cur.channelGainsDb[ch]
            for (k in 0 until cfg.bandCount) {
                if (full || cfg.bandCutoffsHz[k] != cur.bandCutoffsHz[k] || newGains[k] != oldGains[k]) {
                    if (released.get()) return
                    locked { port.setPreEqBand(ch, k, DpBand(cfg.bandCutoffsHz[k], newGains[k])) }
                    wrote = true
                }
            }
        }
        synchronized(portLock) {
            applied = cfg
            dirty = false
            if (wrote) needsReadback = true
        }
    }

    /**
     * Band count changed (ladder step): the effect must be rebuilt, PreEQ size is fixed at construction.
     * The slow construction runs WITHOUT [portLock] so `setEnabled` (bypass!) never waits for it; the desired
     * enabled state is read again when the fresh port is installed and ALWAYS written.
     */
    private fun rebuildWith(cfg: EqConfig) {
        synchronized(portLock) {
            if (released.get()) return
            rebuilding = true
            try {
                port.release()
            } catch (_: RuntimeException) {
            }
        }
        val fresh = try {
            backend.createPort(session, cfg, enabled = desired())
        } catch (e: EffectCreationException) {
            failRebuild(e.kind)
            return
        } catch (e: RuntimeException) {
            failRebuild(EffectFailureKind.CREATION_FAILED)
            return
        }
        synchronized(portLock) {
            if (released.get()) {
                runCatching { fresh.release() }
                return
            }
            port = fresh
            installListener(fresh)
            applied = cfg
            dirty = false
            needsReadback = true
            rebuilding = false
            _failed.value = null
            _control.value = safeHasControl(fresh)
            try {
                fresh.setEnabled(desired())
            } catch (_: RuntimeException) {
                _control.value = false
            }
        }
    }

    private fun failRebuild(kind: EffectFailureKind) {
        synchronized(portLock) { dirty = true }
        _failed.value = kind
        _control.value = false
    }

    private inline fun locked(block: () -> Unit) {
        synchronized(portLock) {
            if (released.get() || rebuilding) return
            block()
        }
    }

    /** DECISIONS §1: 3 bands per channel (first, middle, last); mismatch beyond 0.05 means the HAL clamped. */
    private fun readback() {
        val cfg = synchronized(portLock) { applied }
        val n = cfg.bandCount
        val idx = intArrayOf(0, n / 2, n - 1).distinct()
        var mismatch = false
        for (ch in 0 until EqConfig.CHANNELS) {
            for (k in idx) {
                val got = synchronized(portLock) {
                    if (released.get() || rebuilding) return
                    port.getPreEqBand(ch, k)
                }
                if (abs(got.cutoffHz - cfg.bandCutoffsHz[k]) > READBACK_TOLERANCE ||
                    abs(got.gainDb - cfg.channelGainsDb[ch][k]) > READBACK_TOLERANCE
                ) {
                    mismatch = true
                }
            }
        }
        synchronized(portLock) { needsReadback = false }
        if (mismatch) {
            Log.w(TAG, "readback mismatch: HAL clamped the band layout")
            _clamped.tryEmit(cfg.bandCount)
        }
    }

    override fun setEnabled(on: Boolean) {
        if (released.get()) return
        synchronized(stateLock) { desiredEnabled = on }
        try {
            synchronized(portLock) {
                if (released.get() || rebuilding) return
                port.setEnabled(on)
            }
        } catch (e: UnsupportedOperationException) {
            lostControl()
        } catch (e: IllegalStateException) {
            lostControl()
        }
    }

    override fun release() {
        if (!released.compareAndSet(false, true)) return
        synchronized(portLock) {
            try {
                port.release()
            } catch (_: RuntimeException) {
            }
        }
    }
}
