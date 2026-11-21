package my.umarh.paudio.feature.hearingtest.di

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import my.umarh.paudio.audio.tone.AudioFocusMonitor
import my.umarh.paudio.audio.tone.FocusEvent
import my.umarh.paudio.feature.hearingtest.Clock
import my.umarh.paudio.feature.hearingtest.FocusSource
import my.umarh.paudio.feature.hearingtest.R
import my.umarh.paudio.feature.hearingtest.RepositoryTestStore
import my.umarh.paudio.feature.hearingtest.SessionDispatcherFactory
import my.umarh.paudio.feature.hearingtest.TestPlatform
import my.umarh.paudio.feature.hearingtest.TestStore
import java.util.concurrent.Executors
import javax.inject.Inject

class AndroidTestPlatform @Inject constructor(
    @ApplicationContext private val context: Context,
) : TestPlatform {
    override fun outputSampleRate(): Int {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()?.takeIf { it > 0 } ?: 48_000
    }

    override fun appVersion(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    } catch (_: Exception) {
        "unknown"
    }

    override fun defaultListenerName(): String = context.getString(R.string.hearingtest_default_listener_name)

    override fun defaultProfileName(headsetName: String): String =
        headsetName.trim().ifBlank { context.getString(R.string.hearingtest_default_profile_name) }
}

class AudioFocusSource @Inject constructor(private val monitor: AudioFocusMonitor) : FocusSource {
    override fun request(): Boolean = monitor.request(exclusive = true)

    override fun reacquire(): Boolean {
        monitor.abandon()
        return monitor.request(exclusive = true)
    }

    override fun abandon() = monitor.abandon()

    override val events: Flow<FocusEvent> get() = monitor.latest.filterNotNull()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class HearingTestBindsModule {
    @Binds abstract fun testStore(impl: RepositoryTestStore): TestStore

    @Binds abstract fun platform(impl: AndroidTestPlatform): TestPlatform

    @Binds abstract fun focus(impl: AudioFocusSource): FocusSource
}

@Module
@InstallIn(SingletonComponent::class)
object HearingTestProvidesModule {
    @Provides
    fun clock(): Clock = object : Clock {
        override fun wallMs(): Long = System.currentTimeMillis()
        override fun monotonicMs(): Long = SystemClock.elapsedRealtime()
    }

    /** A fresh dedicated single-thread dispatcher per test session (SPEC §5.3); closed by the ViewModel. */
    @Provides
    fun dispatcherFactory(): SessionDispatcherFactory = SessionDispatcherFactory {
        Executors.newSingleThreadExecutor { r -> Thread(r, "paudio-hearingtest").apply { isDaemon = true } }
            .asCoroutineDispatcher()
    }
}
