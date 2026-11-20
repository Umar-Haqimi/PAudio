package my.umarh.paudio.audio.playback

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import my.umarh.paudio.core.model.AbPlayer
import my.umarh.paudio.core.model.AudioSource
import my.umarh.paudio.core.model.BypassController
import my.umarh.paudio.core.model.BypassReason
import my.umarh.paudio.core.model.BypassToken
import my.umarh.paudio.core.model.EffectBackend
import my.umarh.paudio.core.model.EffectHandle
import my.umarh.paudio.core.model.EqConfig
import my.umarh.paudio.core.preference.AbSide
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration

/** Listening time accumulated by [ExoAbPlayer] while audible; feed to `AbRoundGate.onPlayed(side, deltaMs / 1000.0)`. */
data class ListenTick(val side: AbSide?, val deltaMs: Long)

/** Audio-focus seam (injectable for tests). */
interface AbAudioFocus {
    /** Requests focus; [onLoss] is invoked on the main thread when it is lost. Returns true when granted. */
    fun request(onLoss: () -> Unit): Boolean
    fun abandon()
}

/** [AbAudioFocus] backed by [AudioManager]: transient focus (other media pauses), pause on any loss. */
class AndroidAbAudioFocus(context: Context) : AbAudioFocus {
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var request: android.media.AudioFocusRequest? = null

    override fun request(onLoss: () -> Unit): Boolean {
        abandon()
        val r = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
                ) {
                    onLoss()
                }
            }
            .build()
        request = r
        return am.requestAudioFocus(r) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    override fun abandon() {
        request?.let { am.abandonAudioFocusRequest(it) }
        request = null
    }
}

/**
 * In-app A/B player (SPEC §5.2, §4 flow 3/5) on Media3 ExoPlayer.
 *
 * - Owns a private audio session id (set before `prepare`) and ONE [EffectHandle] on it, enabled for the
 *   player's lifetime: A/B runs its own DynamicsProcessing on its own session, never the system engine (trap #1).
 * - While playing (from the first [play] until [release]) a [BypassToken] with [reason] keeps the
 *   system-wide engine bypassed (P0).
 * - [swapTo] switches the effect config on the same playhead through a 20 ms duck (DECISIONS §3).
 *
 * Threading: construct and call everything on the same looper (normally main), like ExoPlayer itself.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ExoAbPlayer internal constructor(
    context: Context,
    effectBackend: EffectBackend,
    private val bypass: BypassController,
    private val reason: BypassReason,
    private val focus: AbAudioFocus,
    sessionId: Int,
    playerFactory: (Context) -> ExoPlayer,
) : AbPlayer {
    constructor(
        context: Context,
        effectBackend: EffectBackend,
        bypass: BypassController,
        reason: BypassReason,
    ) : this(
        context.applicationContext, effectBackend, bypass, reason, AndroidAbAudioFocus(context.applicationContext),
        (context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager).generateAudioSessionId(),
        { ExoPlayer.Builder(it).build() },
    )

    private val appContext = context.applicationContext
    private val player: ExoPlayer = playerFactory(appContext)
    private val handler = Handler(player.applicationLooper)

    /** The private audio session this player (and its effect) lives on. */
    val audioSessionId: Int = sessionId

    private val effect: EffectHandle
    private var token: BypassToken? = null
    private var released = false

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing

    private val _error = MutableStateFlow<Throwable?>(null)
    val error: StateFlow<Throwable?> = _error

    private val _listenTicks = MutableSharedFlow<ListenTick>(extraBufferCapacity = 64)

    /** Emits a [ListenTick] every [TICK_MS] while audio is actually playing, tagged with the active side. */
    val listenTicks: SharedFlow<ListenTick> = _listenTicks

    /** The side of the most recently applied [swapTo], or null. */
    var activeSide: AbSide? = null
        private set

    private var currentCfg: EqConfig = EqConfig.flat()
    private var currentGainDb = 0f

    // Duck state machine (looper-confined).
    private enum class Phase { IDLE, DOWN, UP }

    private var phase = Phase.IDLE
    private var volume = 1f
    private var pending: Triple<EqConfig, Float, AbSide?>? = null
    private var wantPlay = false
    private var lastTickAt = 0L

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _playing.value = isPlaying
            if (isPlaying) {
                lastTickAt = SystemClock.elapsedRealtime()
                handler.removeCallbacks(listenRunnable)
                handler.postDelayed(listenRunnable, TICK_MS)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            _error.value = error
            wantPlay = false
            closeToken()
        }
    }

    init {
        // Session id first, before any prepare(): the effect attaches to this session.
        player.setAudioSessionId(sessionId)
        player.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            /* handleAudioFocus = */ false,
        )
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.addListener(listener)
        effect = try {
            effectBackend.create(sessionId, currentCfg).also { it.setEnabled(true) }
        } catch (t: Throwable) {
            player.release()
            throw t
        }
    }

    override suspend fun load(src: AudioSource, loop: ClosedRange<Duration>?) {
        check(!released) { "released" }
        val uri = when (src) {
            is AudioSource.Asset -> "asset:///" + src.path.trimStart('/')
            is AudioSource.Uri -> src.value
        }
        var source: MediaSource = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(appContext))
            .createMediaSource(MediaItem.fromUri(uri))
        if (loop != null) {
            source = ClippingMediaSource.Builder(source)
                .setStartPositionUs(loop.start.inWholeMicroseconds)
                .setEndPositionUs(loop.endInclusive.inWholeMicroseconds)
                .build()
        }
        player.setMediaSource(source)
        player.prepare()
        awaitReady()
    }

    private suspend fun awaitReady() = suspendCancellableCoroutine { cont: CancellableContinuation<Unit> ->
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) done { cont.resume(Unit) }
            }

            override fun onPlayerError(error: PlaybackException) {
                done { cont.resumeWithException(error) }
            }

            private fun done(block: () -> Unit) {
                player.removeListener(this)
                if (cont.isActive) block()
            }
        }
        player.addListener(l)
        cont.invokeOnCancellation { player.removeListener(l) }
        player.playerError?.let { l.onPlayerError(it) } ?: run {
            if (player.playbackState == Player.STATE_READY) l.onPlaybackStateChanged(Player.STATE_READY)
        }
    }

    override fun swapTo(cfg: EqConfig, inputGainDb: Float) = swapTo(cfg, inputGainDb, null)

    /** As [swapTo], also recording which A/B [side] becomes active (for [listenTicks]). */
    fun swapTo(cfg: EqConfig, inputGainDb: Float, side: AbSide?) {
        check(!released) { "released" }
        pending = Triple(cfg, inputGainDb, side)
        if (!wantPlay) {
            // Nothing audible: switch immediately, no duck needed.
            finishSwap()
            return
        }
        if (phase != Phase.DOWN) {
            phase = Phase.DOWN
            handler.removeCallbacks(rampRunnable)
            handler.postDelayed(rampRunnable, STEP_MS)
        } // else: already ducking down — the newer pending value simply replaces the older one (coalesce)
    }

    private val rampRunnable = Runnable { rampStep() }

    private fun rampStep() {
        if (released) return
        val step = STEP_MS.toFloat() / RAMP_MS
        when (phase) {
            Phase.DOWN -> {
                volume = (volume - step).coerceAtLeast(0f)
                player.volume = volume
                if (volume <= 0f) {
                    applyPending()
                    phase = Phase.UP
                }
                handler.postDelayed(rampRunnable, STEP_MS)
            }
            Phase.UP -> {
                volume = (volume + step).coerceAtMost(1f)
                player.volume = volume
                if (volume >= 1f) phase = Phase.IDLE else handler.postDelayed(rampRunnable, STEP_MS)
            }
            Phase.IDLE -> Unit
        }
    }

    private fun applyPending() {
        val p = pending ?: return
        pending = null
        currentCfg = p.first
        currentGainDb = p.second
        activeSide = p.third
        // Effect inputGain = cfg.preampDb (shared P, or P_common) + inputGainDb (loudness-match term -ΔL).
        effect.apply(p.first.copy(preampDb = p.first.preampDb + p.second))
    }

    private fun finishSwap() {
        handler.removeCallbacks(rampRunnable)
        applyPending()
        phase = Phase.IDLE
        volume = 1f
        player.volume = 1f
    }

    override fun play() {
        check(!released) { "released" }
        if (token == null) token = bypass.acquire(reason)
        if (!focus.request { handler.post { pause() } }) return
        wantPlay = true
        player.play()
    }

    override fun pause() {
        if (released) return
        wantPlay = false
        if (phase != Phase.IDLE || pending != null) finishSwap()
        player.pause()
        focus.abandon()
    }

    /** Current playhead within the loop region / file, in ms. */
    fun positionMs(): Long = if (released) 0L else player.currentPosition

    private val listenRunnable: Runnable = object : Runnable {
        override fun run() {
            if (released || !player.isPlaying) return
            val now = SystemClock.elapsedRealtime()
            _listenTicks.tryEmit(ListenTick(activeSide, now - lastTickAt))
            lastTickAt = now
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun closeToken() {
        val t = token
        token = null
        try {
            t?.close()
        } catch (_: Throwable) {
        }
    }

    /** Idempotent; closes player, effect, focus and bypass token even when earlier steps throw. */
    override fun release() {
        if (released) return
        released = true
        wantPlay = false
        handler.removeCallbacksAndMessages(null)
        runCatching { focus.abandon() }
        runCatching { player.removeListener(listener) }
        runCatching { player.release() }
        runCatching { effect.setEnabled(false) }
        runCatching { effect.release() }
        closeToken()
        _playing.value = false
    }

    companion object {
        const val RAMP_MS = 20L
        const val STEP_MS = 4L
        const val TICK_MS = 250L
    }
}
