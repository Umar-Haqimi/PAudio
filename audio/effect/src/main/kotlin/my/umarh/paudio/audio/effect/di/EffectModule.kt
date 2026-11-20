package my.umarh.paudio.audio.effect.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import my.umarh.paudio.audio.effect.AudioManagerMusicActivity
import my.umarh.paudio.audio.effect.BroadcastSessionSource
import my.umarh.paudio.audio.effect.DynamicsProcessingBackend
import my.umarh.paudio.audio.effect.EffectEngine
import my.umarh.paudio.audio.effect.EngineMode
import my.umarh.paudio.audio.effect.LegacySessionSource
import my.umarh.paudio.audio.effect.MusicActivity
import my.umarh.paudio.audio.effect.SessionSourceFactory
import my.umarh.paudio.core.model.BypassController
import my.umarh.paudio.core.model.EffectBackend
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object EffectModule {
    @Provides
    @Singleton
    fun effectBackend(impl: DynamicsProcessingBackend): EffectBackend = impl

    @Provides
    @Singleton
    fun sessionSourceFactory(@ApplicationContext context: Context): SessionSourceFactory =
        SessionSourceFactory { mode ->
            when (mode) {
                EngineMode.SESSION -> BroadcastSessionSource(context)
                EngineMode.LEGACY -> LegacySessionSource()
            }
        }

    @Provides
    @Singleton
    fun musicActivity(@ApplicationContext context: Context): MusicActivity = AudioManagerMusicActivity(context)

    @Provides
    @Singleton
    fun effectEngine(
        backend: EffectBackend,
        sources: SessionSourceFactory,
        music: MusicActivity,
    ): EffectEngine = EffectEngine(
        backend = backend,
        sourceFactory = sources,
        musicActivity = music,
        // Engine logic is lock-guarded; one background thread is plenty and keeps binder calls off main.
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    /** The same singleton engine is the P0 [BypassController]. */
    @Provides
    @Singleton
    fun bypassController(engine: EffectEngine): BypassController = engine
}
