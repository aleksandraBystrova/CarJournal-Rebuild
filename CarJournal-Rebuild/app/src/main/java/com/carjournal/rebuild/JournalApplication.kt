package com.carjournal.rebuild

import android.app.Application
import androidx.room.Room
import com.carjournal.rebuild.data.JournalDatabase
import com.carjournal.rebuild.data.JournalRepository

class JournalApplication : Application() {
    val database by lazy {
        Room.databaseBuilder(this, JournalDatabase::class.java, "car_journal_rebuild.db")
            .build()
    }
    val repository by lazy { JournalRepository(this, database) }
}
