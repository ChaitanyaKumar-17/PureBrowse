package com.purebrowse.vpn.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [AutoDomain::class, UserDomain::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun domainDao(): DomainDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "purebrowse_vpn_database"
                )
                // Using fallbackToDestructiveMigration since we want to drop data if schema changes
                .fallbackToDestructiveMigration()
                // Force Write-Ahead Logging so massive blocklist updates don't lock the DNS proxy thread
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
