package my.umarh.paudio.audio.tone

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import my.umarh.paudio.core.model.InterruptibleToneOutput
import my.umarh.paudio.core.model.ToneOutput
import my.umarh.paudio.core.model.VolumeController

@Module
@InstallIn(SingletonComponent::class)
abstract class ToneModule {
    @Binds abstract fun toneOutput(impl: AudioTrackToneOutput): ToneOutput

    @Binds abstract fun interruptibleToneOutput(impl: AudioTrackToneOutput): InterruptibleToneOutput

    @Binds abstract fun volumeController(impl: AndroidVolumeController): VolumeController
}
