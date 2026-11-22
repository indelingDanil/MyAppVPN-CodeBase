package com.myappvpn.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.myappvpn.data.model.ServerProfile

@Database(
    entities = [ServerProfile::class],
    version = 6,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao

    companion object {
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE servers ADD COLUMN validationState TEXT NOT NULL DEFAULT 'UNTESTED'")
                db.execSQL("ALTER TABLE servers ADD COLUMN lastValidatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE servers ADD COLUMN medianLatencyMs INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE servers ADD COLUMN pingCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE servers ADD COLUMN consecutiveFailures INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE servers ADD COLUMN handshakeOk INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE servers ADD COLUMN verifiedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v6: Hysteria2 support — every existing row is a VLESS server. Ids/stats/pin untouched. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE servers ADD COLUMN protocol TEXT NOT NULL DEFAULT 'vless'")
            }
        }
    }
}
