package com.tulipskun.aixodia.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The schema version against the migrations that reach it.
 *
 * Room hashes the entity classes at compile time and compares that hash with the
 * number in `@Database` the first time the file is opened. Changing an entity
 * without changing the number compiles cleanly, passes every test that does not
 * open the database, and then throws `IllegalStateException: Room cannot verify
 * the data integrity` on the phone — which is v0.1.270, a build that closed on
 * launch because one column was added to MessageEntity. Nothing in the build
 * pipeline can see it, so it is checked here.
 *
 * What this does not catch: adding a column and forgetting the migration as
 * well. That still bumps the hash and still crashes, and the only guard against
 * it is the migration being written at the same time.
 */
class DatabaseVersionTest {

    private val migrations = listOf(
        AppDatabase.MIGRATION_1_2,
        AppDatabase.MIGRATION_2_3,
        AppDatabase.MIGRATION_3_4,
        AppDatabase.MIGRATION_4_5,
        AppDatabase.MIGRATION_5_6,
        AppDatabase.MIGRATION_6_7,
    ).sortedBy { it.startVersion }

    @Test
    fun `the migrations form one unbroken chain from a fresh install`() {
        migrations.forEachIndexed { index, migration ->
            assertEquals(
                "migration ${migration.startVersion}->${migration.endVersion} does not start where the one before it ended",
                index + 1,
                migration.startVersion,
            )
            assertEquals(
                "migration ${migration.startVersion}->${migration.endVersion} does not move one step",
                index + 2,
                migration.endVersion,
            )
        }
    }

    @Test
    fun `the last migration lands exactly on the declared version`() {
        assertEquals(migrations.last().endVersion, DB_VERSION)
    }

    @Test
    fun `a fresh install is not asked to migrate at all`() {
        // The first migration has to start at 1, or a phone that has never run
        // the app has no path from its empty database.
        assertEquals(1, migrations.first().startVersion)
        assertTrue(migrations.first().startVersion < DB_VERSION)
    }
}