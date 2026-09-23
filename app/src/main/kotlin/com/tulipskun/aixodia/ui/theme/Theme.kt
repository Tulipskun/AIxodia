package com.tulipskun.aixodia.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

@Composable
fun AIxodiaTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    val scheme = if (dark) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = scheme, content = content)
}
