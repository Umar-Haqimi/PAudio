package my.umarh.paudio.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import my.umarh.paudio.data.db.PAudioDatabase
import org.junit.Rule
import org.junit.Test

/**
 * Migration test infrastructure (SPEC §7.2). The Room Gradle plugin adds `data/schemas` to the androidTest assets.
 * When bumping to version 2: add `MIGRATION_1_2` to the database builder, append it to [ALL_MIGRATIONS]
 * and add a `migrate1To2` test using `helper.runMigrationsAndValidate(NAME, 2, true, MIGRATION_1_2)`.
 */
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PAudioDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun createVersion1AndValidate() {
        helper.createDatabase(NAME, 1).use { db ->
            db.execSQL("INSERT INTO listener (id, name, createdAt) VALUES (1, 'A', 0)")
        }
        helper.runMigrationsAndValidate(NAME, 1, true).close()
    }

    companion object {
        private const val NAME = "migration-test"
    }
}
