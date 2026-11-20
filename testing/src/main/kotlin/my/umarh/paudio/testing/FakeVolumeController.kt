package my.umarh.paudio.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import my.umarh.paudio.core.model.VolumeController

/** In-memory stream volume. [indexDb] is a simple linear map (−[dbRange] dB at 0, 0 dB at max). */
class FakeVolumeController(
    override val maxIndex: Int = 15,
    initialIndex: Int = 7,
    private val dbRange: Float = 60f,
) : VolumeController {
    private var current = initialIndex.coerceIn(0, maxIndex)
    private val _changes = MutableSharedFlow<Int>(extraBufferCapacity = 64)

    /** Highest index [setIndex] will accept, to simulate safe-media-volume refusing (SPEC trap 12). */
    var safeLimit: Int = maxIndex

    val setCalls: MutableList<Int> = mutableListOf()

    override fun index(): Int = current

    /** Returns the index actually in effect after the call. */
    override fun setIndex(i: Int): Int {
        setCalls += i
        current = i.coerceIn(0, minOf(maxIndex, safeLimit))
        _changes.tryEmit(current)
        return current
    }

    override fun indexDb(i: Int): Float? =
        if (i in 0..maxIndex) dbRange * (i.toFloat() / maxIndex - 1f) else null

    override val changes: Flow<Int> = _changes

    /** Simulates the user/headset/CSD changing the volume behind the app's back. */
    fun simulateExternalChange(i: Int) {
        current = i.coerceIn(0, maxIndex)
        _changes.tryEmit(current)
    }
}
