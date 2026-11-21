package my.umarh.paudio.feature.hearingtest

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import my.umarh.paudio.core.model.OutputDevice
import my.umarh.paudio.data.repo.HeadsetRepository
import my.umarh.paudio.data.repo.HearingTestRepository
import my.umarh.paudio.data.repo.ListenerRepository
import my.umarh.paudio.data.repo.ProfileRepository
import my.umarh.paudio.data.repo.ProfileWriteResult
import my.umarh.paudio.data.settings.SettingsRepository
import my.umarh.paudio.data.tx.DataTransactions
import javax.inject.Inject

/**
 * Profile resolution + persistence of a finished test (DECISIONS §23, §27).
 *
 * `HearingTestRoute(profileId = null)` means "the active listener's profile for the headset that was connected when
 * the test started": the listener (and a default one if none exists yet), the headset row and the profile are created
 * HERE, at save time, so an aborted test never leaves empty rows behind. Speakers and other non-processable outputs
 * never get a profile ([check] blocks them before the test starts).
 *
 * A requested profile that was deleted during the test is NOT silently replaced by the active listener's profile
 * (it may belong to another person): [save] throws [ProfileMissingException] and the user decides.
 *
 * Atomic (DECISIONS §28.3): the listener / headset / profile inserts and the test save run in ONE `:data` transaction
 * (all or nothing), inside `NonCancellable` so leaving the screen mid-save cannot cut it short. The DataStore
 * "active listener" is not part of the Room transaction: it is written only after the commit.
 */
class RepositoryTestStore @Inject constructor(
    private val listeners: ListenerRepository,
    private val profiles: ProfileRepository,
    private val headsets: HeadsetRepository,
    private val tests: HearingTestRepository,
    private val settings: SettingsRepository,
    private val platform: TestPlatform,
    private val tx: DataTransactions,
) : TestStore {

    override suspend fun check(device: OutputDevice?, requestedProfileId: Long?): TargetCheck {
        if (device == null || !device.isProcessable) return TargetCheck.NotProcessable
        if (requestedProfileId == null) return TargetCheck.Ok
        val profile = profiles.get(requestedProfileId) ?: return TargetCheck.ProfileMissing
        val headset = profile.headset
        if (headset != null && headset.key != device.headsetKey.value) return TargetCheck.WrongHeadset(headset.displayName)
        return TargetCheck.Ok
    }

    override suspend fun save(request: SaveRequest): Long = withContext(NonCancellable) {
        // The active listener is read before the transaction (DataStore) and only written after the commit, so a
        // rolled-back transaction never leaves the setting pointing at a listener row that does not exist.
        val storedActive = settings.activeListenerId.first()
        var newActive: Long? = null
        val testId = tx.withTransaction {
            val profileId = request.requestedProfileId?.let { id ->
                if (profiles.get(id) == null) throw ProfileMissingException()
                id
            } ?: run {
                val listenerId = existingListener(storedActive) ?: listeners.create(platform.defaultListenerName())
                if (listenerId != storedActive) newActive = listenerId
                profileFor(listenerId, request.device)
            }
            tests.save(
                test = request.test.copy(profileId = profileId),
                thresholds = request.thresholds,
                trials = request.trials,
                headsetKey = request.device.headsetKey,
            )
        }
        newActive?.let { settings.setActiveListenerId(it) }
        testId
    }

    override suspend fun listenerFor(requestedProfileId: Long?): Long? {
        if (requestedProfileId != null) return profiles.get(requestedProfileId)?.profile?.listenerId
        return existingListener(settings.activeListenerId.first())
    }

    /** The stored active listener if it still exists, else the first listener; null when there is none. */
    private suspend fun existingListener(storedActive: Long?): Long? {
        if (storedActive != null && listeners.get(storedActive) != null) return storedActive
        return listeners.observeAll().first().firstOrNull()?.id
    }

    private suspend fun profileFor(listenerId: Long, device: OutputDevice): Long {
        profiles.resolveForHeadset(listenerId, device.headsetKey)?.let { return it.id }
        val headsetId = headsets.getOrCreate(device)
        return when (
            val created = profiles.create(listenerId, headsetId, platform.defaultProfileName(device.productName))
        ) {
            is ProfileWriteResult.Ok -> created.profileId
            is ProfileWriteResult.Conflict -> created.existingProfileId
            ProfileWriteResult.NotFound -> error("listener vanished while saving")
        }
    }
}
