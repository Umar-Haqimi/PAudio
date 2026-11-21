package my.umarh.paudio.feature.profiles

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import my.umarh.paudio.core.model.OutputDeviceType
import my.umarh.paudio.data.transfer.ConflictPolicy
import my.umarh.paudio.data.transfer.ImportErrorReason
import my.umarh.paudio.data.transfer.ImportResult
import my.umarh.paudio.designsystem.PAudioTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfilesScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val groups = listOf(
        ListenerGroup(
            1, "Me", true,
            listOf(ProfileItem(10, "Any headphones", HeadsetLabel.AnyHeadphones), ProfileItem(11, "Buds", HeadsetLabel.Named("Buds"))),
        ),
    )

    private fun showList(state: ProfilesUiState, actions: ProfilesActions = ProfilesActions()) {
        rule.setContent {
            PAudioTheme(darkTheme = false) { ProfilesContent(state, startWithCreate = false, suggestedName = "Buds", actions = actions) }
        }
    }

    @Test
    fun validationErrorIsShownInline() {
        showList(
            ProfilesUiState(
                loading = false,
                groups = groups,
                message = ProfilesMessage.ImportFailed(ImportErrorReason.UNSUPPORTED_VERSION),
            ),
        )
        rule.onNodeWithTag(TAG_MESSAGE).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.profiles_import_error_version)).assertIsDisplayed()
    }

    @Test
    fun conflictDialog_offersReplaceAndImportAsAny_andReportsTheChoice() {
        val chosen = mutableListOf<ConflictPolicy>()
        showList(
            ProfilesUiState(
                loading = false,
                groups = groups,
                conflict = ImportResult.Conflict(11, "Buds", anySlot = false),
                conflictOptions = listOf(ConflictPolicy.REPLACE, ConflictPolicy.IMPORT_AS_ANY),
            ),
            ProfilesActions(onResolveConflict = { chosen += it }),
        )
        rule.onNodeWithText(context.getString(R.string.profiles_conflict_title)).assertIsDisplayed()
        rule.onNodeWithTag(TAG_CONFLICT_REPLACE).performClick()
        rule.onNodeWithTag(TAG_CONFLICT_AS_ANY).performClick()
        assertThat(chosen).containsExactly(ConflictPolicy.REPLACE, ConflictPolicy.IMPORT_AS_ANY).inOrder()
    }

    @Test
    fun conflictOnAnySlot_offersReplaceAny() {
        val chosen = mutableListOf<ConflictPolicy>()
        showList(
            ProfilesUiState(
                loading = false,
                groups = groups,
                conflict = ImportResult.Conflict(10, "Any headphones", anySlot = true),
                conflictOptions = listOf(ConflictPolicy.REPLACE_ANY),
            ),
            ProfilesActions(onResolveConflict = { chosen += it }),
        )
        rule.onNodeWithTag(TAG_CONFLICT_REPLACE_ANY).performClick()
        assertThat(chosen).containsExactly(ConflictPolicy.REPLACE_ANY)
    }

    @Test
    fun createForHeadset_isDisabledOnSpeaker_andEnabledForEarphones() {
        showList(ProfilesUiState(loading = false, groups = groups, currentStatus = CurrentHeadsetStatus.NOT_PROCESSABLE))
        rule.onNodeWithTag(TAG_CREATE_CURRENT).assertIsNotEnabled()
        rule.onNodeWithText(context.getString(R.string.profiles_current_speaker)).assertIsDisplayed()
    }

    @Test
    fun createForHeadset_enabledWhenConnected() {
        showList(
            ProfilesUiState(
                loading = false,
                groups = groups,
                current = my.umarh.paudio.core.model.OutputDevice(OutputDeviceType.BLUETOOTH_A2DP, "Buds", "AB:CD"),
                currentStatus = CurrentHeadsetStatus.READY,
            ),
        )
        rule.onNodeWithTag(TAG_CREATE_CURRENT).assertIsEnabled()
    }

    @Test
    fun listShowsGroupsAndTheAnyHeadphonesProfile() {
        showList(ProfilesUiState(loading = false, groups = groups))
        rule.onNodeWithText("Me").assertIsDisplayed()
        rule.onAllNodesWithText("Buds", useUnmergedTree = true).assertCountEquals(2)
        rule.onNodeWithText(context.getString(R.string.profiles_listener_active)).assertIsDisplayed()
    }

    // ------------------------------------------------------------------ detail

    private fun showDetail(state: ProfileDetailUiState, actions: DetailActions = DetailActions()) {
        rule.setContent { PAudioTheme(darkTheme = false) { ProfileDetailContent(state, actions) } }
    }

    private val detail = ProfileDetailUiState(
        loading = false,
        profileId = 11,
        name = "Buds profile",
        listenerId = 1,
        listenerName = "Me",
        headset = HeadsetLabel.Named("Buds"),
        freeSlots = listOf(SlotOption(null, HeadsetLabel.AnyHeadphones)),
    )

    @Test
    fun delete_needsConfirmation() {
        var deleted = 0
        showDetail(detail, DetailActions(onDelete = { deleted++ }))
        rule.onNodeWithTag(TAG_DELETE).performScrollTo().performClick()
        assertThat(deleted).isEqualTo(0)
        rule.onNodeWithText(context.getString(R.string.profile_dialog_delete_title, "Buds profile")).assertIsDisplayed()
        rule.onNodeWithTag(TAG_CONFIRM_DELETE).performClick()
        assertThat(deleted).isEqualTo(1)
    }

    @Test
    fun retestOffer_afterMove_callsRetest() {
        var retests = 0
        var dismissed = 0
        showDetail(detail.copy(offerRetest = true), DetailActions(onRetest = { retests++ }, onDismissRetest = { dismissed++ }))
        rule.onNodeWithText(context.getString(R.string.profile_dialog_retest_title)).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.profile_dialog_retest_now)).performClick()
        assertThat(retests).isEqualTo(1)
        assertThat(dismissed).isEqualTo(1)
    }

    @Test
    fun exportButton_isWiredToTheAction() {
        var exported = 0
        showDetail(detail, DetailActions(onExport = { exported++ }))
        rule.onNodeWithTag(TAG_EXPORT).performScrollTo().performClick()
        assertThat(exported).isEqualTo(1)
    }

    @Test
    fun emptyHistory_invitesTheFirstTest() {
        showDetail(detail)
        rule.onNodeWithText(context.getString(R.string.profile_detail_no_test)).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.profile_action_first_test)).assertIsDisplayed()
    }
}
