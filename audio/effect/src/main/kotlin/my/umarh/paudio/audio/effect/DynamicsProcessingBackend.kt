package my.umarh.paudio.audio.effect

import android.util.Log
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

/**
 * DynamicsProcessing-backed [EffectBackend]. All band writes of all handles go through ONE dedicated worker
 * thread (never the caller's/main thread), with >= [MIN_WRITE_SPACING_MS] between write batches.
 *
 * Nothing sensitive is ever logged: no gains, curves, cutoffs or package names.
 */
@Singleton
class DynamicsProcessingBackend internal constructor(
    private val portFactory: DpPortFactory,
    private val minSpacingMs: Long,
    private val worker: ScheduledExecutorService,
) : EffectBackend {

    @Inject constructor() : this(PlatformDpPortFactory, MIN_WRITE_SPACING_MS, newWorker())

    private val lastBatchEndNanos = AtomicLong(0L)

    override fun create(session: Int, cfg: EqConfig): EffectHandle {
        val port = createPort(session, cfg)
        return DpEffectHandle(session, cfg, port, this)
    }

    /** First attempt as-is; any non-conflict failure retries once with 1 MBC passthrough band (DECISIONS §3). */
    private fun createPort(session: Int, cfg: EqConfig): DpPort {
        try {
            return portFactory.create(EFFECT_PRIORITY, session, cfg, false)
        } catch (e: UnsupportedOperationException) {
            Log.w(TAG, "create refused: conflict (${e.javaClass.simpleName})")
            throw EffectCreationException(EffectFailureKind.CONFLICT, cause = e)
        } catch (e: RuntimeException) {
            Log.w(TAG, "create failed (${e.javaClass.simpleName}); retrying with MBC passthrough")
        }
        try {
            return portFactory.create(EFFECT_PRIORITY, session, cfg, true)
        } catch (e: UnsupportedOperationException) {
            throw EffectCreationException(EffectFailureKind.CONFLICT, cause = e)
        } catch (e: RuntimeException) {
            Log.w(TAG, "create failed after retry (${e.javaClass.simpleName})")
            throw EffectCreationException(EffectFailureKind.CREATION_FAILED, cause = e)
        }
    }

    internal fun rebuild(session: Int, cfg: EqConfig): DpPort = createPort(session, cfg)

    /** Schedules [task] on the worker no earlier than [minSpacingMs] after the previous batch ended. */
    internal fun submit(task: () -> Unit) {
        val last = lastBatchEndNanos.get()
        val delay = if (last == 0L) 0L else (minSpacingMs - (System.nanoTime() - last) / 1_000_000).coerceAtLeast(0)
        try {
            worker.schedule({
                try {
                    task()
                } catch (e: Throwable) {
                    Log.w(TAG, "worker task failed (${e.javaClass.simpleName})")
                } finally {
                    lastBatchEndNanos.set(System.nanoTime())
                }
            }, delay, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            // worker shut down: nothing to do
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
 * returns); `apply` is coalesced and written by the worker, diffing against what is already on the effect.
 */
internal class DpEffectHandle(
    private val session: Int,
    initial: EqConfig,
    initialPort: DpPort,
    private val backend: DynamicsProcessingBackend,
) : EffectHandle {

    private val portLock = Any()
    private var port: DpPort = initialPort
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
    private val _clamped = MutableStateFlow(false)
    override val clamped: StateFlow<Boolean> = _clamped

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

    private fun onControlStatus(from: DpPort, granted: Boolean) {
        if (released.get() || from !== port) return
        if (granted) {
            // Regained: whatever the other controller left is unknown; rewrite everything (DECISIONS §3).
            val last = synchronized(portLock) {
                dirty = true
                needsReadback = true
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

    /** Band count changed (ladder step): the effect must be rebuilt, PreEQ size is fixed at construction. */
    private fun rebuildWith(cfg: EqConfig) {
        synchronized(portLock) {
            if (released.get()) return
            val enabled = synchronized(stateLock) { desiredEnabled }
            val fresh = try {
                port.release()
                backend.rebuild(session, cfg)
            } catch (e: RuntimeException) {
                _control.value = false
                dirty = true
                return
            }
            port = fresh
            installListener(fresh)
            applied = cfg
            dirty = false
            needsReadback = true
            _control.value = safeHasControl(fresh)
            if (enabled) {
                try {
                    fresh.setEnabled(true)
                } catch (_: RuntimeException) {
                    _control.value = false
                }
            }
        }
    }

    private inline fun locked(block: () -> Unit) {
        synchronized(portLock) {
            if (released.get()) return
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
                    if (released.get()) return
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
        _clamped.value = mismatch
        if (mismatch) Log.w(TAG, "readback mismatch: HAL clamped the band layout")
    }

    override fun setEnabled(on: Boolean) {
        if (released.get()) return
        synchronized(stateLock) { desiredEnabled = on }
        try {
            synchronized(portLock) {
                if (released.get()) return
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
