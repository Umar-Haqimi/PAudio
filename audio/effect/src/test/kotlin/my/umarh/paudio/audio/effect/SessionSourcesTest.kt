package my.umarh.paudio.audio.effect

import android.app.Application
import android.content.Intent
import android.media.audiofx.AudioEffect
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import my.umarh.paudio.core.model.SessionEvent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class SessionSourcesTest {
    private val open = AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION
    private val close = AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION

    @Test
    fun parseValid() {
        assertThat(parseSessionIntent(open, 7, "x.y", "me")).isEqualTo(SessionEvent.Open(7, "x.y"))
        assertThat(parseSessionIntent(close, 7, "x.y", "me")).isEqualTo(SessionEvent.Close(7, "x.y"))
    }

    @Test
    fun parseRejectsInvalid() {
        assertThat(parseSessionIntent(open, 0, "x", "me")).isNull()
        assertThat(parseSessionIntent(open, -1, "x", "me")).isNull()
        assertThat(parseSessionIntent(open, 3, null, "me")).isNull()
        assertThat(parseSessionIntent(open, 3, "  ", "me")).isNull()
        assertThat(parseSessionIntent(open, 3, "me", "me")).isNull() // own package (P0)
        assertThat(parseSessionIntent("android.intent.action.VIEW", 3, "x", "me")).isNull()
        assertThat(parseSessionIntent(null, 3, "x", "me")).isNull()
    }

    private fun intent(action: String, session: Int, pkg: String?) =
        Intent(action).putExtra(AudioEffect.EXTRA_AUDIO_SESSION, session).apply {
            if (pkg != null) putExtra(AudioEffect.EXTRA_PACKAGE_NAME, pkg)
        }

    @Test
    fun broadcastReceiverRegisteredOnlyWhileCollected() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val source = BroadcastSessionSource(app)
        val baseline = shadowOf(app).registeredReceivers.size
        source.events.test {
            assertThat(shadowOf(app).registeredReceivers).hasSize(baseline + 1)
            app.sendBroadcast(intent(open, 42, "a.b"))
            app.sendBroadcast(intent(open, 43, app.packageName))
            app.sendBroadcast(intent(open, 0, "a.b"))
            app.sendBroadcast(intent(open, 44, null))
            app.sendBroadcast(intent(close, 42, "a.b"))
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertThat(awaitItem()).isEqualTo(SessionEvent.Open(42, "a.b"))
            assertThat(awaitItem()).isEqualTo(SessionEvent.Close(42, "a.b"))
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(shadowOf(app).registeredReceivers).hasSize(baseline)
    }

    @Test
    fun legacyEmitsGlobalSession() = runTest {
        LegacySessionSource().events.test {
            assertThat(awaitItem()).isEqualTo(SessionEvent.Open(0, "android"))
            awaitComplete()
        }
    }
}
