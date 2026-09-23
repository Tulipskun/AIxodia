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
)

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY lastAt DESC")
    fun observe(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id LIMIT 1")
    suspend fun get(id: String): SessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(s: SessionEntity)

    @Query("UPDATE sessions SET unread = 0 WHERE id = :id")
    suspend fun clearUnread(id: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE sessionId = :sid ORDER BY seq ASC")
    fun observe(sid: String): Flow<List<MessageEntity>>

    @Query("SELECT COALESCE(MAX(seq), 0) FROM messages WHERE sessionId = :sid")
    suspend fun maxSeq(sid: String): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<MessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: MessageEntity)

    @Query("SELECT * FROM messages WHERE sessionId = :sid AND pending = 1 ORDER BY seq ASC")
    suspend fun pending(sid: String): List<MessageEntity>

    @Query("DELETE FROM messages WHERE sessionId = :sid AND seq = :seq")
    suspend fun delete(sid: String, seq: Long)
}

@Database(entities = [SessionEntity::class, MessageEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao
    abstract fun messages(): MessageDao

    companion object {
        @Volatile private var inst: AppDatabase? = null
        fun get(ctx: Context): AppDatabase = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx, AppDatabase::class.java, "aixodia.db").build().also { inst = it }
        }
    }
}
