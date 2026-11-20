package my.umarh.paudio.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Intent actions/extras shared by the service, its receivers and `:app`. */
object ServiceIntents {
    const val ACTION_START = "my.umarh.paudio.service.action.START"
    const val ACTION_STOP = "my.umarh.paudio.service.action.STOP"
    const val ACTION_TOGGLE_PERSONALIZED = "my.umarh.paudio.service.action.TOGGLE_PERSONALIZED"
    const val ACTION_USE_PROFILE = "my.umarh.paudio.service.action.USE_PROFILE"
    const val ACTION_IGNORE_HEADSET = "my.umarh.paudio.service.action.IGNORE_HEADSET"

    /** Extra on the launch intent of the app: what the app should do when opened from a notification. */
    const val EXTRA_ROUTE = "my.umarh.paudio.extra.ROUTE"

    /** The app should open the "create profile for the current headphones" flow. */
    const val ROUTE_CREATE_PROFILE = "create_profile"

    /** The app should start the engine (it is in the foreground, so the FGS start is allowed). */
    const val ROUTE_START_SERVICE = "start_service"

    const val EXTRA_HEADSET_KEY = "my.umarh.paudio.extra.HEADSET_KEY"
    const val EXTRA_PROFILE_ID = "my.umarh.paudio.extra.PROFILE_ID"
}

/**
 * Small flags that must be readable synchronously from receivers and survive process death.
 * (SharedPreferences, because `:data`'s DataStore is out of this milestone's scope.)
 */
interface ServiceFlags {
    /** The user pressed "Turn off" (or stopped the engine in the app): boot and sticky restarts must not revive it. */
    var userTurnedOff: Boolean

    /** Headset keys the user asked not to be prompted about ("Don't ask for this device"). */
    var ignoredHeadsetKeys: Set<String>
}

/** Unexpected failures of the use-case layer: logged without PII (class name only) and shown as a fault. */
@Singleton
class ServiceFaults @Inject constructor() {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active

    fun report(e: Throwable) {
        try {
            android.util.Log.w("PAudio", "service fault: ${e.javaClass.simpleName}")
        } catch (_: RuntimeException) {
        }
        _active.value = true
    }

    fun clear() {
        _active.value = false
    }
}

@Singleton
class SharedPrefsServiceFlags @Inject constructor(@ApplicationContext private val context: Context) : ServiceFlags {
    private val prefs by lazy { context.getSharedPreferences(FILE, Context.MODE_PRIVATE) }

    override var userTurnedOff: Boolean
        get() = try {
            prefs.getBoolean(KEY_OFF, false)
        } catch (_: RuntimeException) {
            false
        }
        set(value) {
            try {
                prefs.edit().putBoolean(KEY_OFF, value).apply()
            } catch (_: RuntimeException) {
            }
        }

    override var ignoredHeadsetKeys: Set<String>
        get() = try {
            prefs.getStringSet(KEY_IGNORED, emptySet()).orEmpty().toSet()
        } catch (_: RuntimeException) {
            emptySet()
        }
        set(value) {
            try {
                prefs.edit().putStringSet(KEY_IGNORED, value).apply()
            } catch (_: RuntimeException) {
            }
        }

    private companion object {
        const val FILE = "paudio_service"
        const val KEY_OFF = "user_turned_off"
        const val KEY_IGNORED = "ignored_headsets"
    }
}

/** Starts/stops the foreground service. [start] may throw `ForegroundServiceStartNotAllowedException` (an ISE). */
interface ServiceLauncher {
    fun start()
    fun stop()
}

@Singleton
class AndroidServiceLauncher @Inject constructor(@ApplicationContext private val context: Context) : ServiceLauncher {
    override fun start() {
        ContextCompat.startForegroundService(
            context,
            Intent(context, PAudioService::class.java).setAction(ServiceIntents.ACTION_START),
        )
    }

    override fun stop() {
        context.stopService(Intent(context, PAudioService::class.java))
    }
}

/** App label of a package for the notification; falls back to the package name when it is not visible. */
fun interface AppLabels {
    fun labelOf(pkg: String): String
}

@Singleton
class AndroidAppLabels @Inject constructor(@ApplicationContext private val context: Context) : AppLabels {
    override fun labelOf(pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().ifBlank { pkg }
    } catch (_: PackageManager.NameNotFoundException) {
        pkg
    } catch (_: RuntimeException) {
        pkg
    }
}

/** The real mixer rate (same source as `AbPlayerFactory.outputSampleRate()`): the DP band layout depends on it. */
fun interface MixerSampleRate {
    fun hz(): Int
}

@Singleton
class AndroidMixerSampleRate @Inject constructor(@ApplicationContext private val context: Context) : MixerSampleRate {
    override fun hz(): Int =
        (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
            ?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()?.takeIf { it > 0 } ?: 48_000
}

/** Whether PAudio may show notifications (runtime permission on API 33+ and the per-app switch). */
fun interface NotificationPermission {
    fun allowed(): Boolean
}

@Singleton
class AndroidNotificationPermission @Inject constructor(@ApplicationContext private val context: Context) :
    NotificationPermission {
    override fun allowed(): Boolean = try {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    } catch (_: RuntimeException) {
        true
    }
}

/**
 * Launch routes requested by notifications. `MainActivity` calls [offer] with every launch/new intent and the
 * navigation (M8b) reads [pending] and calls [consume] once it has handled it.
 */
@Singleton
class LaunchRequests @Inject constructor() {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending

    /** Returns the route carried by [intent] (one of `ServiceIntents.ROUTE_*`), or null. */
    fun offer(intent: Intent?): String? {
        val route = intent?.getStringExtra(ServiceIntents.EXTRA_ROUTE)?.takeIf { it.isNotBlank() }
        if (route != null) _pending.value = route
        return route
    }

    fun consume() {
        _pending.value = null
    }
}
