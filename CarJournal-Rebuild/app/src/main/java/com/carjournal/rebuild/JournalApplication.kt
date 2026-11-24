package com.carjournal.rebuild

import android.app.Application
import androidx.room.Room
import com.carjournal.rebuild.data.JournalDatabase
import com.carjournal.rebuild.data.JournalRepository
import com.carjournal.rebuild.data.MIGRATION_1_2
import com.carjournal.rebuild.data.MIGRATION_2_3

class JournalApplication : Application() {
    val database by lazy {
        Room.databaseBuilder(this, JournalDatabase::class.java, "car_journal_rebuild.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
    }
    val repository by lazy { JournalRepository(this, database) }
}
