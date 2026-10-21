package com.tulipskun.aixodia.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sessions", primaryKeys = ["id"])
data class SessionEntity(
    val id: String,
    val title: String = id,
    val model: String = "",
    val lastSnippet: String = "",
    val lastAt: Long = 0,
    val unread: Int = 0,
    // False only for a New chat that exists on the phone and has not sent its
    // first message yet. The daemon has no row for it, so sync must neither
    // prune it nor make it look like a stale server session.
    val remote: Boolean = true,
)

@Entity(tableName = "messages", primaryKeys = ["sessionId", "seq"])
data class MessageEntity(
    val sessionId: String,
    val seq: Long,
    val role: String,
    val text: String,
    val createdAt: Long,
    val pending: Boolean = false,
    val clientMsgId: String = "",
    val agent: String = "",
    val jobId: String = "",
    val stage: String = "",
    val toolName: String = "",
    val toolArgs: String = "",
    val tokensIn: Int = 0,
    val tokensOut: Int = 0,
    val cacheRead: Int = 0,
    val cacheWrite: Int = 0,
    // ReasoningTokens and inputIncludesCache came with the token footer rework:
    // a thinking count, and the flag that says whether tokensIn already contains
    // the cache part.
    val reasoningTokens: Int = 0,
    val inputIncludesCache: Boolean = false,
    val model: String = "",
    // durationMs is the whole turn from send to stored — the number shown as
    // the elapsed time. generationMs is only the time the model spent writing,
    // which is what the rate is computed from.
    val durationMs: Long = 0,
    val generationMs: Long = 0,
    // thinkingMs is measured on the phone, from send to the first streamed
    // token: the daemon reports no thinking duration of its own. Old rows keep
    // zero and show no thinking pill.
    val thinkingMs: Long = 0,
    // reasoningContent is what the model thought, stored by the daemon with
    // the turn. Old rows keep blank and their pill stays closed for good.
    val reasoningContent: String = "",
    // block is the row's display block as JSON, written by the engine. Old
    // rows keep blank and render through the role instead.
    val block: String = "",
)

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY lastAt DESC, id DESC")
    fun observe(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id LIMIT 1")
    suspend fun get(id: String): SessionEntity?

    @Query("SELECT * FROM sessions ORDER BY lastAt DESC, id DESC")
    suspend fun observeAll(): List<SessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(s: SessionEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun upsertAll(rows: List<SessionEntity>)

    @Query("UPDATE sessions SET unread = 0 WHERE id = :id")
    suspend fun clearUnread(id: String)

    @Query("UPDATE sessions SET unread = unread + 1 WHERE id = :id")
    suspend fun bumpUnread(id: String)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE sessionId = :sid ORDER BY seq ASC")
    fun observe(sid: String): Flow<List<MessageEntity>>

    @Query("SELECT COALESCE(MAX(seq), 0) FROM messages WHERE sessionId = :sid")
    suspend fun maxSeq(sid: String): Long

    @Query("SELECT COALESCE(MIN(seq), 0) FROM messages WHERE sessionId = :sid")
    suspend fun minSeq(sid: String): Long

    @Query("SELECT COUNT(*) FROM messages WHERE sessionId = :sid")
    suspend fun count(sid: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<MessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: MessageEntity)

    @Query("SELECT * FROM messages WHERE pending = 1 ORDER BY createdAt ASC")
    suspend fun allPending(): List<MessageEntity>

    // The daemon answered that it never had this session, so nothing can be
    // flushed into it: the rows stop being pending and the session becomes
    // prunable again instead of retrying forever.
    @Query("UPDATE messages SET pending = 0 WHERE sessionId = :sid")
    suspend fun clearPending(sid: String)

    @Query("UPDATE messages SET pending = 0 WHERE clientMsgId = :clientMsgId")
    suspend fun markAcked(clientMsgId: String)

    @Query("SELECT * FROM messages WHERE sessionId = :sid AND seq = :seq LIMIT 1")
    suspend fun get(sid: String, seq: Long): MessageEntity?

    @Query("SELECT * FROM messages WHERE sessionId = :sid ORDER BY seq ASC")
    suspend fun allNow(sid: String): List<MessageEntity>

    @Query("DELETE FROM messages WHERE sessionId = :sid AND seq = :seq")
    suspend fun deleteOne(sid: String, seq: Long)

    @Query("DELETE FROM messages WHERE sessionId = :sid")
    suspend fun deleteSession(sid: String)

    @Query("UPDATE messages SET thinkingMs = :ms WHERE sessionId = :sid AND seq = :seq")
    suspend fun stampThinkingAt(sid: String, seq: Long, ms: Long)
}

/**
 * The schema version, declared once so a test can check it against the
 * migrations. Room compares a hash generated from the entities against this
 * number, so adding a field to an entity without moving it is not a compile
 * error and not a warning: it throws the first time the database is opened, and
 * the app closes before anything on screen. That is what shipped as v0.1.270.
 */
internal const val DB_VERSION = 10

// The version has to move whenever an entity gains a column, and it is the one
// number nothing else forces you to touch: adding a field to MessageEntity and
// writing the migration is enough to compile, and the crash only arrives later,
// on the phone, the first time the database is opened. See DatabaseVersionTest.
@Database(entities = [SessionEntity::class, MessageEntity::class], version = DB_VERSION, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao
    abstract fun messages(): MessageDao

    companion object {
        // v2 adds agent/job/tool/token/ack columns. Existing installs upgrade
        // in place: the chat history already on the phone is kept (no reinstall).
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN clientMsgId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE messages ADD COLUMN agent TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE messages ADD COLUMN jobId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE messages ADD COLUMN stage TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE messages ADD COLUMN toolName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE messages ADD COLUMN toolArgs TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE messages ADD COLUMN tokensIn INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN tokensOut INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v3 adds the per-message footer, so a reopened chat still shows which
        // model answered and what it cost.
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN model TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE messages ADD COLUMN durationMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v4 adds cached usage to that footer. Old rows keep zeros and simply
        // show no cache line.
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN cacheRead INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN cacheWrite INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v5 adds the reasoning count and the flag that says whether the input
        // total already contains the cache part. Old rows keep zero/false and
        // are read with the old convention, which is the honest default when
        // nothing recorded which convention produced them.
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN reasoningTokens INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN inputIncludesCache INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v6 splits the footer clock in two. Old rows keep a generationMs of 0
        // and fall back to the turn duration, which is the number they already
        // stored, so no history changes its meaning.
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN generationMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v7 records how long a turn thought before its first word, measured on
        // the phone. Old rows keep zero and show no thinking pill.
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN thinkingMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v8 keeps what the model thought, as the daemon stored it. Old rows
        // keep blank; their pills show the time but never open.
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN reasoningContent TEXT NOT NULL DEFAULT ''")
            }
        }

        // v9 keeps the row's display block, as the daemon wrote it. Old rows
        // keep blank and render through the role instead.
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN block TEXT NOT NULL DEFAULT ''")
            }
        }

        // v10 marks a locally-created New chat as not yet on the daemon, so
        // session sync leaves it alone until the first message promotes it.
        // Every chat that already existed on an upgraded phone came from the
        // daemon, so the default is true.
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN remote INTEGER NOT NULL DEFAULT 1")
            }
        }

        @Volatile private var inst: AppDatabase? = null
        fun get(ctx: Context): AppDatabase = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx, AppDatabase::class.java, "aixodia.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
                .build()
                .also { inst = it }
        }
    }
}
