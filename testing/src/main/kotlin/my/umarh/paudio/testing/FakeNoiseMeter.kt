package my.umarh.paudio.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import my.umarh.paudio.core.model.NoiseMeter

/** Test-driven noise meter: tests push dBFS values with [emit]. Counts active collectors. */
class FakeNoiseMeter : NoiseMeter {
    private val flow = MutableSharedFlow<Float>(extraBufferCapacity = 64)

    /** Number of times [levels] was requested (e.g. to assert the meter stops when the page leaves). */
    var levelsRequests: Int = 0
        private set

    override fun levels(): Flow<Float> {
        levelsRequests++
        return flow
    }

    fun emit(dbfs: Float) {
        flow.tryEmit(dbfs)
    }
}
