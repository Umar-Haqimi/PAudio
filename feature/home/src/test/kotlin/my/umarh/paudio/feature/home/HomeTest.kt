package my.umarh.paudio.feature.home

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import my.umarh.paudio.audio.effect.EngineError
import my.umarh.paudio.audio.effect.EngineState
import my.umarh.paudio.core.model.OutputDevice
import my.umarh.paudio.core.model.OutputDeviceType
import my.umarh.paudio.data.db.ListenerEntity
import my.umarh.paudio.data.db.ProfileEntity
import my.umarh.paudio.designsystem.PAudioTheme
import my.umarh.paudio.service.EngineStatus
import my.umarh.paudio.service.engine.ActiveProfile
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

class FakeHomeEngine : HomeEngine {
    override val state = MutableStateFlow<EngineState>(EngineState.Waiting)
    override val noSessionHint = MutableStateFlow(false)
    override val running = MutableStateFlow(true)
    override val activeProfile = MutableStateFlow<ActiveProfile>(ActiveProfile.Loading)
    override val notificationsBlocked = MutableStateFlow(false)
    override val fault = MutableStateFlow(false)
    val calls = mutableListOf<String>()
    val personalizedCalls = mutableListOf<Boolean>()

    override fun start(): Boolean {
        calls += "start"
        return true
    }

    override fun retry() {
        calls += "retry"
    }

    override suspend fun setPersonalized(on: Boolean): Boolean {
        personalizedCalls += on
        return true
    }

    override suspend fun togglePersonalized(): Boolean {
        calls += "toggle"
        return true
    }

    override fun refreshNotificationPermission() {
        calls += "refresh"
    }
}

private val BUDS = OutputDevice(OutputDeviceType.BLUETOOTH_A2DP, "Test Buds", "AB:CD")

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class HomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val db = FakeDb()
    private val settings = FakeSettingsRepo()
    private val engine = FakeHomeEngine()

    private fun vm() = HomeViewModel(engine, FakeProfileRepo(db), FakeTestRepo(db), settings)

    private fun TestScope.settle() = repeat(3) {
        advanceUntilIdle()
        runCurrent()
    }

    private fun TestScope.collect(v: HomeViewModel): MutableStateFlow<HomeUiState> {
        val out = MutableStateFlow(HomeUiState())
        backgroundScope.launch { v.uiState.collect { out.value = it } }
        settle()
        return out
    }

    private fun resolved(profile: ProfileEntity, listener: ListenerEntity) =
        ActiveProfile.Resolved(BUDS, profile, listener)

    @Test
    fun noProfilesAtAll_isTheEmptyState() = runTest(dispatcher) {
        val out = collect(vm())
        assertThat(out.value.loading).isFalse()
        assertThat(out.value.profile).isNull()
        assertThat(out.value.isEmpty).isTrue()
    }

    @Test
    fun profileWithoutATest_isEmpty_andShowsTheListenerAndHeadset() = runTest(dispatcher) {
        val me = db.addListener("Me")
        settings.active.value = me.id
        db.addProfile(me.id, null, "Any headphones")
        val out = collect(vm())
        val p = out.value.profile!!
        assertThat(p.listenerName).isEqualTo("Me")
        assertThat(p.headset).isEqualTo(HeadsetLabel.AnyHeadphones)
        assertThat(p.hasTest).isFalse()
        assertThat(out.value.isEmpty).isTrue()
        assertThat(out.value.engineFollowsProfile).isFalse()
    }

    @Test
    fun resolvedProfile_withATest_showsTheButterflyAndPersonalization() = runTest(dispatcher) {
        val me = db.addListener("Me")
        val h = db.addHeadset("BT|Test Buds|AB:CD", "Test Buds")
        db.addProfile(me.id, null, "Any headphones")
        val buds = db.addProfile(me.id, h.id, "Buds")
        db.addTest(buds.id)
        engine.activeProfile.value = resolved(buds, me)
        val out = collect(vm())

        val p = out.value.profile!!
        assertThat(p.profileId).isEqualTo(buds.id)
        assertThat(p.headset).isEqualTo(HeadsetLabel.Named("Test Buds"))
        assertThat(p.hasTest).isTrue()
        assertThat(p.butterfly!!.freqsHz).hasSize(9)
        assertThat(out.value.isEmpty).isFalse()
        assertThat(out.value.engineFollowsProfile).isTrue()
        assertThat(out.value.personalized).isTrue()
    }

    @Test
    fun noProfileForTheHeadset_fallsBackToTheListenersAnyProfile_andFlagsTheNeed() = runTest(dispatcher) {
        val me = db.addListener("Me")
        settings.active.value = me.id
        val any = db.addProfile(me.id, null, "Any headphones")
        engine.activeProfile.value = ActiveProfile.NoProfile(BUDS, any)
        engine.state.value = EngineState.NoProfile(BUDS)
        val out = collect(vm())
        assertThat(out.value.noProfileForHeadset).isTrue()
        assertThat(out.value.profile!!.profileId).isEqualTo(any.id)
        assertThat(out.value.engineFollowsProfile).isFalse()
        assertThat(out.value.personalized).isFalse()
    }

    @Test
    fun incompleteTest_hasATestButNoButterfly() = runTest(dispatcher) {
        val me = db.addListener("Me")
        settings.active.value = me.id
        val p = db.addProfile(me.id, null, "Any headphones")
        db.addTest(p.id, complete = false)
        val out = collect(vm())
        assertThat(out.value.profile!!.hasTest).isTrue()
        assertThat(out.value.profile!!.butterfly).isNull()
    }

    @Test
    fun engineFlagsAreMirrored() = runTest(dispatcher) {
        val out = collect(vm())
        engine.running.value = false
        engine.state.value = EngineState.Conflict
        engine.noSessionHint.value = true
        engine.notificationsBlocked.value = true
        engine.fault.value = true
        settle()
        val s = out.value
        assertThat(s.running).isFalse()
        assertThat(s.engineState).isEqualTo(EngineState.Conflict)
        assertThat(s.noSessionHint).isTrue()
        assertThat(s.notificationsBlocked).isTrue()
        assertThat(s.fault).isTrue()
    }

    @Test
    fun commandsReachTheController() = runTest(dispatcher) {
        val v = vm()
        v.setPersonalized(false)
        v.setPersonalized(true)
        v.togglePersonalized()
        v.retry()
        v.turnOn()
        v.refreshNotificationPermission()
        advanceUntilIdle()
        assertThat(engine.personalizedCalls).containsExactly(false, true).inOrder()
        assertThat(engine.calls).containsExactly("toggle", "retry", "start", "refresh")
    }

    @Test
    fun strength_isPersistedToTheProfile_withoutTouchingPersonalization() = runTest(dispatcher) {
        val me = db.addListener("Me")
        settings.active.value = me.id
        val p = db.addProfile(me.id, null, "Any headphones")
        FakeProfileRepo(db).setPersonalization(p.id, false)
        val v = vm()
        collect(v)
        v.setStrength(0.8f)
        settle()
        val stored = db.profile(p.id)!!
        assertThat(stored.compStrength).isEqualTo(0.8f)
        assertThat(stored.personalizationOn).isFalse()
        v.setStrength(7f)
        settle()
        assertThat(db.profile(p.id)!!.compStrength).isEqualTo(1f) // clamped
    }
}

@RunWith(AndroidJUnit4::class)
class HomeScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val profile = HomeProfile(
        profileId = 1,
        profileName = "Buds",
        listenerName = "Me",
        headset = HeadsetLabel.Named("Test Buds"),
        strength = 0.5f,
        butterfly = null,
        hasTest = true,
    )
    private val content = HomeUiState(
        loading = false,
        running = true,
        engineState = EngineState.Waiting,
        profile = profile,
        engineFollowsProfile = true,
        personalized = true,
    )

    private fun show(state: HomeUiState, actions: HomeActions = HomeActions()) {
        rule.setContent { PAudioTheme(darkTheme = false) { HomeContent(state, actions) } }
    }

    @Test
    fun emptyState_showsOneBigCta_thatStartsTheTest() {
        var tests = 0
        show(HomeUiState(loading = false, running = true), HomeActions(onHearingTest = { tests++ }))
        rule.onNodeWithText("Take your hearing test (≈ 7 min)").assertIsDisplayed()
        rule.onNodeWithTag(TAG_EMPTY_CTA).performClick()
        assertThat(tests).isEqualTo(1)
    }

    @Test
    fun withATest_noEmptyCta_andAllActionsAreOffered() {
        val calls = mutableListOf<String>()
        show(
            content,
            HomeActions(
                onHearingTest = { calls += "test" },
                onPreferenceTest = { calls += "pref" },
                onHealth = { calls += "health:$it" },
                onProfiles = { calls += "profiles" },
                onSettings = { calls += "settings" },
            ),
        )
        rule.onNodeWithTag(TAG_EMPTY_CTA).assertDoesNotExist()
        listOf(
            R.string.home_action_hearing_test, R.string.home_action_preference_test, R.string.home_action_health,
            R.string.home_action_profiles, R.string.home_action_settings,
        ).forEach { rule.onNodeWithText(context.getString(it)).performScrollTo().performClick() }
        assertThat(calls).containsExactly("test", "pref", "health:1", "profiles", "settings").inOrder()
    }

    @Test
    fun personalizedToggle_callsTheController() {
        val db = FakeDb()
        val me = db.addListener("Me")
        val p = db.addProfile(me.id, null, "Any headphones")
        db.addTest(p.id)
        val engine = FakeHomeEngine()
        engine.activeProfile.value = ActiveProfile.Resolved(BUDS, p, me)
        val settings = FakeSettingsRepo(active = me.id)
        val vm = HomeViewModel(engine, FakeProfileRepo(db), FakeTestRepo(db), settings)
        rule.setContent {
            PAudioTheme(darkTheme = false) {
                val s by vm.uiState.collectAsState()
                HomeContent(s, HomeActions(onTogglePersonalized = vm::setPersonalized))
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Original").performScrollTo().performClick()
        rule.waitForIdle()
        assertThat(engine.personalizedCalls).containsExactly(false)
        rule.onNodeWithText("Personalized").performClick()
        rule.waitForIdle()
        assertThat(engine.personalizedCalls).containsExactly(false, true).inOrder()
    }

    @Test
    fun conflictAndError_areTappableToRetry() {
        var retries = 0
        val state = MutableStateFlow(content.copy(engineState = EngineState.Conflict))
        rule.setContent {
            PAudioTheme(darkTheme = false) {
                val s by state.collectAsState()
                HomeContent(s, HomeActions(onRetry = { retries++ }))
            }
        }
        rule.onNodeWithTag(TAG_STATUS).performScrollTo().performClick()
        assertThat(retries).isEqualTo(1)

        state.value = content.copy(engineState = EngineState.Error(EngineError.CREATION_FAILED))
        rule.waitForIdle()
        rule.onNodeWithText("This device's audio system refused PAudio's effect. Try Legacy mode in Settings.").assertIsDisplayed()
        rule.onNodeWithTag(TAG_STATUS).performClick()
        assertThat(retries).isEqualTo(2)
    }

    @Test
    fun statesThatAreNotErrors_areNotTappable() {
        var retries = 0
        show(content.copy(engineState = EngineState.Active(listOf("com.example.player"))), HomeActions(onRetry = { retries++ }))
        rule.onNodeWithTag(TAG_STATUS).performScrollTo().performClick()
        assertThat(retries).isEqualTo(0)
    }

    @Test
    fun everyEngineState_showsItsStatusText() {
        val device = BUDS
        val states = listOf(
            EngineState.Off, EngineState.Starting, EngineState.Waiting, EngineState.Active(listOf("com.example.player")),
            EngineState.Legacy(true), EngineState.Legacy(false), EngineState.Bypassed, EngineState.Conflict,
            EngineState.NoProfile(device), EngineState.SpeakerOutput, EngineState.Error(EngineError.CREATION_FAILED),
            EngineState.Error(EngineError.SOURCE_FAILED),
        )
        val state = MutableStateFlow(content)
        rule.setContent {
            PAudioTheme(darkTheme = false) {
                val s by state.collectAsState()
                HomeContent(s, HomeActions())
            }
        }
        states.forEach { engineState ->
            state.value = content.copy(engineState = engineState)
            rule.waitForIdle()
            val expected = EngineStatus.textOf(engineState) { it }.format(context)
            rule.onNodeWithText(expected).assertIsDisplayed()
        }
    }

    @Test
    fun noSessionHint_isShownWhileWaiting() {
        show(content.copy(engineState = EngineState.Waiting, noSessionHint = true))
        rule.onNodeWithText("Your music app isn't announcing itself. Try Legacy mode, or check the compatibility list.")
            .assertIsDisplayed()
    }

    @Test
    fun noProfile_offersCreatingOne() {
        var created = 0
        show(
            content.copy(engineState = EngineState.NoProfile(BUDS), noProfileForHeadset = true, engineFollowsProfile = false),
            HomeActions(onCreateProfile = { created++ }),
        )
        rule.onNodeWithText("Create profile for this headset").performScrollTo().performClick()
        assertThat(created).isEqualTo(1)
    }

    @Test
    fun serviceOff_showsTurnOn() {
        var on = 0
        show(content.copy(running = false, engineState = EngineState.Off), HomeActions(onTurnOn = { on++ }))
        rule.onNodeWithText(context.getString(R.string.home_off_title)).assertIsDisplayed()
        rule.onNodeWithTag(TAG_TURN_ON).performClick()
        assertThat(on).isEqualTo(1)
    }

    @Test
    fun notificationsBlocked_showsTheBackgroundCopy() {
        show(content.copy(notificationsBlocked = true))
        rule.onNodeWithText("PAudio needs its notification to keep working in the background.").assertIsDisplayed()
    }

    @Suppress("unused")
    private val unused: StateFlow<Int> = MutableStateFlow(0)
}
