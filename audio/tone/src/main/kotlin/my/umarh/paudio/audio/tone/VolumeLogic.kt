package my.umarh.paudio.audio.tone

import java.util.concurrent.atomic.AtomicInteger

/**
 * Pure validity check for `AudioManager.getStreamVolumeDb` curves (docs/DECISIONS.md §4). The curve is only a
 * first-order step planner, so anything suspicious means "no dB information": NaN/Inf, a decreasing curve
 * (non-monotonic), or a flat one (fixed-volume / absolute-volume sinks).
 */
object VolumeCurve {
    /**
     * [raw] is the dB per index for `min..max` (null / non-finite where the platform failed or returned -Inf
     * for mute). Returns, per index, the dB value if the whole curve is usable and that index is finite, else null.
     * The lowest indices may be mute (-Inf) without invalidating the rest of the curve.
     */
    fun validate(raw: List<Float?>): List<Float?> {
        val finite = raw.filter { it != null && it.isFinite() }.map { it!! }
        val usable = finite.size >= 2 &&
            finite.zipWithNext().all { (a, b) -> b >= a } &&
            finite.last() > finite.first()
        return raw.map { v -> if (usable && v != null && v.isFinite()) v else null }
    }
}

/**
 * Filters [android.database.ContentObserver]/broadcast callbacks down to EXTERNAL changes: our own
 * `setIndex` records the read-back as known, so the echo notifications that follow are suppressed.
 */
class ExternalChangeFilter(initial: Int) {
    private val known = AtomicInteger(initial)

    /** Called after our own write with the index read back. */
    fun onSelfWrite(effective: Int) = known.set(effective)

    /** Returns [current] if it differs from what we know (an external change), else null. */
    fun onObserved(current: Int): Int? = if (known.getAndSet(current) != current) current else null
}

/**
 * Pins STREAM_MUSIC to the calibrated index for the duration of a test and restores the user's original index
 * afterwards. Use [withLock] (restores in `finally`) or call [release] from your own finally block.
 */
class VolumeLock(private val controller: my.umarh.paudio.core.model.VolumeController) {
    private val lock = Any()
    private var original: Int? = null

    /** The index we locked to, or null when not locked. */
    @Volatile var lockedIndex: Int? = null
        private set

    /**
     * External changes away from the locked index (user keys, headset, CSD auto-lowering). Emits the observed
     * index. Nothing is emitted while unlocked.
     */
    val drift: kotlinx.coroutines.flow.Flow<Int> =
        kotlinx.coroutines.flow.flow {
            controller.changes.collect { idx ->
                val locked = lockedIndex
                if (locked != null && idx != locked) emit(idx)
            }
        }

    /** Remembers the original index (first call only), sets [index]; returns the effective (read-back) index. */
    fun lock(index: Int): Int = synchronized(lock) {
        if (original == null) original = controller.index()
        val effective = controller.setIndex(index)
        lockedIndex = effective
        effective
    }

    /** Idempotent. Restores the original index if we had changed it. */
    fun release() {
        val orig: Int?
        synchronized(lock) {
            orig = original
            original = null
            lockedIndex = null
        }
        if (orig != null) controller.setIndex(orig)
    }

    inline fun <T> withLock(index: Int, block: (effective: Int) -> T): T {
        val effective = lock(index)
        try {
            return block(effective)
        } finally {
            release()
        }
    }
}
