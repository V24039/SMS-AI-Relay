package com.smsairelay.app

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Entity(tableName = "messages", indices = [Index("conversationKey")])
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationKey: String,
    val role: String,
    val content: String,
    val timestamp: Long
)

@Dao
interface ChatMessageDao {

    @Insert
    suspend fun insertAll(messages: List<ChatMessage>)

    // Newest N rows, returned oldest-first so they can be passed to the model as-is.
    @Query(
        "SELECT * FROM (SELECT * FROM messages WHERE conversationKey = :key " +
            "ORDER BY id DESC LIMIT :limit) ORDER BY id ASC"
    )
    suspend fun recent(key: String, limit: Int): List<ChatMessage>

    @Query("DELETE FROM messages WHERE conversationKey = :key")
    suspend fun deleteConversation(key: String)

    @Query(
        "DELETE FROM messages WHERE conversationKey IN (SELECT conversationKey FROM messages " +
            "GROUP BY conversationKey HAVING MAX(timestamp) < :cutoff)"
    )
    suspend fun deleteInactiveBefore(cutoff: Long)

    @Query(
        "DELETE FROM messages WHERE conversationKey = :key AND id NOT IN " +
            "(SELECT id FROM messages WHERE conversationKey = :key ORDER BY id DESC LIMIT :keep)"
    )
    suspend fun trim(key: String, keep: Int)
}

@Database(entities = [ChatMessage::class], version = 1, exportSchema = false)
abstract class ChatDatabase : RoomDatabase() {

    abstract fun messageDao(): ChatMessageDao

    companion object {
        @Volatile
        private var instance: ChatDatabase? = null

        fun get(context: Context): ChatDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, ChatDatabase::class.java, "relay.db"
                ).build().also { instance = it }
            }
    }
}
