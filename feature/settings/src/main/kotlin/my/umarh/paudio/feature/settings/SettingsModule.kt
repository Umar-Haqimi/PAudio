package my.umarh.paudio.feature.settings

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import my.umarh.paudio.data.settings.EngineMode as StoredMode
import my.umarh.paudio.audio.effect.EngineMode as EffectMode
import my.umarh.paudio.service.engine.EngineController

internal class EngineSettingsAdapter @Inject constructor(private val c: EngineController) : SettingsEngine {
    override val running: StateFlow<Boolean> get() = c.running

    override suspend fun setMode(mode: StoredMode) = c.setMode(
        when (mode) {
            StoredMode.SESSION -> EffectMode.SESSION
            StoredMode.LEGACY -> EffectMode.LEGACY
        },
    )

    override fun start(): Boolean = c.start()
    override fun stop() = c.stop()
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class SettingsModule {
    @Binds
    abstract fun engine(impl: EngineSettingsAdapter): SettingsEngine
}
