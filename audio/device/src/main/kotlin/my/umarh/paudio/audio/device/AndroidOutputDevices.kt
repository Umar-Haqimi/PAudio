package my.umarh.paudio.audio.device

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import my.umarh.paudio.core.model.OutputDevice
import my.umarh.paudio.core.model.OutputDevices
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The current media output. API 33+: the platform's answer for media attributes; below: priority heuristic over
 * all output devices. Re-evaluated on every device add/remove. Only a 5-char Bluetooth address suffix is kept.
 */
@Singleton
class AndroidOutputDevices @Inject constructor(@ApplicationContext context: Context) : OutputDevices {
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val _current = MutableStateFlow<OutputDevice?>(compute())

    override val current: StateFlow<OutputDevice?> = _current

    init {
        am.registerAudioDeviceCallback(
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refresh()
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refresh()
            },
            Handler(Looper.getMainLooper()),
        )
    }

    private fun refresh() {
        _current.value = compute()
    }

    private fun compute(): OutputDevice? {
        val infos: List<AudioDeviceInfo> = try {
            if (Build.VERSION.SDK_INT >= 33) {
                val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                am.getAudioDevicesForAttributes(attrs)
            } else {
                am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
            }
        } catch (_: RuntimeException) {
            emptyList()
        }
        return DeviceMapping.pickCurrent(
            infos.map { DeviceCandidate(it.type, it.productName?.toString().orEmpty(), it.address.orEmpty()) },
        )
    }
}
