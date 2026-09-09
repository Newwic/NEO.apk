package com.neo.assistant.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface MemoryDao {
    @Insert suspend fun insert(memory: MemoryEntity)
    @Query("SELECT * FROM memories ORDER BY createdAt DESC LIMIT 20")
    suspend fun recent(): List<MemoryEntity>
}
