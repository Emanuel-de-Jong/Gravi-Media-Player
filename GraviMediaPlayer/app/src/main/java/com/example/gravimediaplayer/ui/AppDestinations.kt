package com.example.gravimediaplayer.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

enum class AppDestinations(
    val label: String,
    val icon: ImageVector,
) {
    FOLDERS("Folders", Icons.Filled.Folder),
    GENRES("Genres", Icons.Filled.LocalOffer),
    SETTINGS("Settings", Icons.Filled.Settings),
}