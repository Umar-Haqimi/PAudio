package my.umarh.paudio.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import my.umarh.paudio.data.settings.SettingsRepository
import my.umarh.paudio.service.di.ServiceEntryPoint
import javax.inject.Inject

enum class BootOutcome { NOT_OPTED_IN, STARTED, FALLBACK_POSTED }

/** The decision made on BOOT_COMPLETED (F23, SPEC §2.2 #10, DECISIONS §8). Separate from the receiver for tests. */
class BootLogic @Inject constructor(
    private val settings: SettingsRepository,
    private val flags: ServiceFlags,
    private val launcher: ServiceLauncher,
    private val fallback: BootFallback,
) {
    suspend fun onBoot(): BootOutcome {
        if (!settings.startOnBoot.first() || !settings.onboardingDone.first() || flags.userTurnedOff) {
            return BootOutcome.NOT_OPTED_IN
        }
        return try {
            launcher.start()
            BootOutcome.STARTED
        } catch (_: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (Android 15+ boot restrictions) is an IllegalStateException.
            fallback.postTapToStart()
            BootOutcome.FALLBACK_POSTED
        } catch (_: SecurityException) {
            fallback.postTapToStart()
            BootOutcome.FALLBACK_POSTED
        }
    }
}

/** Exported for the system's BOOT_COMPLETED only; any other action is ignored. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val logic = EntryPointAccessors.fromApplication(context.applicationContext, ServiceEntryPoint::class.java).bootLogic()
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                logic.onBoot()
            } catch (_: Exception) {
                // Never crash the boot broadcast; the user can start PAudio from the app.
            } finally {
                pending.finish()
            }
        }
    }
}

/** "Use <profile>" and other notification actions that need no UI. Not exported. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val handler = EntryPointAccessors.fromApplication(context.applicationContext, ServiceEntryPoint::class.java).actionHandler()
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                handler.handle(intent)
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }
}
