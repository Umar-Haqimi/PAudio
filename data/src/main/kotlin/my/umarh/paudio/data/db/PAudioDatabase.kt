package my.umarh.paudio.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

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

/**
 * DB-level backstop for "one Any-headphones profile per listener" (SQLite UNIQUE ignores NULLs): a partial unique
 * index that is NOT declared in the entities (Room's schema validation only checks declared indices and ignores
 * this one; verified by a test). It is created idempotently in onCreate AND onOpen.
 *
 * FUTURE MIGRATIONS: any migration that recreates the `profile` table must recreate this index
 * (onOpen re-creates it on every open, so this is automatic as long as the callback stays registered).
 * Best effort: if legacy duplicates exist the statement fails and is skipped; the repository-level
 * transactional check still applies.
 */
object AnySlotIndexCallback : RoomDatabase.Callback() {
    const val SQL = "CREATE UNIQUE INDEX IF NOT EXISTS idx_profile_any_slot ON profile(listenerId) WHERE headsetId IS NULL"

    override fun onCreate(db: SupportSQLiteDatabase) = ensure(db)
    override fun onOpen(db: SupportSQLiteDatabase) = ensure(db)

    private fun ensure(db: SupportSQLiteDatabase) {
        try {
            db.execSQL(SQL)
        } catch (e: android.database.SQLException) {
            // keep opening; see KDoc
        }
    }
}
