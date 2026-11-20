package my.umarh.paudio.service.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import my.umarh.paudio.core.model.OutputDevice
import my.umarh.paudio.core.model.OutputDeviceType
import my.umarh.paudio.core.model.OutputDevices
import my.umarh.paudio.data.db.ListenerEntity
import my.umarh.paudio.data.db.ProfileEntity
import my.umarh.paudio.data.repo.ListenerProfiles
import my.umarh.paudio.data.repo.ProfileRepository
import my.umarh.paudio.data.settings.SettingsRepository
import my.umarh.paudio.service.di.ServiceScope
import javax.inject.Inject
import javax.inject.Singleton

/** Which profile drives the engine for the current output route (SPEC §5.5, F17). */
sealed interface ActiveProfile {
    /** Not known yet (the first snapshot of the repositories has not arrived). */
    data object Loading : ActiveProfile

    data class Resolved(
        val device: OutputDevice,
        val profile: ProfileEntity,
        val listener: ListenerEntity,
    ) : ActiveProfile

    /**
     * A processable headset without a profile of the active listener. [anyHeadphones] is the listener's
     * "Any headphones" profile, offered by the "Use <profile>" notification action (it is NOT applied
     * automatically, F17).
     */
    data class NoProfile(val device: OutputDevice, val anyHeadphones: ProfileEntity?) : ActiveProfile

    /** Speaker / earpiece / HDMI / unknown (or no) route: never processed (SPEC §5.5). */
    data class NotProcessable(val device: OutputDevice?) : ActiveProfile
}

/** "Which headphones are these?" (SPEC §5.5): a wired/USB connect where several profiles could apply. */
data class WhichHeadphones(val device: OutputDevice, val candidates: List<ProfileEntity>)

/**
 * OutputDevices -> HeadsetKey -> profile of the active listener.
 *
 * Resolution order for a processable device (all within the active listener's profiles):
 * 1. the session answer to "Which headphones are these?" for this key (any listener's profile bound to it),
 * 2. the profile bound to exactly this headset,
 * 3. the profile remembered in the DataStore headsetKey -> profileId map ("Use <profile>"),
 * 4. otherwise [ActiveProfile.NoProfile].
 */
@Singleton
class ActiveProfileResolver @Inject constructor(
    devices: OutputDevices,
    private val settings: SettingsRepository,
    profiles: ProfileRepository,
    @ServiceScope scope: CoroutineScope,
) {
    private val overrides = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val _pending = MutableStateFlow<WhichHeadphones?>(null)

    // Connection tracking for the wired-collision question (confined to the single stateIn collector).
    private var seenFirstDevice = false
    private var lastKey: String? = null

    /** The current resolution; recomputed on any device / settings / profile change. */
    val activeProfile: StateFlow<ActiveProfile> = combine(
        devices.current,
        settings.activeListenerId,
        settings.headsetProfiles,
        profiles.observeAll(),
        overrides,
    ) { device, listenerId, map, listeners, over ->
        trackConnection(device, listenerId, listeners)
        resolveProfile(device, listenerId, map, listeners, over)
    }.stateIn(scope, SharingStarted.Eagerly, ActiveProfile.Loading)

    /** Pending question for the UI (M8b renders it); answer with [answerWhichHeadphones]. */
    val pendingQuestion: StateFlow<WhichHeadphones?> = _pending

    /**
     * Answers the pending question: use [profileId] for this connection ("remembered for the session": until the
     * wired device is disconnected). `null` keeps the default resolution.
     */
    fun answerWhichHeadphones(profileId: Long?) {
        val q = _pending.value ?: return
        _pending.value = null
        if (profileId != null && q.candidates.any { it.id == profileId }) {
            overrides.update { it + (q.device.headsetKey.value to profileId) }
        }
    }

    /** "Use <profile>" (F17): remember that [headsetKey] uses [profileId] (survives restarts). */
    suspend fun useProfileFor(headsetKey: String, profileId: Long) {
        settings.setHeadsetProfile(headsetKey, profileId)
    }

    private fun trackConnection(device: OutputDevice?, listenerId: Long?, listeners: List<ListenerProfiles>) {
        val key = device?.headsetKey?.value
        if (seenFirstDevice && key == lastKey) return
        val firstEver = !seenFirstDevice
        val previous = lastKey
        seenFirstDevice = true
        lastKey = key
        // The route changed: whatever was answered/asked for the old wired device no longer applies.
        if (previous != null) overrides.update { it - previous }
        _pending.value = null
        if (firstEver || device == null || !device.isWiredLike) return
        // A real key collision (SPEC §5.5): several non-Any profiles (of different listeners, since a listener has
        // at most one profile per headset) are bound to this very wired/USB key. Any + one wired profile is the
        // normal setup and never asks.
        val key2 = device.headsetKey.value
        val candidates = listeners.flatMap { it.profiles }.filter { it.headset?.key == key2 }.map { it.profile }
        if (candidates.size >= 2) _pending.value = WhichHeadphones(device, candidates)
    }

    companion object {
        private val OutputDevice.isWiredLike: Boolean get() = type == OutputDeviceType.WIRED || type == OutputDeviceType.USB

        private fun activeListener(listenerId: Long?, listeners: List<ListenerProfiles>): ListenerProfiles? =
            listeners.firstOrNull { it.listener.id == listenerId } ?: listeners.firstOrNull()

        /** Pure resolution (unit-tested). */
        fun resolveProfile(
            device: OutputDevice?,
            listenerId: Long?,
            headsetProfiles: Map<String, Long>,
            listeners: List<ListenerProfiles>,
            sessionOverrides: Map<String, Long> = emptyMap(),
        ): ActiveProfile {
            if (device == null || !device.isProcessable) return ActiveProfile.NotProcessable(device)
            val listener = activeListener(listenerId, listeners) ?: return ActiveProfile.NoProfile(device, null)
            val key = device.headsetKey.value
            val mine = listener.profiles
            fun byId(id: Long?) = mine.firstOrNull { it.profile.id == id }?.profile
            // A session answer to the collision question may pick another listener's profile.
            val everyone = listeners.flatMap { l -> l.profiles.map { l.listener to it.profile } }
            val answered = sessionOverrides[key]?.let { id -> everyone.firstOrNull { it.second.id == id } }
            if (answered != null) return ActiveProfile.Resolved(device, answered.second, answered.first)
            val chosen = mine.firstOrNull { it.headset?.key == key }?.profile
                ?: byId(headsetProfiles[key])
            return if (chosen != null) {
                ActiveProfile.Resolved(device, chosen, listener.listener)
            } else {
                ActiveProfile.NoProfile(device, mine.firstOrNull { it.profile.headsetId == null }?.profile)
            }
        }
    }
}
