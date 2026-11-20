package my.umarh.paudio.audio.tone

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import my.umarh.paudio.testing.FakeVolumeController
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VolumeLogicTest {
    private val ninf = Float.NEGATIVE_INFINITY

    @Test
    fun curveWithMuteAtBottomAndMonotonicRestIsUsable() {
        val r = VolumeCurve.validate(listOf(ninf, -40f, -30f, -20f, -10f, 0f))
        assertThat(r).containsExactly(null, -40f, -30f, -20f, -10f, 0f).inOrder()
    }

    @Test
    fun nanNullOrNonMonotonicOrFlatCurvesAreUnusable() {
        assertThat(VolumeCurve.validate(listOf(-30f, Float.NaN, -10f, 0f))[0]).isNotNull() // NaN entry alone: others fine
        assertThat(VolumeCurve.validate(listOf(-30f, Float.NaN, -10f, 0f))[1]).isNull()
        assertThat(VolumeCurve.validate(listOf(-30f, -10f, -20f, 0f)).all { it == null }).isTrue()
        assertThat(VolumeCurve.validate(listOf(0f, 0f, 0f)).all { it == null }).isTrue()
        assertThat(VolumeCurve.validate(listOf(null, null)).all { it == null }).isTrue()
        assertThat(VolumeCurve.validate(listOf(5f, ninf))).containsExactly(null, null)
    }

    @Test
    fun externalChangeFilterSuppressesOwnEchoes() {
        val f = ExternalChangeFilter(7)
        f.onSelfWrite(5)
        assertThat(f.onObserved(5)).isNull() // echo of our write
        assertThat(f.onObserved(5)).isNull()
        assertThat(f.onObserved(9)).isEqualTo(9) // user pressed volume up
        assertThat(f.onObserved(9)).isNull()
        f.onSelfWrite(6) // refused request: read-back differs from request but is what we know
        assertThat(f.onObserved(6)).isNull()
    }

    @Test
    fun filterSuppressesObserverThatFiresBeforeSetIndexReturns() {
        val f = ExternalChangeFilter(7)
        f.expectSelf(5)
        assertThat(f.onObserved(5)).isNull() // observer fires mid-write
        f.onSelfWrite(5)
        assertThat(f.onObserved(5)).isNull()
        assertThat(f.onObserved(8)).isEqualTo(8)
        // refused request: observer sees the old value again, still not external
        f.expectSelf(12)
        assertThat(f.onObserved(8)).isNull()
        f.onSelfWrite(8)
        assertThat(f.onObserved(8)).isNull()
    }

    @Test
    fun driftEmitsAlreadyDeviatedIndexOnCollectionStart() = runTest(UnconfinedTestDispatcher()) {
        val vc = FakeVolumeController(maxIndex = 15, initialIndex = 9)
        val lock = VolumeLock(vc)
        lock.lock(7)
        vc.externalChange(4) // before anyone collects: no replay in `changes`
        val seen = mutableListOf<Int>()
        val job = launch { lock.drift.collect { seen += it } }
        assertThat(seen).containsExactly(4)
        job.cancel()
    }

    @Test
    fun releaseDoesNotRaiseVolumeThatWasLoweredExternally() {
        val vc = FakeVolumeController(maxIndex = 15, initialIndex = 9)
        val lock = VolumeLock(vc)
        lock.lock(7)
        vc.externalChange(4) // e.g. CSD auto-lowering
        lock.release()
        assertThat(vc.index()).isEqualTo(4)
    }

    @Test
    fun releaseRestoresOriginalWhenExternalChangeWasHigher() {
        val vc = FakeVolumeController(maxIndex = 15, initialIndex = 9)
        val lock = VolumeLock(vc)
        lock.lock(7)
        vc.externalChange(12)
        lock.release()
        assertThat(vc.index()).isEqualTo(9)
    }

    @Test
    fun volumeLockRestoresOriginalInFinallyEvenOnException() {
        val vc = FakeVolumeController(maxIndex = 15, initialIndex = 9)
        val lock = VolumeLock(vc)
        try {
            lock.withLock(7) { eff ->
                assertThat(eff).isEqualTo(7)
                assertThat(vc.index()).isEqualTo(7)
                error("boom")
            }
        } catch (_: IllegalStateException) {
        }
        assertThat(vc.index()).isEqualTo(9)
        lock.release() // idempotent
        assertThat(vc.setCalls).containsExactly(7, 9).inOrder()
    }

    @Test
    fun volumeLockRestoresOriginalOnCoroutineCancellation() = runTest(UnconfinedTestDispatcher()) {
        val vc = FakeVolumeController(initialIndex = 9)
        val lock = VolumeLock(vc)
        val job = launch {
            lock.withLock(7) { kotlinx.coroutines.awaitCancellation() }
        }
        assertThat(vc.index()).isEqualTo(7)
        job.cancel()
        job.join()
        assertThat(vc.index()).isEqualTo(9)
    }

    @Test
    fun lockReportsEffectiveIndexWhenRefusedAndDriftEmitsOnlyOnDeviation() = runTest(UnconfinedTestDispatcher()) {
        val vc = FakeVolumeController(maxIndex = 15, initialIndex = 3).apply { safeLimit = 6 }
        val lock = VolumeLock(vc)
        assertThat(lock.lock(8)).isEqualTo(6)
        val seen = mutableListOf<Int>()
        val job = launch { lock.drift.collect { seen += it } }
        vc.externalChange(6) // same as locked: no drift
        vc.externalChange(4)
        assertThat(seen).containsExactly(4)
        job.cancel()
        lock.release()
        assertThat(vc.index()).isEqualTo(3) // 4 >= original 3: restore to original
    }
}
