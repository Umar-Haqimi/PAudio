package my.umarh.paudio.feature.profiles

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import my.umarh.paudio.data.transfer.ConflictPolicy
import my.umarh.paudio.data.transfer.ImportErrorReason
import my.umarh.paudio.data.transfer.ImportResult
import my.umarh.paudio.testing.FakeOutputDevices
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Scripted [ProfileTransfer]: returns queued import results and records every call. */
class FakeTransfer : ProfileTransfer {
    val importResults = ArrayDeque<ImportResult>()
    val importCalls = mutableListOf<Pair<Int, ConflictPolicy>>()
    var exported: MutableList<Long> = mutableListOf()
    var exportJson: String? = """{"schemaVersion":1}"""

    override suspend fun export(profileId: Long): String? {
        exported += profileId
        return exportJson
    }

    override suspend fun import(bytes: ByteArray, policy: ConflictPolicy): ImportResult {
        importCalls += bytes.size to policy
        return importResults.removeFirst()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ProfilesViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val db = FakeDb()
    private val settings = FakeSettingsRepo()
    private val devices = FakeOutputDevices()
    private val transfer = FakeTransfer()

    private fun vm(createForCurrent: Boolean = false) = ProfilesViewModel(
        SavedStateHandle(mapOf("createForCurrent" to createForCurrent)),
        FakeProfileRepo(db),
        FakeListenerRepo(db),
        FakeHeadsetRepo(db),
        settings,
        devices,
        transfer,
    )

    private fun TestScope.settle() = repeat(3) {
        advanceUntilIdle()
        runCurrent()
    }

    private fun TestScope.collect(vm: ProfilesViewModel): MutableStateFlow<ProfilesUiState> {
        val out = MutableStateFlow(ProfilesUiState())
        backgroundScope.launch { vm.uiState.collect { out.value = it } }
        settle()
        return out
    }

    @Test
    fun listIsGroupedByListener_withAnyHeadphonesAndActiveMarker() = runTest(dispatcher) {
        val me = db.addListener("Me")
        val sam = db.addListener("Sam")
        val buds = db.addHeadset("BT|Buds|AB:CD", "Buds")
        db.addProfile(me.id, null, "Any headphones")
        db.addProfile(me.id, buds.id, "Buds")
        db.addProfile(sam.id, buds.id, "Sam buds")
        settings.active.value = sam.id
        val out = collect(vm())

        val groups = out.value.groups
        assertThat(groups.map { it.name }).containsExactly("Me", "Sam").inOrder()
        assertThat(groups.map { it.isActive }).containsExactly(false, true).inOrder()
        // "Any headphones" sorts first, then the headset-bound profiles.
        assertThat(groups[0].profiles.map { it.headset }).containsExactly(HeadsetLabel.AnyHeadphones, HeadsetLabel.Named("Buds")).inOrder()
        assertThat(groups[0].hasAnyProfile).isTrue()
        assertThat(groups[1].hasAnyProfile).isFalse()
    }

    @Test
    fun createForCurrentHeadset_bindsTheProfileAndOpensIt_thenReportsExisting() = runTest(dispatcher) {
        val me = db.addListener("Me")
        devices.set(FakeOutputDevices.BLUETOOTH_BUDS)
        settings.strength.value = 0.8f
        val v = vm()
        val out = collect(v)
        val opened = mutableListOf<ProfilesEvent>()
        backgroundScope.launch { v.events.collect { opened += it } }

        assertThat(out.value.currentStatus).isEqualTo(CurrentHeadsetStatus.READY)
        v.createForCurrentHeadset(me.id, "  My buds ")
        settle()

        val created = db.profiles.value.single()
        assertThat(created.name).isEqualTo("My buds")
        assertThat(created.headsetId).isNotNull()
        assertThat(created.compStrength).isEqualTo(0.8f) // new profiles start from the default strength
        assertThat(opened).containsExactly(ProfilesEvent.OpenProfile(created.id))

        v.createForCurrentHeadset(me.id, "Again")
        settle()
        assertThat(db.profiles.value).hasSize(1)
        assertThat(out.value.message).isEqualTo(ProfilesMessage.ProfileExists)
    }

    @Test
    fun createForCurrentHeadset_isIgnoredOnSpeaker() = runTest(dispatcher) {
        val me = db.addListener("Me")
        devices.set(FakeOutputDevices.SPEAKER)
        val v = vm()
        val out = collect(v)
        assertThat(out.value.currentStatus).isEqualTo(CurrentHeadsetStatus.NOT_PROCESSABLE)
        v.createForCurrentHeadset(me.id, "x")
        settle()
        assertThat(db.profiles.value).isEmpty()
    }

    @Test
    fun listeners_createRenameSwitchAndRejectDuplicates() = runTest(dispatcher) {
        val me = db.addListener("Me")
        val v = vm()
        val out = collect(v)

        v.createListener("Sam")
        settle()
        val sam = db.listeners.value.first { it.name == "Sam" }
        assertThat(db.profiles.value.single { it.listenerId == sam.id }.headsetId).isNull() // gets its Any profile

        v.createListener("sam")
        settle()
        assertThat(db.listeners.value).hasSize(2)
        assertThat(out.value.message).isEqualTo(ProfilesMessage.ListenerExists)

        v.renameListener(sam.id, "Samira")
        v.setActiveListener(sam.id)
        settle()
        assertThat(db.listeners.value.first { it.id == sam.id }.name).isEqualTo("Samira")
        assertThat(settings.active.value).isEqualTo(sam.id)
        assertThat(out.value.groups.single { it.isActive }.name).isEqualTo("Samira")
        assertThat(me.id).isNotEqualTo(sam.id)
    }

    // ------------------------------------------------------------------ import

    @Test
    fun import_success_showsMessageAndOpensTheProfile() = runTest(dispatcher) {
        val me = db.addListener("Me")
        val imported = db.addProfile(me.id, null, "Imported")
        transfer.importResults += ImportResult.Success(imported.id)
        val v = vm()
        val out = collect(v)
        val events = mutableListOf<ProfilesEvent>()
        backgroundScope.launch { v.events.collect { events += it } }

        v.importBytes(ByteArray(10))
        settle()

        assertThat(out.value.message).isEqualTo(ProfilesMessage.Imported("Imported", replaced = false))
        assertThat(events).containsExactly(ProfilesEvent.OpenProfile(imported.id))
        assertThat(transfer.importCalls).containsExactly(10 to ConflictPolicy.ASK)
    }

    @Test
    fun import_validationErrorIsShownInline() = runTest(dispatcher) {
        transfer.importResults += ImportResult.Error(ImportErrorReason.UNSUPPORTED_VERSION, "schema 9")
        val v = vm()
        val out = collect(v)
        v.importBytes(ByteArray(3))
        settle()
        assertThat(out.value.message).isEqualTo(ProfilesMessage.ImportFailed(ImportErrorReason.UNSUPPORTED_VERSION))
        assertThat(out.value.conflict).isNull()
        assertThat(out.value.importing).isFalse()
        // The message string exists for every reason.
        ImportErrorReason.entries.forEach { assertThat(importErrorRes(it)).isNotEqualTo(0) }
        v.dismissMessage()
        settle()
        assertThat(out.value.message).isNull()
    }

    @Test
    fun import_unreadableFile_isAnIoError() = runTest(dispatcher) {
        val v = vm()
        val out = collect(v)
        v.importBytes(null)
        settle()
        assertThat(out.value.message).isEqualTo(ProfilesMessage.ImportFailed(ImportErrorReason.IO))
        assertThat(transfer.importCalls).isEmpty()
    }

    @Test
    fun import_conflict_replace() = runTest(dispatcher) {
        val me = db.addListener("Me")
        val existing = db.addProfile(me.id, null, "Old")
        val replacement = db.addProfile(me.id, null, "New", id = 900)
        transfer.importResults += ImportResult.Conflict(existing.id, "Old", anySlot = false)
        transfer.importResults += ImportResult.Success(replacement.id, replacedProfileId = existing.id)
        val v = vm()
        val out = collect(v)

        v.importBytes(ByteArray(5))
        settle()
        assertThat(out.value.conflict?.existingProfileName).isEqualTo("Old")
        assertThat(out.value.conflictOptions).containsExactly(ConflictPolicy.REPLACE, ConflictPolicy.IMPORT_AS_ANY).inOrder()

        v.resolveConflict(ConflictPolicy.REPLACE)
        settle()
        assertThat(transfer.importCalls.map { it.second }).containsExactly(ConflictPolicy.ASK, ConflictPolicy.REPLACE).inOrder()
        assertThat(transfer.importCalls.map { it.first }.toSet()).containsExactly(5) // same bytes re-used
        assertThat(out.value.conflict).isNull()
        assertThat(out.value.message).isEqualTo(ProfilesMessage.Imported("New", replaced = true))
    }

    @Test
    fun import_conflict_importAsAny_thenReplaceAny() = runTest(dispatcher) {
        val me = db.addListener("Me")
        val any = db.addProfile(me.id, null, "Any headphones")
        val result = db.addProfile(me.id, db.addHeadset("k").id, "Landed")
        transfer.importResults += ImportResult.Conflict(result.id, "Buds", anySlot = false)
        transfer.importResults += ImportResult.Conflict(any.id, "Any headphones", anySlot = true)
        transfer.importResults += ImportResult.Success(result.id, replacedProfileId = any.id)
        val v = vm()
        val out = collect(v)

        v.importBytes(ByteArray(7))
        settle()
        v.resolveConflict(ConflictPolicy.IMPORT_AS_ANY)
        settle()
        // "Any headphones" is taken as well: the only sensible resolution is REPLACE_ANY.
        assertThat(out.value.conflict?.anySlot).isTrue()
        assertThat(out.value.conflictOptions).containsExactly(ConflictPolicy.REPLACE_ANY)

        v.resolveConflict(ConflictPolicy.REPLACE_ANY)
        settle()
        assertThat(transfer.importCalls.map { it.second })
            .containsExactly(ConflictPolicy.ASK, ConflictPolicy.IMPORT_AS_ANY, ConflictPolicy.REPLACE_ANY).inOrder()
        assertThat(out.value.conflict).isNull()
        assertThat(out.value.message).isInstanceOf(ProfilesMessage.Imported::class.java)
    }

    @Test
    fun import_conflictOnAnySlotOfAFileWithoutHeadset_offersReplaceOnly() {
        val c = ImportResult.Conflict(1, "Any headphones", anySlot = true)
        assertThat(ProfilesViewModel.conflictOptions(c, ConflictPolicy.ASK)).containsExactly(ConflictPolicy.REPLACE)
    }

    @Test
    fun import_conflictCanBeDismissed_andDropsTheBytes() = runTest(dispatcher) {
        transfer.importResults += ImportResult.Conflict(1, "X", anySlot = false)
        val v = vm()
        val out = collect(v)
        v.importBytes(ByteArray(2))
        settle()
        assertThat(out.value.conflict).isNotNull()
        v.dismissConflict()
        settle()
        assertThat(out.value.conflict).isNull()
        v.resolveConflict(ConflictPolicy.REPLACE) // nothing to import any more
        settle()
        assertThat(transfer.importCalls).hasSize(1)
    }

    @Test
    fun deepLinkFlag_isReadFromTheRoute() {
        assertThat(vm(createForCurrent = true).startWithCreate).isTrue()
        assertThat(vm().startWithCreate).isFalse()
    }

    @Test
    fun suggestedName_usesTheProductName() {
        devices.set(FakeOutputDevices.BLUETOOTH_BUDS)
        assertThat(vm().suggestedName()).isEqualTo("Test Buds")
        devices.set(null)
        assertThat(vm().suggestedName()).isNull()
    }
}
