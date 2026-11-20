package my.umarh.paudio.service.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import my.umarh.paudio.service.HeadsetPromptSink

/**
 * F17: when a processable headset has no profile, post "Create profile" / "Use <Any headphones profile>" once per
 * (headset, offered profile); clear the prompt as soon as the situation is gone. A dismissed prompt is not re-posted
 * until the situation changes.
 */
internal class UnknownHeadsetPrompter(
    private val resolution: Flow<ActiveProfile>,
    private val sink: HeadsetPromptSink,
) {
    private data class Spec(val key: String, val anyId: Long?)

    suspend fun run() {
        resolution
            .map { a ->
                when (a) {
                    ActiveProfile.Loading -> null
                    is ActiveProfile.NoProfile -> Spec(a.device.headsetKey.value, a.anyHeadphones?.id) to a
                    else -> Spec("", null) to null
                }
            }
            .distinctUntilChanged { old, new -> old?.first == new?.first }
            .collect { pair ->
                val a = pair?.second
                if (a == null) {
                    if (pair != null) sink.clear()
                } else {
                    sink.showUnknown(pair.first.key, a.anyHeadphones)
                }
            }
    }
}
