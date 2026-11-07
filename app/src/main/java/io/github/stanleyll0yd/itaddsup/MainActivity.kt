package io.github.stanleyll0yd.itaddsup

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.github.stanleyll0yd.itaddsup.ui.TrackerApp

class MainActivity : ComponentActivity() {
    private val model: TrackerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { TrackerApp(model) }
    }
}
