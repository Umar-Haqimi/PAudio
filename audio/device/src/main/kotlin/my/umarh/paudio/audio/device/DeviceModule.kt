package my.umarh.paudio.audio.device

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import my.umarh.paudio.core.model.EnvironmentChecks
import my.umarh.paudio.core.model.NoiseMeter
import my.umarh.paudio.core.model.OutputDevices

@Module
@InstallIn(SingletonComponent::class)
abstract class DeviceModule {
    @Binds abstract fun outputDevices(impl: AndroidOutputDevices): OutputDevices

    @Binds abstract fun environmentChecks(impl: AndroidEnvironmentChecks): EnvironmentChecks

    @Binds abstract fun noiseMeter(impl: AndroidNoiseMeter): NoiseMeter
}
