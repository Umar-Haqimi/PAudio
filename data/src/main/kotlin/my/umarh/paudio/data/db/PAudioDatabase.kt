package my.umarh.paudio.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ListenerEntity::class, HeadsetEntity::class, ProfileEntity::class, HearingTestEntity::class,
        ThresholdEntity::class, TrialEntity::class, PreferenceTestEntity::class,
    ],
    version = PAudioDatabase.VERSION,
    exportSchema = true,
)
abstract class PAudioDatabase : RoomDatabase() {
    abstract fun listenerDao(): ListenerDao
    abstract fun headsetDao(): HeadsetDao
    abstract fun profileDao(): ProfileDao
    abstract fun hearingTestDao(): HearingTestDao
    abstract fun preferenceTestDao(): PreferenceTestDao

    companion object {
        const val VERSION = 1
        const val NAME = "paudio.db"
    }
}
