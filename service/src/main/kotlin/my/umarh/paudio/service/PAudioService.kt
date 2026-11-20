package my.umarh.paudio.service

import android.app.Notification
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import my.umarh.paudio.service.engine.ActiveProfile
import my.umarh.paudio.service.engine.EngineController
import javax.inject.Inject

/**
 * The system-wide engine host: a `specialUse` foreground service (DECISIONS §8).
 *
 * It owns the engine lifecycle through [EngineController] (`setRunning(true)` on start, `false` on stop) and keeps
 * one persistent notification in sync with the engine state. `START_STICKY`: after process death the system
 * restarts it with a null intent and the controller restores everything from persisted state (the engine never
 * starts bypassed because bypass tokens only live in-process). A user "Turn off" is persisted and never revived.
 */
@AndroidEntryPoint
class PAudioService : LifecycleService() {
    @Inject lateinit var controller: EngineController

    @Inject lateinit var notifications: NotificationPoster

    @Inject lateinit var flags: ServiceFlags

    private var observer: Job? = null

    override fun onCreate() {
        super.onCreate()
        notifications.ensureChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action
        // A foreground start must be followed by startForeground quickly, whatever we decide afterwards.
        if (!enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        when {
            action == ServiceIntents.ACTION_STOP -> {
                this.flags.userTurnedOff = true
                stopSelf()
                return START_NOT_STICKY
            }
            intent == null && this.flags.userTurnedOff -> {
                // Sticky restart after the user had turned PAudio off.
                stopSelf()
                return START_NOT_STICKY
            }
            action == ServiceIntents.ACTION_START -> this.flags.userTurnedOff = false
        }
        controller.onServiceStarted()
        observeState()
        if (action == ServiceIntents.ACTION_TOGGLE_PERSONALIZED) {
            lifecycleScope.launch { controller.togglePersonalized() }
        }
        return START_STICKY
    }

    private fun enterForeground(): Boolean = try {
        ServiceCompat.startForeground(
            this,
            NotificationPoster.ID_ENGINE,
            currentNotification(),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        true
    } catch (_: IllegalStateException) { // ForegroundServiceStartNotAllowedException is one
        false
    } catch (_: SecurityException) {
        false
    }

    private fun currentNotification(): Notification = notifications.engineNotification(model())

    private fun model(): NotificationModel {
        val profile = controller.activeProfile.value as? ActiveProfile.Resolved
        return NotificationModel(
            state = controller.state.value,
            noSessionHint = controller.noSessionHint.value,
            personalized = profile?.profile?.personalizationOn,
        )
    }

    private fun observeState() {
        if (observer != null) return
        observer = lifecycleScope.launch {
            combine(
                controller.state,
                controller.noSessionHint,
                controller.activeProfile.map { (it as? ActiveProfile.Resolved)?.profile?.personalizationOn }
                    .distinctUntilChanged(),
            ) { state, hint, personalized -> NotificationModel(state, hint, personalized) }
                .distinctUntilChanged()
                .collect { notifications.notifyEngine(it) }
        }
    }

    override fun onDestroy() {
        observer?.cancel()
        observer = null
        controller.onServiceStopped()
        super.onDestroy()
    }
}
