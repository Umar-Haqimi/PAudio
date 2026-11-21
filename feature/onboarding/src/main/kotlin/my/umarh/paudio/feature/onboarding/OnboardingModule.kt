package my.umarh.paudio.feature.onboarding

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import my.umarh.paudio.service.engine.EngineController

internal class EngineServiceStarter @Inject constructor(private val controller: EngineController) : ServiceStarter {
    override fun start(): Boolean = controller.start()
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class OnboardingModule {
    @Binds
    abstract fun serviceStarter(impl: EngineServiceStarter): ServiceStarter
}
