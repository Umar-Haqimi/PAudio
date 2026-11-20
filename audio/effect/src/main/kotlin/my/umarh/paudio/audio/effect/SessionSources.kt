package my.umarh.paudio.audio.effect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.audiofx.AudioEffect
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import my.umarh.paudio.core.model.SessionEvent
import my.umarh.paudio.core.model.SessionSource

/** Which kind of effect instances the engine owns (SPEC §2.2 #14: mutually exclusive). */
enum class EngineMode { SESSION, LEGACY }

/** Chooses the [SessionSource] for a mode. */
fun interface SessionSourceFactory {
    fun create(mode: EngineMode): SessionSource
}

/** Package name used for the global-mix pseudo session in Legacy mode. */
const val LEGACY_PACKAGE = "android"

/**
 * Validates an OPEN/CLOSE broadcast (SPEC §6): action must be one of the two, session an Int > 0,
 * package non-null and non-blank, and never PAudio's own package (SPEC §2.2 #1: no self-colouring).
 * Pure so it can be unit-tested without Android.
 */
fun parseSessionIntent(action: String?, session: Int, pkg: String?, ownPackage: String): SessionEvent? {
    if (session <= 0) return null
    if (pkg.isNullOrBlank()) return null
    if (pkg == ownPackage) return null
    return when (action) {
        AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> SessionEvent.Open(session, pkg)
        AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> SessionEvent.Close(session, pkg)
        else -> null
    }
}

/**
 * Context-registered receiver (SPEC §2.2 #9): manifest receivers never get these implicit broadcasts on
 * API 26+. `RECEIVER_EXPORTED` is required because the broadcasts come from other apps. Registered only while
 * the flow is collected.
 */
class BroadcastSessionSource(private val context: Context) : SessionSource {
    override val events: Flow<SessionEvent> = callbackFlow {
        val own = context.packageName
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                intent ?: return
                val event = parseSessionIntent(
                    action = intent.action,
                    session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0),
                    pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME),
                    ownPackage = own,
                ) ?: return
                trySend(event)
            }
        }
        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        awaitClose { runCatching { context.unregisterReceiver(receiver) } }
    }
}

/** Legacy mode: one pseudo session 0 = the global output mix. */
class LegacySessionSource : SessionSource {
    override val events: Flow<SessionEvent> = flowOf(SessionEvent.Open(0, LEGACY_PACKAGE))
}
