package my.umarh.paudio.audio.device

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.umarh.paudio.core.model.EnvSnapshot
import my.umarh.paudio.core.model.EnvironmentChecks
import javax.inject.Inject

/**
 * Reads mono audio, balance, spatializer and DND without any permission (docs/DECISIONS.md §6).
 * `master_mono` (and Samsung's `mono_audio`) / `master_balance` are hidden but readable settings keys; unset keys mean the default (off /
 * centre). Anything that throws is reported as `null` (unknown).
 */
class AndroidEnvironmentChecks @Inject constructor(
    @ApplicationContext private val context: Context,
) : EnvironmentChecks {

    override suspend fun snapshot(): EnvSnapshot = withContext(Dispatchers.IO) {
        EnvSnapshot(
            mono = readMono(),
            balance = readBalance(),
            spatializerOn = readSpatializer(),
            dndOn = readDnd(),
        )
    }

    /** AOSP `master_mono`, or Samsung's `mono_audio` (One UI keeps its own key). Either one on means mono. */
    private fun readMono(): Boolean? {
        val aosp: Boolean? = try {
            Settings.System.getInt(context.contentResolver, "master_mono", 0) == 1
        } catch (_: Exception) {
            null
        }
        val samsung = try {
            Settings.System.getInt(context.contentResolver, "mono_audio", 0) == 1
        } catch (_: Exception) {
            false // absent or unreadable: not mono
        }
        return if (samsung) true else aosp
    }

    private fun readBalance(): Float? = try {
        Settings.System.getFloat(context.contentResolver, "master_balance", 0f)
    } catch (_: Exception) {
        null
    }

    private fun readSpatializer(): Boolean? {
        if (Build.VERSION.SDK_INT < 32) return false
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val sp = am.spatializer
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(48_000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
            sp.isEnabled && sp.isAvailable && sp.canBeSpatialized(attrs, fmt)
        } catch (_: Exception) {
            null
        }
    }

    private fun readDnd(): Boolean? = try {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        when (nm.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_ALL -> false
            NotificationManager.INTERRUPTION_FILTER_UNKNOWN -> null
            else -> true
        }
    } catch (_: Exception) {
        null
    }
}
