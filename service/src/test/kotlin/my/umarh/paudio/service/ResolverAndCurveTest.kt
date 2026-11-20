package my.umarh.paudio.service

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import my.umarh.paudio.audio.effect.toEqConfig
import my.umarh.paudio.core.dsp.DpEqPlanner
import my.umarh.paudio.core.dsp.DpLadderStep
import my.umarh.paudio.core.model.OutputDevice
import my.umarh.paudio.core.model.OutputDeviceType
import my.umarh.paudio.data.repo.ListenerProfiles
import my.umarh.paudio.data.repo.ProfileWithHeadset
import my.umarh.paudio.service.engine.ActiveProfile
import my.umarh.paudio.service.engine.ActiveProfileResolver
import my.umarh.paudio.service.engine.ProfileCurveService
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ResolverAndCurveTest {

    private val buds = headset(1, BT)
    private val wired = headset(2, WIRED)
    private val lp = ListenerProfiles(
        listener(),
        listOf(
            ProfileWithHeadset(profile(1, 1, "Buds"), buds),
            ProfileWithHeadset(profile(2, 2, "Wired"), wired),
            ProfileWithHeadset(profile(3, null, "Any"), null),
        ),
    )

    @Test
    fun resolutionOrderAndNotProcessable() {
        val resolve = { d: OutputDevice?, map: Map<String, Long>, over: Map<String, Long> ->
            ActiveProfileResolver.resolveProfile(d, 1, map, listOf(lp), over)
        }
        assertThat((resolve(BT, emptyMap(), emptyMap()) as ActiveProfile.Resolved).profile.id).isEqualTo(1)
        // The exact headset beats a remembered mapping; a session answer beats both.
        assertThat((resolve(BT, mapOf(BT.headsetKey.value to 3L), emptyMap()) as ActiveProfile.Resolved).profile.id).isEqualTo(1)
        assertThat((resolve(BT, emptyMap(), mapOf(BT.headsetKey.value to 3L)) as ActiveProfile.Resolved).profile.id).isEqualTo(3)

        val unknown = OutputDevice(OutputDeviceType.BLUETOOTH_A2DP, "Unknown", "99:99")
        val none = resolve(unknown, emptyMap(), emptyMap()) as ActiveProfile.NoProfile
        assertThat(none.anyHeadphones?.id).isEqualTo(3L)
        assertThat((resolve(unknown, mapOf(unknown.headsetKey.value to 3L), emptyMap()) as ActiveProfile.Resolved).profile.id).isEqualTo(3)

        for (d in listOf(SPEAKER, OutputDevice(OutputDeviceType.EARPIECE, "e"), OutputDevice(OutputDeviceType.HDMI, "h"), OutputDevice(OutputDeviceType.OTHER, "o"), null)) {
            assertThat(resolve(d, emptyMap(), emptyMap())).isEqualTo(ActiveProfile.NotProcessable(d))
        }
        // A mapping to a profile of another listener is ignored.
        assertThat(ActiveProfileResolver.resolveProfile(unknown, 1, mapOf(unknown.headsetKey.value to 99L), listOf(lp)))
            .isInstanceOf(ActiveProfile.NoProfile::class.java)
        assertThat(ActiveProfileResolver.resolveProfile(BT, 1, emptyMap(), emptyList())).isEqualTo(ActiveProfile.NoProfile(BT, null))
    }

    @Test
    fun wiredCollisionQuestionIsAskedOnConnectAndRememberedUntilDisconnect() = runTest {
        val r = Rig(this)
        r.store.seedStandard()
        assertThat(r.resolver.pendingQuestion.value).isNull() // BT at start

        r.store.devices.set(WIRED)
        val q = r.resolver.pendingQuestion.value!!
        assertThat(q.candidates.map { it.id }).containsExactly(2L, 3L)
        // Meanwhile the default (headset-bound) profile already applies: the question never blocks audio.
        assertThat((r.resolver.activeProfile.value as ActiveProfile.Resolved).profile.id).isEqualTo(2)

        r.resolver.answerWhichHeadphones(3)
        assertThat(r.resolver.pendingQuestion.value).isNull()
        assertThat((r.resolver.activeProfile.value as ActiveProfile.Resolved).profile.id).isEqualTo(3)

        r.store.devices.set(SPEAKER)
        r.store.devices.set(WIRED) // reconnect: asked again, answer forgotten
        assertThat(r.resolver.pendingQuestion.value).isNotNull()
        assertThat((r.resolver.activeProfile.value as ActiveProfile.Resolved).profile.id).isEqualTo(2)
        r.resolver.answerWhichHeadphones(null) // dismiss: keep the default
        assertThat((r.resolver.activeProfile.value as ActiveProfile.Resolved).profile.id).isEqualTo(2)
    }

    @Test
    fun noQuestionWhenWiredIsAlreadyConnectedAtStartOrForBluetooth() = runTest {
        val r = Rig(this)
        r.store.seedStandard()
        r.store.devices.set(WIRED)
        r.store.devices.set(BT)
        assertThat(r.resolver.pendingQuestion.value).isNull()
        val r2 = Rig(this)
        r2.store.seedStandard()
        r2.store.devices.set(WIRED)
        r2.store.devices.set(SPEAKER)
        assertThat(r2.resolver.pendingQuestion.value).isNull()
    }

    @Test
    fun curveIsMemoizedByAllInputs() = runTest {
        val store = Store().also { it.seedStandard() }
        val svc = ProfileCurveService(store.tests)
        val p = store.profiles.profile(1)!!
        val src = svc.loadSource(1)
        assertThat(src).isNotNull()

        val a = svc.config(p, src, 48_000, DpLadderStep.MAIN)
        val b = svc.config(p, src, 48_000, DpLadderStep.MAIN)
        assertThat(b).isSameInstanceAs(a)
        assertThat(svc.plansComputed).isEqualTo(1)

        svc.config(p.copy(compStrength = 0.9f), src, 48_000, DpLadderStep.MAIN)
        svc.config(p.copy(gT = 2f), src, 48_000, DpLadderStep.MAIN)
        svc.config(p, src, 44_100, DpLadderStep.MAIN)
        svc.config(p, src, 48_000, DpLadderStep.FB51)
        svc.config(p, src!!.copy(testId = 99), 48_000, DpLadderStep.MAIN)
        svc.config(p, src.copy(algoVersion = 2), 48_000, DpLadderStep.MAIN)
        assertThat(svc.plansComputed).isEqualTo(7)

        // Unrelated profile fields are not part of the key.
        svc.config(p.copy(name = "renamed", personalizationOn = false), src, 48_000, DpLadderStep.MAIN)
        assertThat(svc.plansComputed).isEqualTo(7)
    }

    @Test
    fun curveFollowsTheMeasuredLossAndFlatWithoutAnyData() = runTest {
        val store = Store().also { it.seedStandard() }
        val svc = ProfileCurveService(store.tests)
        val p = store.profiles.profile(1)!!

        val withTest = svc.config(p, svc.loadSource(1), 48_000, DpLadderStep.MAIN)
        val expected = DpEqPlanner.plan(
            svc.loadSource(1)!!.hlLeft.toDoubleArray(), svc.loadSource(1)!!.hlRight.toDoubleArray(),
            0.5, 0.0, 0.0, 0.0, 48_000,
        ).toEqConfig()
        assertThat(withTest).isEqualTo(expected)
        assertThat(withTest.channelGainsDb[0].max()).isGreaterThan(1f) // HF loss -> boost
        assertThat(withTest.preampDb).isLessThan(0f)

        // No test and no preference: flat (DECISIONS §20).
        val noTest = profile(9, null, "x")
        assertThat(svc.config(noTest, null, 48_000, DpLadderStep.MAIN))
            .isEqualTo(DpEqPlanner.flat(48_000).toEqConfig())
        // No test but a preference gain: preference-only curve.
        val pref = svc.config(noTest.copy(gB = 4f), null, 48_000, DpLadderStep.MAIN)
        assertThat(pref.channelGainsDb[0].max()).isGreaterThan(1f)
        assertThat(pref.channelGainsDb[0]).isEqualTo(pref.channelGainsDb[1])
    }

    @Test
    fun incompleteTestsAreIgnored() = runTest {
        val store = Store().also { it.seedStandard() }
        store.tests.put(testEntity(20, 1), thresholdsFor(20, 10f).drop(3))
        val svc = ProfileCurveService(store.tests)
        assertThat(svc.loadSource(1)).isNull()
    }
}
