package my.umarh.paudio.feature.hearingtest

import kotlinx.coroutines.flow.first
import my.umarh.paudio.core.model.OutputDevice
import my.umarh.paudio.data.repo.HeadsetRepository
import my.umarh.paudio.data.repo.HearingTestRepository
import my.umarh.paudio.data.repo.ListenerRepository
import my.umarh.paudio.data.repo.ProfileRepository
import my.umarh.paudio.data.repo.ProfileWriteResult
import my.umarh.paudio.data.settings.SettingsRepository
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
 * Not yet one transaction: the listener / headset / profile inserts and the test save are separate writes (a
 * `:data` transaction API is being added separately and will be wired here).
 */
class RepositoryTestStore @Inject constructor(
    private val listeners: ListenerRepository,
    private val profiles: ProfileRepository,
    private val headsets: HeadsetRepository,
    private val tests: HearingTestRepository,
    private val settings: SettingsRepository,
    private val platform: TestPlatform,
) : TestStore {

    override suspend fun check(device: OutputDevice?, requestedProfileId: Long?): TargetCheck {
        if (device == null || !device.isProcessable) return TargetCheck.NotProcessable
        if (requestedProfileId == null) return TargetCheck.Ok
        val profile = profiles.get(requestedProfileId) ?: return TargetCheck.ProfileMissing
        val headset = profile.headset
        if (headset != null && headset.key != device.headsetKey.value) return TargetCheck.WrongHeadset(headset.displayName)
        return TargetCheck.Ok
    }

    override suspend fun save(request: SaveRequest): Long {
        val profileId = resolveProfileId(request)
        return tests.save(
            test = request.test.copy(profileId = profileId),
            thresholds = request.thresholds,
            trials = request.trials,
            headsetKey = request.device.headsetKey,
        )
    }

    override suspend fun listenerFor(requestedProfileId: Long?): Long? {
        if (requestedProfileId != null) return profiles.get(requestedProfileId)?.profile?.listenerId
        val active = settings.activeListenerId.first()
        if (active != null && listeners.get(active) != null) return active
        return listeners.observeAll().first().firstOrNull()?.id
    }

    private suspend fun resolveProfileId(request: SaveRequest): Long {
        request.requestedProfileId?.let { id ->
            if (profiles.get(id) != null) return id
            throw ProfileMissingException()
        }
        val device = request.device
        val listenerId = activeListenerId()
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

    private suspend fun activeListenerId(): Long {
        val active = settings.activeListenerId.first()
        if (active != null && listeners.get(active) != null) return active
        val id = listeners.observeAll().first().firstOrNull()?.id ?: listeners.create(platform.defaultListenerName())
        settings.setActiveListenerId(id)
        return id
    }
}
