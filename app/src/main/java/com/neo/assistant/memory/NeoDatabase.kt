package com.neo.assistant.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.neo.assistant.data.AppDataDao
import com.neo.assistant.data.ChatEntity
import com.neo.assistant.data.KnowledgeEntity

@Database(
    entities = [MemoryEntity::class, ChatEntity::class, KnowledgeEntity::class],
    version = 3
)
abstract class NeoDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao
    abstract fun appDataDao(): AppDataDao

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

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS chat_history (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "role TEXT NOT NULL, text TEXT NOT NULL, createdAt INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_history_createdAt ON chat_history(createdAt)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS knowledge (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "title TEXT NOT NULL, content TEXT NOT NULL, source TEXT NOT NULL, " +
                        "sourceUri TEXT NOT NULL, keywords TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_updatedAt ON knowledge(updatedAt)")
            }
        }

        fun get(context: Context): NeoDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                NeoDatabase::class.java,
                "neo.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
                .also { INSTANCE = it }
        }
    }
}
