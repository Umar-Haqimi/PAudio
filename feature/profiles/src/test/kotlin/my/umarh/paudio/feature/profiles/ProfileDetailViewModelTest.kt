package my.umarh.paudio.feature.profiles

import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ProfileDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val db = FakeDb()
    private val settings = FakeSettingsRepo()
    private val transfer = FakeTransfer()

    private fun vm(profileId: Long) = ProfileDetailViewModel(
        SavedStateHandle(mapOf("profileId" to profileId)),
        FakeProfileRepo(db),
        FakeTestRepo(db),
        FakeHeadsetRepo(db),
        settings,
        transfer,
    )

    private fun TestScope.settle() = repeat(3) {
        advanceUntilIdle()
        runCurrent()
    }

    private fun TestScope.collect(vm: ProfileDetailViewModel): MutableStateFlow<ProfileDetailUiState> {
        val out = MutableStateFlow(ProfileDetailUiState())
        backgroundScope.launch { vm.uiState.collect { out.value = it } }
        settle()
        return out
    }

    private fun TestScope.events(vm: ProfileDetailViewModel): MutableList<DetailEvent> {
        val list = mutableListOf<DetailEvent>()
        backgroundScope.launch { vm.events.collect { list += it } }
        return list
    }

    private data class World(val listener: Long, val profile: Long, val buds: Long, val speakerBuds: Long)

    private fun world(withTest: Boolean = true): World {
        val me = db.addListener("Me")
        val buds = db.addHeadset("BT|Buds|AB:CD", "Buds")
        val other = db.addHeadset("WIRED|AKG|", "AKG")
        val p = db.addProfile(me.id, buds.id, "Buds profile")
        if (withTest) db.addTest(p.id)
        return World(me.id, p.id, buds.id, other.id)
    }

    @Test
    fun showsHeadsetHistoryAndButterfly() = runTest(dispatcher) {
        val w = world()
        db.addTest(w.profile, startedAt = 1_800_000_000_000L, reliable = false)
        val out = collect(vm(w.profile))
        val s = out.value
        assertThat(s.name).isEqualTo("Buds profile")
        assertThat(s.listenerName).isEqualTo("Me")
        assertThat(s.headset).isEqualTo(HeadsetLabel.Named("Buds"))
        assertThat(s.tests.map { it.reliable }).containsExactly(false, true).inOrder() // newest first
        assertThat(s.butterfly).isNotNull()
        assertThat(s.butterfly!!.freqsHz).hasSize(9)
    }

    @Test
    fun noTest_hasNoButterfly_andIncompleteTestIsNotShown() = runTest(dispatcher) {
        val w = world(withTest = false)
        val out = collect(vm(w.profile))
        assertThat(out.value.butterfly).isNull()
        assertThat(out.value.tests).isEmpty()

        db.addTest(w.profile, complete = false)
        settle()
        assertThat(out.value.tests).hasSize(1)
        assertThat(out.value.butterfly).isNull()
    }

    @Test
    fun missingProfile_isNotFound() = runTest(dispatcher) {
        world()
        val out = collect(vm(12345))
        assertThat(out.value.notFound).isTrue()
    }

    @Test
    fun freeSlots_offerAnyAndUnusedHeadsets_butNotTheOwnOrUsedOnes() = runTest(dispatcher) {
        val w = world()
        val out = collect(vm(w.profile))
        // Own headset is used by this very profile; Any is free; AKG is free.
        assertThat(out.value.freeSlots.map { it.label }).containsExactly(HeadsetLabel.AnyHeadphones, HeadsetLabel.Named("AKG")).inOrder()
        db.addProfile(w.listener, null, "Any headphones")
        settle()
        assertThat(out.value.freeSlots.map { it.label }).containsExactly(HeadsetLabel.Named("AKG"))
    }

    @Test
    fun rename_trimsAndIgnoresBlank() = runTest(dispatcher) {
        val w = world()
        val v = vm(w.profile)
        val out = collect(v)
        v.rename("   ")
        v.rename("  Better name ")
        settle()
        assertThat(out.value.name).isEqualTo("Better name")
    }

    @Test
    fun duplicate_toFreeSlot_copiesTestsAndOpensTheCopy() = runTest(dispatcher) {
        val w = world()
        val v = vm(w.profile)
        val out = collect(v)
        val events = events(v)

        v.duplicate("Copy", out.value.freeSlots.first { it.label == HeadsetLabel.AnyHeadphones })
        settle()

        val copy = db.profiles.value.single { it.name == "Copy" }
        assertThat(copy.headsetId).isNull()
        assertThat(db.tests.value.count { it.profileId == copy.id }).isEqualTo(1)
        assertThat(events).containsExactly(DetailEvent.OpenProfile(copy.id))
    }

    @Test
    fun duplicate_toTakenSlot_reportsSlotTaken() = runTest(dispatcher) {
        val w = world()
        val v = vm(w.profile)
        val out = collect(v)
        // The slot became taken after the dialog was built (stale option).
        val stale = out.value.freeSlots.first { it.label == HeadsetLabel.Named("AKG") }
        db.addProfile(w.listener, w.speakerBuds, "Taken")
        v.duplicate("Copy", stale)
        settle()
        assertThat(out.value.message).isEqualTo(DetailMessage.SlotTaken)
        assertThat(db.profiles.value.none { it.name == "Copy" }).isTrue()
    }

    @Test
    fun move_succeeds_andOffersARetest_thenTheOfferCanBeDismissed() = runTest(dispatcher) {
        val w = world()
        val v = vm(w.profile)
        val out = collect(v)

        v.move(out.value.freeSlots.first { it.label == HeadsetLabel.Named("AKG") })
        settle()

        assertThat(db.profile(w.profile)!!.headsetId).isEqualTo(w.speakerBuds)
        assertThat(out.value.headset).isEqualTo(HeadsetLabel.Named("AKG"))
        assertThat(out.value.offerRetest).isTrue()
        assertThat(settings.removedProfiles).contains(w.profile)

        v.dismissRetestOffer()
        settle()
        assertThat(out.value.offerRetest).isFalse()
    }

    @Test
    fun move_toTakenSlot_failsCleanly() = runTest(dispatcher) {
        val w = world()
        val v = vm(w.profile)
        val out = collect(v)
        val stale = out.value.freeSlots.first { it.label == HeadsetLabel.AnyHeadphones }
        db.addProfile(w.listener, null, "Any headphones")
        v.move(stale)
        settle()
        assertThat(out.value.message).isEqualTo(DetailMessage.SlotTaken)
        assertThat(out.value.offerRetest).isFalse()
        assertThat(db.profile(w.profile)!!.headsetId).isEqualTo(w.buds)
    }

    @Test
    fun delete_removesTheProfile_forgetsItAndLeaves() = runTest(dispatcher) {
        val w = world()
        val v = vm(w.profile)
        collect(v)
        val events = events(v)
        v.delete()
        settle()
        assertThat(db.profile(w.profile)).isNull()
        assertThat(db.tests.value).isEmpty()
        assertThat(settings.removedProfiles).containsExactly(w.profile)
        assertThat(events).containsExactly(DetailEvent.Deleted)
    }

    @Test
    fun export_usesTheExporter_andReportsOutcome() = runTest(dispatcher) {
        val w = world()
        val v = vm(w.profile)
        val out = collect(v)
        transfer.exportJson = """{"schemaVersion":1,"profile":{"name":"Buds profile"}}"""

        val bytes = v.exportBytes()
        assertThat(transfer.exported).containsExactly(w.profile)
        assertThat(String(bytes!!, Charsets.UTF_8)).isEqualTo(transfer.exportJson)

        v.reportExport(true)
        settle()
        assertThat(out.value.message).isEqualTo(DetailMessage.Exported)
        v.reportExport(false)
        settle()
        assertThat(out.value.message).isEqualTo(DetailMessage.ExportFailed)

        transfer.exportJson = null
        assertThat(v.exportBytes()).isNull()
    }

    @Test
    fun seedColor_pickMarksOverridden_resetRecomputesFromTheLatestTest() = runTest(dispatcher) {
        val w = world()
        val v = vm(w.profile)
        val out = collect(v)

        v.setSeedColor(0xFFC0392B.toInt())
        settle()
        assertThat(out.value.seedColor).isEqualTo(0xFFC0392B.toInt())
        assertThat(out.value.seedOverridden).isTrue()

        v.resetSeedColor()
        settle()
        assertThat(out.value.seedOverridden).isFalse()
        val derived = ProfileMeasure.seedColor(db.thresholds.values.first())
        assertThat(derived).isNotNull()
        assertThat(out.value.seedColor).isEqualTo(derived)
    }

    @Test
    fun seedColor_resetWithoutATest_clearsTheSeed() = runTest(dispatcher) {
        val w = world(withTest = false)
        val v = vm(w.profile)
        val out = collect(v)
        v.setSeedColor(0xFF123456.toInt())
        settle()
        v.resetSeedColor()
        settle()
        assertThat(out.value.seedColor).isNull()
        assertThat(out.value.seedOverridden).isFalse()
    }

    @Test
    fun profileMeasure_incompleteThresholds_giveNothing() {
        assertThat(ProfileMeasure.butterfly(fullThresholds(1, complete = false))).isNull()
        assertThat(ProfileMeasure.seedColor(fullThresholds(1, complete = false))).isNull()
        val full = ProfileMeasure.butterfly(fullThresholds(1))!!
        assertThat(full.leftHl).hasSize(9)
        // The seed hue stays inside the documented 200..340 degree range (blue to magenta); alpha is opaque.
        val argb = ProfileMeasure.seedColor(fullThresholds(1))!!
        assertThat(argb ushr 24).isEqualTo(0xFF)
        assertThat(androidx.compose.ui.graphics.Color(argb).toArgb()).isEqualTo(argb)
    }
}
