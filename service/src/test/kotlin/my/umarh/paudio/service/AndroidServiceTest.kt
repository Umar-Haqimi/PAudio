package my.umarh.paudio.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import my.umarh.paudio.audio.effect.EngineError
import my.umarh.paudio.audio.effect.EngineState
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidServiceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val nm get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val poster by lazy { NotificationPoster(context) { it.substringAfterLast('.') } }

    @Before
    fun registerLauncherActivity() {
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName)
        val ri = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = context.packageName
                name = "${context.packageName}.MainActivity"
            }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(launch, ri)
    }

    private fun logic(
        boot: Boolean = true,
        onboarding: Boolean = true,
        off: Boolean = false,
        launcher: FakeLauncher = FakeLauncher(),
    ): Triple<BootLogic, FakeLauncher, FakeFlags> {
        val settings = FakeSettings().also { it.boot.value = boot; it.onboarding.value = onboarding }
        val flags = FakeFlags(off)
        return Triple(BootLogic(settings, flags, launcher, poster), launcher, flags)
    }

    // ------------------------------------------------------------------ BootReceiver logic

    @Test
    fun bootDoesNothingWhenNotOptedInOrOnboardingNotDoneOrUserTurnedOff() = runTest {
        for ((boot, onboarding, off) in listOf(Triple(false, true, false), Triple(true, false, false), Triple(true, true, true))) {
            val (l, launcher, _) = logic(boot, onboarding, off)
            assertThat(l.onBoot()).isEqualTo(BootOutcome.NOT_OPTED_IN)
            assertThat(launcher.starts).isEqualTo(0)
        }
        assertThat(shadowOf(nm).allNotifications).isEmpty()
    }

    @Test
    fun bootStartsTheServiceWhenAllowed() = runTest {
        val (l, launcher, _) = logic()
        assertThat(l.onBoot()).isEqualTo(BootOutcome.STARTED)
        assertThat(launcher.starts).isEqualTo(1)
        assertThat(shadowOf(nm).allNotifications).isEmpty()
    }

    @Test
    fun bootPostsTapToStartWhenTheForegroundStartIsNotAllowed() = runTest {
        val failures = listOf<RuntimeException>(
            ForegroundServiceStartNotAllowedException("boot"),
            SecurityException("denied"),
            IllegalStateException("background"),
        )
        for (failure in failures) {
            nm.cancelAll()
            val (l, launcher, _) = logic(launcher = FakeLauncher().also { it.failWith = failure })
            assertThat(l.onBoot()).isEqualTo(BootOutcome.FALLBACK_POSTED)
            assertThat(launcher.starts).isEqualTo(0)
            val n = shadowOf(nm).allNotifications.single()
            assertThat(n.channelId).isEqualTo(NotificationPoster.CHANNEL_BOOT)
            assertThat(n.extras.getCharSequence("android.text").toString()).isEqualTo("Tap to start PAudio")
            // Tapping it opens the app with the route that starts the FGS from the foreground.
            val pi = n.contentIntent
            val intent = shadowOf(pi).savedIntent
            assertThat(intent.getStringExtra(ServiceIntents.EXTRA_ROUTE)).isEqualTo(ServiceIntents.ROUTE_START_SERVICE)
            assertThat(shadowOf(pi).flags and PendingIntent.FLAG_IMMUTABLE).isNotEqualTo(0)
        }
        assertThat(nm.getNotificationChannel(NotificationPoster.CHANNEL_BOOT)!!.importance)
            .isEqualTo(NotificationManager.IMPORTANCE_HIGH)
    }

    // ------------------------------------------------------------------ state text

    private fun text(state: EngineState, hint: Boolean = false) =
        EngineStatus.textOf(state) { it.substringAfterLast('.') }.format(context) +
            (EngineStatus.hintOf(state, hint)?.let { " | " + it.format(context) } ?: "")

    @Test
    fun everyEngineStateMapsToItsCopy() {
        assertThat(text(EngineState.Off)).isEqualTo("PAudio is off")
        assertThat(text(EngineState.Starting)).isEqualTo("Starting…")
        assertThat(text(EngineState.Waiting)).isEqualTo("Waiting for a player")
        assertThat(text(EngineState.Active(listOf("com.a.spotify")))).isEqualTo("Active in spotify")
        assertThat(text(EngineState.Active(listOf("x.a", "y.b", "z.a")))).isEqualTo("Active in a, b")
        assertThat(text(EngineState.Active(listOf("p.a", "p.b", "p.c", "p.d", "p.e"))))
            .isEqualTo("Active in a, b, c and 2 more")
        assertThat(text(EngineState.Legacy(true))).isEqualTo("Legacy mode · Personalized")
        assertThat(text(EngineState.Legacy(false))).isEqualTo("Legacy mode · Original")
        assertThat(text(EngineState.Bypassed)).isEqualTo("Paused while PAudio plays its own sounds")
        assertThat(text(EngineState.Conflict))
            .isEqualTo("Another equalizer app is controlling the audio right now. Close it or turn it off to use PAudio.")
        assertThat(text(EngineState.NoProfile(BT))).isEqualTo("No profile for this headset")
        assertThat(text(EngineState.SpeakerOutput)).isEqualTo("Speaker output (not processed)")
        assertThat(text(EngineState.Error(EngineError.CREATION_FAILED)))
            .isEqualTo("This device's audio system refused PAudio's effect. Try Legacy mode in Settings.")
        assertThat(text(EngineState.Error(EngineError.SOURCE_FAILED))).contains("could not listen")
        // The no-session hint only accompanies Waiting.
        assertThat(text(EngineState.Waiting, hint = true))
            .isEqualTo("Waiting for a player | Your music app isn't announcing itself. Try Legacy mode, or check the compatibility list.")
        assertThat(text(EngineState.Conflict, hint = true)).doesNotContain("announcing")
        assertThat(context.getString(R.string.service_notification_permission_denied))
            .isEqualTo("PAudio needs its notification to keep working in the background.")
    }

    // ------------------------------------------------------------------ the persistent notification

    @Test
    fun engineNotificationShowsStateAndImmutableExplicitActions() {
        val n = poster.engineNotification(NotificationModel(EngineState.Active(listOf("a.player")), false, personalized = true))
        assertThat(n.channelId).isEqualTo(NotificationPoster.CHANNEL_ENGINE)
        assertThat(n.extras.getCharSequence("android.title").toString()).isEqualTo("PAudio · Personalized")
        assertThat(n.extras.getCharSequence("android.text").toString()).isEqualTo("Active in player")
        assertThat(n.actions.map { it.title.toString() }).containsExactly("Original", "Open app", "Turn off").inOrder()
        for (a in n.actions) {
            assertThat(shadowOf(a.actionIntent).flags and PendingIntent.FLAG_IMMUTABLE).isNotEqualTo(0)
            val i = shadowOf(a.actionIntent).savedIntent
            // Explicit: a component (own service) or the app's own launch intent (package set by the resolver).
            assertThat(i.component != null || i.`package` == context.packageName).isTrue()
        }
        val toggle = shadowOf(n.actions[0].actionIntent).savedIntent
        assertThat(toggle.action).isEqualTo(ServiceIntents.ACTION_TOGGLE_PERSONALIZED)
        assertThat(toggle.component!!.className).isEqualTo(PAudioService::class.java.name)
        assertThat(shadowOf(n.actions[2].actionIntent).savedIntent.action).isEqualTo(ServiceIntents.ACTION_STOP)
        assertThat(nm.getNotificationChannel(NotificationPoster.CHANNEL_ENGINE)!!.importance)
            .isEqualTo(NotificationManager.IMPORTANCE_LOW)

        val orig = poster.engineNotification(NotificationModel(EngineState.Waiting, false, personalized = false))
        assertThat(orig.extras.getCharSequence("android.title").toString()).isEqualTo("PAudio · Original")
        assertThat(orig.actions.first().title.toString()).isEqualTo("Personalized")

        // No profile: no Personalized/Original action.
        val none = poster.engineNotification(NotificationModel(EngineState.NoProfile(BT), false, personalized = null))
        assertThat(none.actions.map { it.title.toString() }).containsExactly("Open app", "Turn off").inOrder()
    }

    // ------------------------------------------------------------------ unknown headset prompt

    @Test
    fun unknownHeadsetNotificationOffersCreateAndUse() {
        poster.showUnknown(BT.headsetKey.value, profile(3, null, "Any headphones"))
        val n = shadowOf(nm).allNotifications.single()
        assertThat(n.channelId).isEqualTo(NotificationPoster.CHANNEL_HEADSET)
        assertThat(n.actions.map { it.title.toString() }).containsExactly("Create profile", "Don't ask for this device", "Use Any headphones").inOrder()
        val create = shadowOf(n.actions[0].actionIntent).savedIntent
        assertThat(create.getStringExtra(ServiceIntents.EXTRA_ROUTE)).isEqualTo(ServiceIntents.ROUTE_CREATE_PROFILE)
        val ignore = shadowOf(n.actions[1].actionIntent).savedIntent
        assertThat(ignore.action).isEqualTo(ServiceIntents.ACTION_IGNORE_HEADSET)
        val use = shadowOf(n.actions[2].actionIntent).savedIntent
        assertThat(use.component!!.className).isEqualTo(NotificationActionReceiver::class.java.name)
        assertThat(use.getLongExtra(ServiceIntents.EXTRA_PROFILE_ID, -1)).isEqualTo(3L)
        assertThat(shadowOf(n.actions[2].actionIntent).flags and PendingIntent.FLAG_IMMUTABLE).isNotEqualTo(0)

        poster.clear()
        assertThat(shadowOf(nm).allNotifications).isEmpty()
    }

    @Test
    fun launchRequestsCarryTheRoute() {
        val req = LaunchRequests()
        assertThat(req.offer(Intent())).isNull()
        assertThat(req.offer(Intent().putExtra(ServiceIntents.EXTRA_ROUTE, ServiceIntents.ROUTE_CREATE_PROFILE)))
            .isEqualTo(ServiceIntents.ROUTE_CREATE_PROFILE)
        assertThat(req.pending.value).isEqualTo(ServiceIntents.ROUTE_CREATE_PROFILE)
        req.consume()
        assertThat(req.pending.value).isNull()
    }
}
