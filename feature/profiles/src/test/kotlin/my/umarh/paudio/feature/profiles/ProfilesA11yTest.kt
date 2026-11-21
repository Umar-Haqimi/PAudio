package my.umarh.paudio.feature.profiles

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.umarh.paudio.core.model.TestMode
import my.umarh.paudio.designsystem.PAudioTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** M12 accessibility pass (SPEC §8): labelled clickables and 48 dp touch targets on the profile list and detail. */
@RunWith(AndroidJUnit4::class)
class ProfilesA11yTest {
    @get:Rule
    val rule = createComposeRule()

    private val groups = listOf(
        ListenerGroup(
            1, "Me", true,
            listOf(ProfileItem(10, "Any headphones", HeadsetLabel.AnyHeadphones), ProfileItem(11, "Buds", HeadsetLabel.Named("Buds"))),
        ),
        ListenerGroup(2, "Sam", false, listOf(ProfileItem(20, "Any headphones", HeadsetLabel.AnyHeadphones))),
    )

    private fun list(dark: Boolean) {
        rule.setContent {
            PAudioTheme(darkTheme = dark) {
                ProfilesContent(
                    ProfilesUiState(loading = false, groups = groups),
                    startWithCreate = false, suggestedName = "Buds", actions = ProfilesActions(),
                )
            }
        }
        rule.assertClickablesAreLabelledAndLargeEnough()
    }

    @Test
    fun list_lightTheme() = list(dark = false)

    @Test
    fun list_darkTheme() = list(dark = true)

    @Test
    fun detail_withHistoryAndColourOverride_darkTheme() {
        val state = ProfileDetailUiState(
            loading = false, profileId = 11, name = "Buds", listenerId = 1, listenerName = "Me",
            headset = HeadsetLabel.Named("Buds"),
            tests = listOf(TestRow(1, 1_700_000_000_000, TestMode.QUICK, true)),
            seedColor = 0xFF3F6FB5.toInt(), seedOverridden = true,
            freeSlots = listOf(SlotOption(null, HeadsetLabel.AnyHeadphones)),
        )
        rule.setContent { PAudioTheme(darkTheme = true) { ProfileDetailContent(state, DetailActions()) } }
        rule.assertClickablesAreLabelledAndLargeEnough()
    }
}
