package my.umarh.paudio.audio.playback

import android.content.Context
import android.media.AudioManager
import android.net.Uri
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
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import my.umarh.paudio.core.model.AbPlayer
import my.umarh.paudio.core.model.AudioSource
import my.umarh.paudio.core.model.BypassController
import my.umarh.paudio.core.model.BypassReason
import my.umarh.paudio.core.model.BypassToken
import my.umarh.paudio.core.model.EffectBackend
import my.umarh.paudio.core.model.EffectHandle
import my.umarh.paudio.core.model.EqConfig
import my.umarh.paudio.core.preference.AbSide
import java.util.concurrent.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration

/**
 * Listening time accumulated by [ExoAbPlayer] while audible; feed to `AbRoundGate.onPlayed(side, deltaMs / 1000.0)`.
 * Never emitted with an unknown side.
 */
data class ListenTick(val side: AbSide, val deltaMs: Long)

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
 * - A [BypassToken] with [reason] is acquired in [load] BEFORE any media is prepared and held until [release]
 *   (also after a player error: the screen is still active), so the system-wide engine is bypassed (P0).
 * - [swapTo] switches the effect config on the same playhead through a 20 ms duck (DECISIONS §3): the 20 ms
 *   is the DOWN ramp; the up ramp is another 20 ms. The effect `apply` happens at volume 0.
 * - [swapTo] MUST be called at least once before [play] (otherwise [play] throws [IllegalStateException]):
 *   the first audio is always at the planned gain. While not playing, swaps apply immediately.
 * - Loop region: [ClippingMediaSource] + REPEAT_MODE_ONE re-seeks at the loop end; this is NOT
 *   sample-gapless (a click/gap may be audible; on the device checklist).
 * - [listenTicks]: ducked time counts as listening time; ticks are only emitted with a known side.
 *
 * Threading: construct and call everything on the player's looper (normally main). [load] switches itself
 * to that looper.
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
    private val looperDispatcher = handler.asCoroutineDispatcher("ExoAbPlayer").immediate

    /** The private audio session this player (and its effect) lives on. */
    val audioSessionId: Int = sessionId

    private val effect: EffectHandle
    private var token: BypassToken? = null
    private var released = false
    private var configured = false
    private var loadCont: CancellableContinuation<Unit>? = null

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing

    private val _error = MutableStateFlow<Throwable?>(null)

    /** Set after a player error; [play] is blocked until the screen releases and recreates the player. */
    val error: StateFlow<Throwable?> = _error

    private val _focusDenied = MutableStateFlow(false)

    /** True when the last [play] was refused by audio focus (the player is not playing). */
    val focusDenied: StateFlow<Boolean> = _focusDenied

    private val _listenTicks = MutableSharedFlow<ListenTick>(extraBufferCapacity = 64)

    /** Listening time per side, every [TICK_MS] while audible and flushed on swap/pause/release. */
    val listenTicks: SharedFlow<ListenTick> = _listenTicks

    /** The side of the most recently applied [swapTo], or null. */
    var activeSide: AbSide? = null
        private set

    // Duck state machine (looper-confined).
    private enum class Phase { IDLE, DOWN, UP }

    private var phase = Phase.IDLE
    private var volume = 1f
    private var pending: Triple<EqConfig, Float, AbSide?>? = null
    private var wantPlay = false
    private var lastTickAt = 0L

    // Test seams.
    internal var isAudible: () -> Boolean = { player.isPlaying }
    private val clockMs: () -> Long = { SystemClock.elapsedRealtime() }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _playing.value = isPlaying
        }

        override fun onPlayerError(error: PlaybackException) {
            _error.value = error
            flushTick()
            wantPlay = false
            finishSwap() // reset duck phase/volume; the token stays held until release()
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
            effectBackend.create(sessionId, EqConfig.flat()).also { it.setEnabled(true) }
        } catch (t: Throwable) {
            player.release()
            throw t
        }
    }

    internal fun buildMediaSource(src: AudioSource, loop: ClosedRange<Duration>?): MediaSource {
        val uri = when (src) {
            is AudioSource.Asset ->
                src.path.trim('/').split('/')
                    .fold(Uri.parse("asset:///").buildUpon()) { b, seg -> b.appendPath(seg) }
                    .build()
            is AudioSource.Uri -> Uri.parse(src.value)
        }
        var source: MediaSource = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(appContext))
            .createMediaSource(MediaItem.fromUri(uri))
        if (loop != null) {
            source = ClippingMediaSource.Builder(source)
                .setStartPositionUs(loop.start.inWholeMicroseconds)
                .setEndPositionUs(loop.endInclusive.inWholeMicroseconds)
                .build()
        }
        return source
    }

    override suspend fun load(src: AudioSource, loop: ClosedRange<Duration>?) = withContext(looperDispatcher) {
        check(!released) { "released" }
        // P0: bypass the system-wide engine before any media is prepared.
        if (token == null) token = bypass.acquire(reason)
        player.setMediaSource(buildMediaSource(src, loop))
        player.prepare()
        awaitReady()
    }

    private suspend fun awaitReady() = suspendCancellableCoroutine { cont: CancellableContinuation<Unit> ->
        loadCont = cont
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) done { cont.resume(Unit) }
            }

            override fun onPlayerError(error: PlaybackException) {
                done { cont.resumeWithException(error) }
            }

            private fun done(block: () -> Unit) {
                player.removeListener(this)
                loadCont = null
                if (cont.isActive) block()
            }
        }
        player.addListener(l)
        cont.invokeOnCancellation { runCatching { player.removeListener(l) } }
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
        flushTick() // attribute time so far to the OLD side
        activeSide = p.third
        configured = true
        // Effect inputGain = cfg.preampDb (shared P_common) + inputGainDb (loudness-match term -ΔL).
        effect.apply(p.first.copy(preampDb = p.first.preampDb + p.second))
    }

    private fun finishSwap() {
        handler.removeCallbacks(rampRunnable)
        applyPending()
        phase = Phase.IDLE
        volume = 1f
        player.volume = 1f
    }

    /**
     * Requires a prior [swapTo]. No-op after a player error (see [error]) or when focus is denied
     * ([focusDenied] becomes true).
     */
    override fun play() {
        check(!released) { "released" }
        check(configured) { "swapTo() must be called before play()" }
        if (_error.value != null) return
        if (token == null) token = bypass.acquire(reason)
        val granted = focus.request { handler.post { pause() } }
        _focusDenied.value = !granted
        if (!granted) return
        wantPlay = true
        lastTickAt = clockMs()
        player.play()
        handler.removeCallbacks(listenRunnable)
        handler.postDelayed(listenRunnable, TICK_MS)
    }

    override fun pause() {
        if (released) return
        flushTick()
        wantPlay = false
        if (phase != Phase.IDLE || pending != null) finishSwap()
        player.pause()
        focus.abandon()
    }

    /** Current playhead within the loop region / file, in ms. */
    fun positionMs(): Long = if (released) 0L else player.currentPosition

    private fun flushTick() {
        if (!wantPlay || !isAudible()) return
        val now = clockMs()
        val delta = now - lastTickAt
        lastTickAt = now
        val side = activeSide ?: return
        if (delta > 0) _listenTicks.tryEmit(ListenTick(side, delta))
    }

    private val listenRunnable: Runnable = object : Runnable {
        override fun run() {
            if (released || !wantPlay) return
            flushTick()
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
        runCatching { flushTick() }
        released = true
        wantPlay = false
        handler.removeCallbacksAndMessages(null)
        loadCont?.cancel(CancellationException("released"))
        loadCont = null
        runCatching { focus.abandon() }
        runCatching { player.removeListener(listener) }
        runCatching { player.release() }
        runCatching { effect.setEnabled(false) }
        runCatching { effect.release() }
        closeToken()
        _playing.value = false
    }

    companion object {
        /** Duck DOWN ramp duration (the UP ramp is another [RAMP_MS]). */
        const val RAMP_MS = 20L
        const val STEP_MS = 4L
        const val TICK_MS = 250L
    }
}
