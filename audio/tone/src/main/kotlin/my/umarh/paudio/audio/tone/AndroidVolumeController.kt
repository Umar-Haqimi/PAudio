package my.umarh.paudio.audio.tone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.shareIn
import my.umarh.paudio.core.model.VolumeController
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * STREAM_MUSIC volume (SPEC §2.2 #12, docs/DECISIONS.md §4/§5). [setIndex] never throws and always returns the
 * read-back index: safe-media-volume / CSD may refuse the request. [indexDb] is `null` when the platform curve is
 * unreliable. [changes] reports only EXTERNAL changes.
 */
@Singleton
class AndroidVolumeController(
    private val context: Context,
    private val deviceType: () -> Int,
) : VolumeController {
    @Inject constructor(@ApplicationContext context: Context) : this(context, { currentDeviceType(context) })

    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val filter = ExternalChangeFilter(am.getStreamVolume(AudioManager.STREAM_MUSIC))
    private val curves = ConcurrentHashMap<Int, List<Float?>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val minIndex: Int
        get() = if (Build.VERSION.SDK_INT >= 28) am.getStreamMinVolume(AudioManager.STREAM_MUSIC) else 0

    override val maxIndex: Int get() = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    override fun index(): Int = am.getStreamVolume(AudioManager.STREAM_MUSIC)

    override fun setIndex(i: Int): Int {
        val target = i.coerceIn(minIndex, maxIndex)
        filter.expectSelf(target)
        try {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        } catch (_: SecurityException) {
            // e.g. Do-Not-Disturb policy access quirks: report whatever is in effect.
        } catch (_: RuntimeException) {
        }
        val effective = index()
        filter.onSelfWrite(effective)
        return effective
    }

    override fun indexDb(i: Int): Float? {
        val min = minIndex
        val max = maxIndex
        if (i !in min..max) return null
        val type = deviceType()
        val curve = curves[type] ?: VolumeCurve.validate((min..max).map { raw(it, type) }).also {
            // An all-null curve may be transient (audio policy not ready); don't cache it forever.
            if (it.any { v -> v != null }) curves[type] = it
        }
        return curve.getOrNull(i - min)
    }

    private fun raw(index: Int, type: Int): Float? = try {
        am.getStreamVolumeDb(AudioManager.STREAM_MUSIC, index, type)
    } catch (_: RuntimeException) {
        null
    }

    private val rawChanges: Flow<Int> = callbackFlow {
        val main = Handler(Looper.getMainLooper())
        fun check() {
            filter.onObserved(index())?.let { trySend(it) }
        }
        val observer = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) = check()
        }
        context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) = check()
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(VOLUME_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        awaitClose {
            context.contentResolver.unregisterContentObserver(observer)
            try { context.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) { }
        }
    }

    /** External index changes only; shared so several collectors see every change once the observer is up. */
    override val changes: Flow<Int> = rawChanges.shareIn(scope, SharingStarted.WhileSubscribed(), replay = 0)

    companion object {
        const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"

        /** AudioDeviceInfo.TYPE_* of the current media output (speaker if undeterminable). */
        internal fun currentDeviceType(context: Context): Int {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= 33) {
                val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                am.getAudioDevicesForAttributes(attrs).firstOrNull()?.let { return it.type }
            }
            val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val rank = { t: Int ->
                when (t) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, 26, 27, AudioDeviceInfo.TYPE_HEARING_AID -> 0
                    AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> 1
                    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> 2
                    else -> 3
                }
            }
            return outs.minByOrNull { rank(it.type) }?.type ?: AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        }
    }
}
