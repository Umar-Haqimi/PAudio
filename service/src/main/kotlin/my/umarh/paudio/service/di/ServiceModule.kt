package my.umarh.paudio.service.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import my.umarh.paudio.service.AndroidAppLabels
import my.umarh.paudio.service.AndroidMixerSampleRate
import my.umarh.paudio.service.AndroidNotificationPermission
import my.umarh.paudio.service.AndroidServiceLauncher
import my.umarh.paudio.service.AppLabels
import my.umarh.paudio.service.BootFallback
import my.umarh.paudio.service.BootLogic
import my.umarh.paudio.service.HeadsetPromptSink
import my.umarh.paudio.service.MixerSampleRate
import my.umarh.paudio.service.NotificationActionHandler
import my.umarh.paudio.service.NotificationPermission
import my.umarh.paudio.service.NotificationPoster
import my.umarh.paudio.service.ServiceFlags
import my.umarh.paudio.service.ServiceLauncher
import my.umarh.paudio.service.SharedPrefsServiceFlags
import my.umarh.paudio.service.engine.EngineController
import javax.inject.Qualifier
import javax.inject.Singleton

/** Long-lived scope of the use-case layer (resolver, controller). Not tied to the service instance. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ServiceScope

@Module
@InstallIn(SingletonComponent::class)
abstract class ServiceBindsModule {
    @Binds abstract fun flags(impl: SharedPrefsServiceFlags): ServiceFlags

    @Binds abstract fun launcher(impl: AndroidServiceLauncher): ServiceLauncher

    @Binds abstract fun labels(impl: AndroidAppLabels): AppLabels

    @Binds abstract fun mixerRate(impl: AndroidMixerSampleRate): MixerSampleRate

    @Binds abstract fun notificationPermission(impl: AndroidNotificationPermission): NotificationPermission

    @Binds abstract fun promptSink(impl: NotificationPoster): HeadsetPromptSink

    @Binds abstract fun bootFallback(impl: NotificationPoster): BootFallback
}

@Module
@InstallIn(SingletonComponent::class)
object ServiceProvidesModule {
    @Provides
    @Singleton
    @ServiceScope
    fun serviceScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

/** Access for the manifest receivers (they are not Hilt entry points themselves). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ServiceEntryPoint {
    fun bootLogic(): BootLogic

    fun actionHandler(): NotificationActionHandler

    fun controller(): EngineController
}
