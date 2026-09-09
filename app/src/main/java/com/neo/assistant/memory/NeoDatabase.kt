package com.neo.assistant.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [MemoryEntity::class], version = 1)
abstract class NeoDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao

    companion object {
        @Volatile private var INSTANCE: NeoDatabase? = null
        fun get(context: Context): NeoDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                NeoDatabase::class.java,
                "neo.db"
            ).build().also { INSTANCE = it }
        }
    }
}
