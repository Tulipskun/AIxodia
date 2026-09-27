package com.tulipskun.aixodia.screen

data class ScreenAction(
    val kind: String,
    val targetIndex: Int = -1,
    val text: String = "",
    val direction: String = "",
    val confidence: Float = 0f,
)
