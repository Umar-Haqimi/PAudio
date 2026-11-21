package my.umarh.paudio.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import my.umarh.paudio.audio.effect.EngineError
import my.umarh.paudio.audio.effect.EngineState
import my.umarh.paudio.data.db.ProfileEntity
import my.umarh.paudio.service.engine.ActiveProfileResolver
import javax.inject.Inject
import javax.inject.Singleton

/** A string resource plus format arguments; resolve with [format]. */
data class StatusText(@StringRes val res: Int, val args: List<Any> = emptyList()) {
    fun format(context: Context): String = context.getString(res, *args.toTypedArray())
}

/** EngineState -> user-visible copy (F21). Pure; also used by the Home status chip. */
object EngineStatus {
    const val MAX_LISTED_APPS = 3

    fun textOf(state: EngineState, labelOf: (String) -> String): StatusText = when (state) {
        EngineState.Off -> StatusText(R.string.service_status_off)
        EngineState.Starting -> StatusText(R.string.service_status_starting)
        EngineState.Waiting -> StatusText(R.string.service_status_waiting)
        is EngineState.Active -> {
            val labels = state.apps.map(labelOf).distinct()
            if (labels.size <= MAX_LISTED_APPS) {
                StatusText(R.string.service_status_active, listOf(labels.joinToString(", ")))
            } else {
                StatusText(
                    R.string.service_status_active_more,
                    listOf(labels.take(MAX_LISTED_APPS).joinToString(", "), labels.size - MAX_LISTED_APPS),
                )
            }
        }
        is EngineState.Legacy ->
            StatusText(if (state.active) R.string.service_status_legacy_active else R.string.service_status_legacy_original)
        EngineState.Bypassed -> StatusText(R.string.service_status_bypassed)
        EngineState.Conflict -> StatusText(R.string.service_status_conflict)
        is EngineState.NoProfile -> StatusText(R.string.service_status_no_profile)
        EngineState.SpeakerOutput -> StatusText(R.string.service_status_speaker)
        is EngineState.Error -> StatusText(
            when (state.cause) {
                EngineError.CREATION_FAILED -> R.string.service_status_error_creation
                EngineError.SOURCE_FAILED -> R.string.service_status_error_source
            },
        )
    }

    /** The "your music app isn't announcing itself" hint only makes sense while waiting for a session. */
    fun hintOf(state: EngineState, noSessionHint: Boolean): StatusText? =
        if (noSessionHint && state == EngineState.Waiting) StatusText(R.string.service_hint_no_session) else null
}

/**
 * Everything the persistent notification shows. [personalized] is null when no profile applies
 * (then there is no Personalized/Original action).
 */
data class NotificationModel(
    val state: EngineState,
    val noSessionHint: Boolean,
    val personalized: Boolean?,
    /** An unexpected failure is being retried: shown instead of the (possibly stale) engine state. */
    val fault: Boolean = false,
)

/** Posts the unknown-headset prompt (F17). */
interface HeadsetPromptSink {
    fun showUnknown(headsetKey: String, anyHeadphones: ProfileEntity?)
    fun clear()
}

/** Posts the "Tap to start PAudio" boot fallback (DECISIONS §8). */
interface BootFallback {
    fun postTapToStart()
}

@Singleton
class NotificationPoster @Inject constructor(
    @ApplicationContext private val context: Context,
    private val labels: AppLabels,
) : HeadsetPromptSink, BootFallback {

    private val nm: NotificationManager get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private var channelsCreated = false

    fun ensureChannels() {
        if (channelsCreated) return
        channelsCreated = true
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ENGINE, context.getString(R.string.service_channel_engine_name), NotificationManager.IMPORTANCE_LOW)
                .apply {
                    description = context.getString(R.string.service_channel_engine_description)
                    setShowBadge(false)
                },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_BOOT, context.getString(R.string.service_channel_boot_name), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = context.getString(R.string.service_channel_boot_description) },
        )
        // LOW on purpose: a prompt on every headphone connect must not make sound.
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_HEADSET, context.getString(R.string.service_channel_headset_name), NotificationManager.IMPORTANCE_LOW)
                .apply { description = context.getString(R.string.service_channel_headset_description) },
        )
    }

    // ------------------------------------------------------------ persistent engine notification

    fun engineNotification(model: NotificationModel): Notification {
        ensureChannels()
        val status = if (model.fault) {
            context.getString(R.string.service_status_fault)
        } else {
            EngineStatus.textOf(model.state, labels::labelOf).format(context)
        }
        val hint = if (model.fault) null else EngineStatus.hintOf(model.state, model.noSessionHint)?.format(context)
        val title = context.getString(
            when (model.personalized) {
                true -> R.string.service_notification_title_personalized
                false -> R.string.service_notification_title_original
                null -> R.string.service_notification_title
            },
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ENGINE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(status)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (hint == null) status else "$status\n$hint"))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        launchIntent(null)?.let { builder.setContentIntent(activity(REQ_OPEN, it)) }
        if (model.personalized != null) {
            val label = if (model.personalized) R.string.service_action_to_original else R.string.service_action_to_personalized
            builder.addAction(0, context.getString(label), servicePending(REQ_TOGGLE, ServiceIntents.ACTION_TOGGLE_PERSONALIZED))
        }
        launchIntent(null)?.let {
            builder.addAction(0, context.getString(R.string.service_action_open_app), activity(REQ_OPEN_ACTION, it))
        }
        builder.addAction(0, context.getString(R.string.service_action_turn_off), servicePending(REQ_STOP, ServiceIntents.ACTION_STOP))
        return builder.build()
    }

    fun notifyEngine(model: NotificationModel) {
        nm.notify(ID_ENGINE, engineNotification(model))
    }

    // ------------------------------------------------------------ boot fallback

    override fun postTapToStart() {
        ensureChannels()
        val open = launchIntent(ServiceIntents.ROUTE_START_SERVICE) ?: return
        val n = NotificationCompat.Builder(context, CHANNEL_BOOT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.service_boot_title))
            .setContentText(context.getString(R.string.service_boot_text))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(activity(REQ_BOOT, open))
            .build()
        nm.notify(ID_BOOT, n)
    }

    // ------------------------------------------------------------ unknown headset (F17)

    override fun showUnknown(headsetKey: String, anyHeadphones: ProfileEntity?) {
        ensureChannels()
        val create = launchIntent(ServiceIntents.ROUTE_CREATE_PROFILE) ?: return
        val builder = NotificationCompat.Builder(context, CHANNEL_HEADSET)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.service_headset_title))
            .setContentText(context.getString(R.string.service_headset_text))
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(activity(REQ_CREATE, create))
            .addAction(0, context.getString(R.string.service_headset_action_create), activity(REQ_CREATE_ACTION, create))
        val ignore = Intent(context, NotificationActionReceiver::class.java)
            .setAction(ServiceIntents.ACTION_IGNORE_HEADSET)
            .putExtra(ServiceIntents.EXTRA_HEADSET_KEY, headsetKey)
        builder.addAction(
            0,
            context.getString(R.string.service_headset_action_ignore),
            PendingIntent.getBroadcast(context, REQ_IGNORE, ignore, IMMUTABLE_UPDATE),
        )
        if (anyHeadphones != null) {
            val use = Intent(context, NotificationActionReceiver::class.java)
                .setAction(ServiceIntents.ACTION_USE_PROFILE)
                .putExtra(ServiceIntents.EXTRA_HEADSET_KEY, headsetKey)
                .putExtra(ServiceIntents.EXTRA_PROFILE_ID, anyHeadphones.id)
            builder.addAction(
                0,
                context.getString(R.string.service_headset_action_use, anyHeadphones.name),
                PendingIntent.getBroadcast(context, REQ_USE, use, IMMUTABLE_UPDATE),
            )
        }
        nm.notify(ID_HEADSET, builder.build())
    }

    override fun clear() {
        nm.cancel(ID_HEADSET)
    }

    // ------------------------------------------------------------ helpers

    private fun launchIntent(route: String?): Intent? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.also {
            // Deliver to the running activity (onNewIntent) instead of dropping the tap.
            it.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (route != null) it.putExtra(ServiceIntents.EXTRA_ROUTE, route)
        }

    private fun activity(request: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(context, request, intent, IMMUTABLE_UPDATE)

    private fun servicePending(request: Int, action: String): PendingIntent =
        PendingIntent.getService(
            context,
            request,
            Intent(context, PAudioService::class.java).setAction(action),
            IMMUTABLE_UPDATE,
        )

    companion object {
        const val CHANNEL_ENGINE = "paudio_engine"
        const val CHANNEL_BOOT = "paudio_boot"
        const val CHANNEL_HEADSET = "paudio_headset"
        const val ID_ENGINE = 1
        const val ID_BOOT = 2
        const val ID_HEADSET = 3

        private const val REQ_OPEN = 10
        private const val REQ_OPEN_ACTION = 11
        private const val REQ_TOGGLE = 12
        private const val REQ_STOP = 13
        private const val REQ_BOOT = 14
        private const val REQ_CREATE = 15
        private const val REQ_CREATE_ACTION = 16
        private const val REQ_USE = 17
        private const val REQ_IGNORE = 18
        private const val IMMUTABLE_UPDATE = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    }
}

/** Notification actions handled without a UI ("Use <profile>"). */
@Singleton
class NotificationActionHandler @Inject constructor(
    private val resolver: ActiveProfileResolver,
    private val prompts: HeadsetPromptSink,
    private val flags: ServiceFlags,
) {
    suspend fun handle(intent: Intent) {
        if (intent.action == ServiceIntents.ACTION_IGNORE_HEADSET) {
            val key = intent.getStringExtra(ServiceIntents.EXTRA_HEADSET_KEY) ?: return
            flags.ignoredHeadsetKeys = flags.ignoredHeadsetKeys + key
            prompts.clear()
            return
        }
        if (intent.action != ServiceIntents.ACTION_USE_PROFILE) return
        val key = intent.getStringExtra(ServiceIntents.EXTRA_HEADSET_KEY) ?: return
        val id = intent.getLongExtra(ServiceIntents.EXTRA_PROFILE_ID, -1L).takeIf { it >= 0 } ?: return
        resolver.useProfileFor(key, id)
        prompts.clear()
    }
}
