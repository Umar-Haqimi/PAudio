package my.umarh.paudio.feature.onboarding

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import my.umarh.paudio.designsystem.PAudioTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

class FakeStarter(private val log: MutableList<String> = mutableListOf()) : ServiceStarter {
    var started = 0
    override fun start(): Boolean {
        started++
        log += "start"
        return true
    }
}

@RunWith(AndroidJUnit4::class)
class OnboardingViewModelTest {
    private val db = FakeDb()
    private val settings = FakeSettingsRepo()
    private val starter = FakeStarter()

    private fun vm() = OnboardingViewModel(FakeListenerRepo(db), FakeProfileRepo(db), settings, starter)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = StandardTestDispatcher()

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @OptIn(ExperimentalCoroutinesApi::class)
    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun walksThroughTheSteps_andTheDisclaimerCannotBeSkipped() {
        val v = vm()
        assertThat(v.state.value.step).isEqualTo(OnboardingStep.WELCOME)
        v.next()
        assertThat(v.state.value.step).isEqualTo(OnboardingStep.HOW_IT_WORKS)
        v.next()
        assertThat(v.state.value.step).isEqualTo(OnboardingStep.DISCLAIMER)

        v.next() // no way past the disclaimer without "I understand"
        assertThat(v.state.value.step).isEqualTo(OnboardingStep.DISCLAIMER)
        assertThat(v.state.value.disclaimerAccepted).isFalse()

        v.acknowledgeDisclaimer()
        assertThat(v.state.value.disclaimerAccepted).isTrue()
        assertThat(v.state.value.step).isEqualTo(OnboardingStep.NOTIFICATIONS)

        v.back()
        v.back()
        assertThat(v.state.value.step).isEqualTo(OnboardingStep.HOW_IT_WORKS)
    }

    @Test
    fun finishBeforeTheDisclaimerIsAcknowledged_doesNothing() = runTest(dispatcher) {
        val v = vm()
        v.finish("Me")
        advanceUntilIdle()
        assertThat(db.listeners.value).isEmpty()
        assertThat(starter.started).isEqualTo(0)
        assertThat(settings.done.value).isFalse()
    }

    @Test
    fun notificationDenied_showsTheCopyAndContinues() {
        val v = vm()
        repeat(2) { v.next() }
        v.acknowledgeDisclaimer()
        v.onNotificationResult(false)
        assertThat(v.state.value.notificationsDenied).isTrue()
        assertThat(v.state.value.step).isEqualTo(OnboardingStep.NOTIFICATIONS)
        v.next() // "Continue"
        assertThat(v.state.value.step).isEqualTo(OnboardingStep.LISTENER)
    }

    @Test
    fun notificationGranted_movesOn() {
        val v = vm()
        repeat(2) { v.next() }
        v.acknowledgeDisclaimer()
        v.onNotificationResult(true)
        assertThat(v.state.value.step).isEqualTo(OnboardingStep.LISTENER)
        assertThat(v.state.value.notificationsDenied).isFalse()
    }

    @Test
    fun finish_createsTheListener_startsTheService_andMarksOnboardingDone() = runTest(dispatcher) {
        val v = vm()
        repeat(2) { v.next() }
        v.acknowledgeDisclaimer()
        v.next()
        v.setListenerName("  Alex ")
        v.finish("Me")
        advanceUntilIdle()

        val listener = db.listeners.value.single()
        assertThat(listener.name).isEqualTo("Alex")
        assertThat(settings.active.value).isEqualTo(listener.id)
        assertThat(db.profiles.value.single { it.listenerId == listener.id }.headsetId).isNull() // "Any headphones"
        assertThat(starter.started).isEqualTo(1)
        assertThat(settings.done.value).isTrue()
        assertThat(v.state.value.finished).isTrue()
    }

    @Test
    fun finish_blankNameUsesTheDefault_andIsIdempotent() = runTest(dispatcher) {
        val v = vm()
        repeat(2) { v.next() }
        v.acknowledgeDisclaimer()
        v.setListenerName("   ")
        v.finish("Me")
        v.finish("Me")
        advanceUntilIdle()
        assertThat(db.listeners.value.map { it.name }).containsExactly("Me")
        assertThat(starter.started).isEqualTo(1)
    }

    @Test
    fun nameIsLimited() {
        val v = vm()
        v.setListenerName("x".repeat(100))
        assertThat(v.state.value.listenerName).hasLength(OnboardingViewModel.MAX_NAME)
    }
}

@RunWith(AndroidJUnit4::class)
class OnboardingScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = FakeDb()
    private val settings = FakeSettingsRepo()
    private val starter = FakeStarter()

    private fun host(permissionNeeded: Boolean, onRequest: () -> Unit = {}): OnboardingViewModel {
        val vm = OnboardingViewModel(FakeListenerRepo(db), FakeProfileRepo(db), settings, starter)
        rule.setContent {
            PAudioTheme(darkTheme = false) {
                val s by vm.state.collectAsState()
                OnboardingContent(
                    state = s,
                    permissionNeeded = permissionNeeded,
                    onNext = vm::next,
                    onBack = vm::back,
                    onAcknowledge = vm::acknowledgeDisclaimer,
                    onRequestPermission = onRequest,
                    onNameChange = vm::setListenerName,
                    onFinish = { vm.finish("Me") },
                )
            }
        }
        return vm
    }

    @Test
    fun happyPath_disclaimerMustBeAcknowledgedBeforeContinuing() {
        val vm = host(permissionNeeded = false)
        rule.onNodeWithText(context.getString(R.string.onboarding_welcome_headline)).assertIsDisplayed()
        rule.onNodeWithTag(TAG_CONTINUE).performClick()
        rule.onNodeWithText(context.getString(R.string.onboarding_how_1_title)).assertIsDisplayed()
        rule.onNodeWithTag(TAG_CONTINUE).performClick()

        // Disclaimer: spec wording, and no "continue" button, only "I understand".
        rule.onNodeWithText(context.getString(R.string.onboarding_disclaimer_text)).assertIsDisplayed()
        rule.onNodeWithTag(TAG_CONTINUE).assertDoesNotExist()
        assertThat(vm.state.value.disclaimerAccepted).isFalse()
        rule.onNodeWithTag(TAG_ACK).performClick()
        assertThat(vm.state.value.disclaimerAccepted).isTrue()

        // No permission needed (API < 33 or granted): straight to the listener step.
        rule.waitForIdle()
        rule.onNodeWithText(context.getString(R.string.onboarding_listener_title)).assertIsDisplayed()
        rule.onNodeWithTag(TAG_FINISH).performClick()
        rule.waitForIdle()
        assertThat(starter.started).isEqualTo(1)
        assertThat(settings.done.value).isTrue()
        assertThat(db.listeners.value.single().name).isEqualTo("Me")
    }

    @Test
    fun permissionStep_deniedShowsTheBackgroundCopy_thenContinues() {
        var requested = 0
        val vm = host(permissionNeeded = true) { requested++ }
        repeat(2) { rule.onNodeWithTag(TAG_CONTINUE).performClick() }
        rule.onNodeWithTag(TAG_ACK).performClick()
        rule.onNodeWithText(context.getString(R.string.onboarding_notifications_title)).assertIsDisplayed()
        rule.onNodeWithTag(TAG_CONTINUE).performClick() // "Allow notifications"
        assertThat(requested).isEqualTo(1)

        vm.onNotificationResult(false)
        rule.waitForIdle()
        rule.onNodeWithText("PAudio needs its notification to keep working in the background.").assertIsDisplayed()
        rule.onNodeWithTag(TAG_CONTINUE).performClick()
        rule.onNodeWithText(context.getString(R.string.onboarding_listener_title)).assertIsDisplayed()
    }

    @Test
    fun listenerNameCanBeTyped() {
        val vm = host(permissionNeeded = false)
        repeat(2) { rule.onNodeWithTag(TAG_CONTINUE).performClick() }
        rule.onNodeWithTag(TAG_ACK).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(context.getString(R.string.onboarding_listener_label)).performTextInput("Robin")
        assertThat(vm.state.value.listenerName).isEqualTo("Robin")
    }
}
