package com.tulipskun.aixodia.ui

import androidx.compose.runtime.compositionLocalOf
import com.tulipskun.aixodia.data.model.UiSpec

/**
 * The effective server-driven UI spec, available to every composable. Defaults
 * to the built-in spec, so a component previewed or rendered before the first
 * fetch still looks right.
 */
val LocalUiSpec = compositionLocalOf { UiSpec() }
