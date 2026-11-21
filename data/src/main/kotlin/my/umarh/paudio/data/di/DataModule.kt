package my.umarh.paudio.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import my.umarh.paudio.data.db.PAudioDatabase
import my.umarh.paudio.data.repo.HeadsetRepository
import my.umarh.paudio.data.repo.HearingTestRepository
import my.umarh.paudio.data.repo.ListenerRepository
import my.umarh.paudio.data.repo.PreferenceTestRepository
import my.umarh.paudio.data.repo.ProfileRepository
import my.umarh.paudio.data.repo.RoomHeadsetRepository
import my.umarh.paudio.data.repo.RoomHearingTestRepository
import my.umarh.paudio.data.repo.RoomListenerRepository
import my.umarh.paudio.data.repo.RoomPreferenceTestRepository
import my.umarh.paudio.data.repo.RoomProfileRepository
import my.umarh.paudio.data.tx.DataTransactions
import my.umarh.paudio.data.tx.RoomDataTransactions
import my.umarh.paudio.data.settings.DataStoreSettingsRepository
import my.umarh.paudio.data.settings.SettingsRepository

@Module
@InstallIn(SingletonComponent::class)
object DataProvidesModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): PAudioDatabase =
        Room.databaseBuilder(context, PAudioDatabase::class.java, PAudioDatabase.NAME)
            .addCallback(my.umarh.paudio.data.db.AnySlotIndexCallback)
            .build()

    @Provides fun listenerDao(db: PAudioDatabase) = db.listenerDao()
    @Provides fun headsetDao(db: PAudioDatabase) = db.headsetDao()
    @Provides fun profileDao(db: PAudioDatabase) = db.profileDao()
    @Provides fun hearingTestDao(db: PAudioDatabase) = db.hearingTestDao()
    @Provides fun preferenceTestDao(db: PAudioDatabase) = db.preferenceTestDao()

    @Provides
    @Singleton
    fun settingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
            context.preferencesDataStoreFile("settings")
        }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DataBindsModule {
    @Binds abstract fun listeners(impl: RoomListenerRepository): ListenerRepository
    @Binds abstract fun headsets(impl: RoomHeadsetRepository): HeadsetRepository
    @Binds abstract fun profiles(impl: RoomProfileRepository): ProfileRepository
    @Binds abstract fun hearingTests(impl: RoomHearingTestRepository): HearingTestRepository
    @Binds abstract fun preferenceTests(impl: RoomPreferenceTestRepository): PreferenceTestRepository
    @Binds abstract fun transactions(impl: RoomDataTransactions): DataTransactions
    @Binds abstract fun settings(impl: DataStoreSettingsRepository): SettingsRepository
}
