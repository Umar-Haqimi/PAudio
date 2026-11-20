package my.umarh.paudio.service.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import my.umarh.paudio.service.HeadsetPromptSink
import my.umarh.paudio.service.ServiceFlags

/**
 * F17: when a processable headset has no profile, post "Create profile" / "Use <Any headphones profile>" /
 * "Don't ask for this device". Each headset is prompted at most once per process ([shownKeys], shared across
 * service restarts) and never again once the user chose "Don't ask" (persisted in [flags]); the prompt is cleared as
 * soon as the situation is gone, and route flapping or reconnects do not re-post it.
 */
internal class UnknownHeadsetPrompter(
    private val resolution: Flow<ActiveProfile>,
    private val sink: HeadsetPromptSink,
    private val flags: ServiceFlags,
    private val shownKeys: MutableSet<String>,
) {
    suspend fun run() {
        resolution
            .map { a ->
                when (a) {
                    ActiveProfile.Loading -> null
                    is ActiveProfile.NoProfile -> a
                    else -> Unit
                }
            }
            .distinctUntilChanged { old, new ->
                // Only the headset identity matters, not the offered profile.
                (old is ActiveProfile.NoProfile && new is ActiveProfile.NoProfile &&
                    old.device.headsetKey == new.device.headsetKey) || old == new
            }
            .collect { a ->
                when (a) {
                    null -> Unit
                    is ActiveProfile.NoProfile -> {
                        val key = a.device.headsetKey.value
                        if (key !in flags.ignoredHeadsetKeys && shownKeys.add(key)) sink.showUnknown(key, a.anyHeadphones)
                    }
                    else -> sink.clear()
                }
            }
    }
}
