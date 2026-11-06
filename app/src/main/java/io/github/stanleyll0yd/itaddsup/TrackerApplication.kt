package io.github.stanleyll0yd.itaddsup

import android.app.Application
import androidx.room.Room
import io.github.stanleyll0yd.itaddsup.data.*

class TrackerApplication : Application() {
    val database by lazy {
        Room.databaseBuilder(this, TrackerDatabase::class.java, "tracker.db").build()
    }
    val repository by lazy { TrackerRepository(database) }
    val preferences by lazy { PreferencesRepository(this) }
}
