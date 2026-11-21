package my.umarh.paudio.feature.home

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import my.umarh.paudio.service.engine.EngineController

internal class EngineHomeAdapter @Inject constructor(private val c: EngineController) : HomeEngine {
    override val state get() = c.state
    override val noSessionHint get() = c.noSessionHint
    override val running: StateFlow<Boolean> get() = c.running
    override val activeProfile get() = c.activeProfile
    override val notificationsBlocked: StateFlow<Boolean> get() = c.notificationsBlocked
    override val fault: StateFlow<Boolean> get() = c.fault
    override fun start(): Boolean = c.start()
    override fun retry() = c.retry()
    override suspend fun setPersonalized(on: Boolean): Boolean = c.setPersonalized(on)
    override suspend fun togglePersonalized(): Boolean = c.togglePersonalized()
    override fun refreshNotificationPermission() = c.refreshNotificationPermission()
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class HomeModule {
    @Binds
    abstract fun engine(impl: EngineHomeAdapter): HomeEngine
}
