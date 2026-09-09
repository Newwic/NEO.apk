package com.neo.assistant.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface MemoryDao {
    @Insert suspend fun insert(memory: MemoryEntity)

    @Query("SELECT * FROM memories ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int = 100): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE category = :category ORDER BY importance DESC, updatedAt DESC LIMIT :limit")
    suspend fun byCategory(category: String, limit: Int = 80): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE source = :source ORDER BY importance DESC, updatedAt DESC LIMIT :limit")
    suspend fun bySource(source: String, limit: Int = 80): List<MemoryEntity>

    @Query("UPDATE memories SET accessCount = accessCount + 1, updatedAt = :now WHERE id IN (:ids)")
    suspend fun markAccessed(ids: List<Long>, now: Long = System.currentTimeMillis())
}
