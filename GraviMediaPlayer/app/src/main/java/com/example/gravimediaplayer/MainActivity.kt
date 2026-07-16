package com.example.gravimediaplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.gravimediaplayer.ui.GraviMediaPlayerApp
import com.example.gravimediaplayer.ui.theme.GraviMediaPlayerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GraviMediaPlayerTheme {
                GraviMediaPlayerApp()
            }
        }
    }
}