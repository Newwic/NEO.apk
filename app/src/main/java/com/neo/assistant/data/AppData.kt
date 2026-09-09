package com.neo.assistant.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "chat_history")
data class ChatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,
    val text: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "knowledge")
data class KnowledgeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val content: String,
    val source: String = "local",
    val sourceUri: String = "",
    val keywords: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface AppDataDao {
    @Insert suspend fun insertChat(item: ChatEntity)

    @Insert suspend fun insertKnowledge(item: KnowledgeEntity)

    @Query("SELECT * FROM chat_history ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recentChats(limit: Int = 100): List<ChatEntity>

    @Query("SELECT * FROM knowledge ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun knowledge(limit: Int = 300): List<KnowledgeEntity>

    @Query("SELECT COUNT(*) FROM knowledge")
    suspend fun knowledgeCount(): Int
}
