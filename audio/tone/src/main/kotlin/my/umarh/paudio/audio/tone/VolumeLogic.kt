package my.umarh.paudio.audio.tone

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import my.umarh.paudio.core.model.VolumeController

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
 * Filters [android.database.ContentObserver]/broadcast callbacks down to EXTERNAL changes. Around our own write:
 * [expectSelf] BEFORE `setStreamVolume` (an observer may fire before it returns), [onSelfWrite] with the
 * read-back afterwards. All state is compared under one lock.
 */
class ExternalChangeFilter(initial: Int) {
    private val lock = Any()
    private var known = initial
    private var pendingSelf: Int? = null

    fun expectSelf(requested: Int) = synchronized(lock) { pendingSelf = requested }

    fun onSelfWrite(effective: Int) = synchronized(lock) {
        known = effective
        pendingSelf = null
    }

    /** Returns [current] if it is an external change, else null. */
    fun onObserved(current: Int): Int? = synchronized(lock) {
        if (pendingSelf == current) {
            known = current
            return null
        }
        if (known == current) return null
        known = current
        current
    }
}

/**
 * Pins STREAM_MUSIC to the calibrated index for the duration of a test and restores the user's original index
 * afterwards. Use [withLock] (restores in `finally`) or call [release] from your own finally block.
 *
 * Hearing safety: [release] restores the ORIGINAL index, but never raises the volume when something else (user,
 * system, CSD auto-lowering) changed it to below the original while locked.
 */
class VolumeLock(private val controller: VolumeController) {
    private val lock = Any()
    private var original: Int? = null

    /** The index we locked to, or null when not locked. */
    @Volatile var lockedIndex: Int? = null
        private set

    /**
     * Deviations from the locked index (user keys, headset, CSD auto-lowering): emits the observed index. If the
     * index already differs when collection starts, that is emitted first (the underlying flow has no replay).
     * Nothing is emitted while unlocked.
     */
    val drift: Flow<Int> = channelFlow {
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            controller.changes.collect { idx ->
                val locked = lockedIndex
                if (locked != null && idx != locked) send(idx)
            }
        }
        val locked = lockedIndex
        val now = controller.index()
        if (locked != null && now != locked) send(now)
        job.join()
    }

    /** Remembers the original index (first call only), sets [index]; returns the effective (read-back) index. */
    fun lock(index: Int): Int = synchronized(lock) {
        if (original == null) original = controller.index()
        val effective = controller.setIndex(index)
        lockedIndex = effective
        effective
    }

    /** Idempotent. Restores the original index (see class doc for the safety exception). */
    fun release() {
        val orig: Int?
        val locked: Int?
        synchronized(lock) {
            orig = original
            locked = lockedIndex
            original = null
            lockedIndex = null
        }
        if (orig == null) return
        val now = controller.index()
        val loweredExternally = locked != null && now != locked && now < orig
        if (!loweredExternally) controller.setIndex(orig)
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
