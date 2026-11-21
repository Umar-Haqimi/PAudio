package my.umarh.paudio.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import my.umarh.paudio.data.settings.EngineMode
import my.umarh.paudio.designsystem.PAudioTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Records engine calls; persisting the mode goes through the settings fake like the real controller does. */
class FakeSettingsEngine(private val settings: FakeSettingsRepo) : SettingsEngine {
    override val running = MutableStateFlow(true)
    val modes = mutableListOf<EngineMode>()
    val calls = mutableListOf<String>()

    override suspend fun setMode(mode: EngineMode) {
        modes += mode
        settings.setEngineMode(mode)
    }

    override fun start(): Boolean {
        calls += "start"
        running.value = true
        return true
    }

    override fun stop() {
        calls += "stop"
        running.value = false
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SettingsTest {
    @get:Rule val compose = createComposeRule()
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun modeSwitchGoesThroughEngineAndIsReflected() = runTest(dispatcher) {
        val settings = FakeSettingsRepo()
        val engine = FakeSettingsEngine(settings)
        val vm = SettingsViewModel(settings, engine)
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        assertThat(vm.uiState.value.mode).isEqualTo(EngineMode.SESSION)

        vm.setMode(EngineMode.LEGACY)
        advanceUntilIdle()
        assertThat(engine.modes).containsExactly(EngineMode.LEGACY)
        assertThat(vm.uiState.value.mode).isEqualTo(EngineMode.LEGACY)

        // Selecting the current mode again is a no-op (no redundant engine restart).
        vm.setMode(EngineMode.LEGACY)
        advanceUntilIdle()
        assertThat(engine.modes).containsExactly(EngineMode.LEGACY)
    }

    @Test
    fun bootStrengthAndServiceToggles() = runTest(dispatcher) {
        val settings = FakeSettingsRepo()
        val engine = FakeSettingsEngine(settings)
        val vm = SettingsViewModel(settings, engine)
        backgroundScope.launch { vm.uiState.collect {} }

        vm.setStartOnBoot(true)
        vm.setDefaultStrength(0.8f)
        vm.setServiceOn(false)
        advanceUntilIdle()
        assertThat(settings.boot.value).isTrue()
        assertThat(settings.strength.value).isEqualTo(0.8f)
        assertThat(engine.calls).containsExactly("stop")
        assertThat(vm.uiState.value.serviceRunning).isFalse()
    }

    @Test
    fun tappingLegacyInvokesModeAction() {
        val picked = mutableListOf<EngineMode>()
        compose.setContent {
            PAudioTheme {
                SettingsContent(SettingsUiState(loading = false), SettingsActions(onMode = { picked += it }))
            }
        }
        compose.onNodeWithTag(TAG_MODE_LEGACY).performScrollTo().performClick()
        assertThat(picked).containsExactly(EngineMode.LEGACY)
    }

    @Test
    fun aboutShowsAllThreeAttributions() {
        compose.setContent { PAudioTheme { AboutContent(versionName = "1.0", onBack = {}) } }
        listOf(TAG_ATTR_NIN, TAG_ATTR_FONT, TAG_ATTR_ICONS).forEach {
            compose.onNodeWithTag(it).performScrollTo().assertIsDisplayed()
        }
    }
}
