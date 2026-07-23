package com.example.gravimusicplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.gravimusicplayer.ui.GraviMusicPlayerApp
import com.example.gravimusicplayer.ui.theme.GraviMusicPlayerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GraviMusicPlayerTheme {
                GraviMusicPlayerApp()
            }
        }
    }
}