package com.neo.assistant.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [MemoryEntity::class], version = 2)
abstract class NeoDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao

    companion object {
        @Volatile private var INSTANCE: NeoDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memories ADD COLUMN category TEXT NOT NULL DEFAULT 'general'")
                db.execSQL("ALTER TABLE memories ADD COLUMN source TEXT NOT NULL DEFAULT 'user'")
                db.execSQL("ALTER TABLE memories ADD COLUMN destination TEXT NOT NULL DEFAULT 'brain'")
                db.execSQL("ALTER TABLE memories ADD COLUMN keywords TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE memories ADD COLUMN importance INTEGER NOT NULL DEFAULT 50")
                db.execSQL("ALTER TABLE memories ADD COLUMN accessCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE memories ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE memories SET updatedAt = createdAt WHERE updatedAt = 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memories_category ON memories(category)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memories_source ON memories(source)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memories_updatedAt ON memories(updatedAt)")
            }
        }

        fun get(context: Context): NeoDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                NeoDatabase::class.java,
                "neo.db"
            ).addMigrations(MIGRATION_1_2)
                .build()
                .also { INSTANCE = it }
        }
    }
}
