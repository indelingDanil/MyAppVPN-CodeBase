package com.myappvpn.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.myappvpn.data.model.ServerProfile

@Database(
    entities = [ServerProfile::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
}
