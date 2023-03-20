package com.mapking.ar.core.landmark.geo

import androidx.lifecycle.ViewModel

class MainViewModel: ViewModel() {
}

data class MainUiState(
    val isSignedIn: Boolean = false,
    val isPremium: Boolean = false
)

data class NewsItemUiState(
    val title: String,
    val body: String,
    val bookmarked: Boolean = false
)