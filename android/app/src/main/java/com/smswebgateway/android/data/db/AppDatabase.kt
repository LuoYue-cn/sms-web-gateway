package com.smswebgateway.android.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [OutboxMessage::class, SendTaskEntity::class, RootAuditEntity::class, AppLogEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun outbox(): OutboxDao
    abstract fun tasks(): SendTaskDao
    abstract fun rootAudit(): RootAuditDao
    abstract fun logs(): AppLogDao

    companion object {
        fun create(ctx: Context): AppDatabase =
            Room.databaseBuilder(ctx, AppDatabase::class.java, "smsgate.db")
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
