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
    val model: String = "",
    val durationMs: Long = 0,
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

    @Query("UPDATE messages SET pending = 0 WHERE clientMsgId = :clientMsgId")
    suspend fun markAcked(clientMsgId: String)

    @Query("DELETE FROM messages WHERE sessionId = :sid")
    suspend fun deleteSession(sid: String)
}

@Database(entities = [SessionEntity::class, MessageEntity::class], version = 4, exportSchema = false)
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
        // model answered and what it cost (AX-095).
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN model TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE messages ADD COLUMN durationMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v4 adds cached usage to that footer. Old rows keep zeros and simply
        // show no cache line (AX-099).
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN cacheRead INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN cacheWrite INTEGER NOT NULL DEFAULT 0")
            }
        }

        @Volatile private var inst: AppDatabase? = null
        fun get(ctx: Context): AppDatabase = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx, AppDatabase::class.java, "aixodia.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
                .also { inst = it }
        }
    }
}
