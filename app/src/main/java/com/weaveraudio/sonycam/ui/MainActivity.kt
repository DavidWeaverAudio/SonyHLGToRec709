package com.weaveraudio.sonycam.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                MainScreen(viewModel())
            }
        }
    }
}

/** Always dark: judging a grade against a bright UI chrome skews perception of exposure. */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFF2A33A),
            onPrimary = Color(0xFF241600),
            primaryContainer = Color(0xFF4A3410),
            onPrimaryContainer = Color(0xFFFFDDB0),
            secondary = Color(0xFFAEB4C2),
            onSecondary = Color(0xFF1A1D24),
            secondaryContainer = Color(0xFF2C3039),
            onSecondaryContainer = Color(0xFFDDE1EA),
            background = Color(0xFF101114),
            onBackground = Color(0xFFE6E7EA),
            surface = Color(0xFF101114),
            onSurface = Color(0xFFE6E7EA),
            surfaceVariant = Color(0xFF1B1D22),
            onSurfaceVariant = Color(0xFFA6AAB4),
            surfaceContainer = Color(0xFF17191D),
            surfaceContainerHigh = Color(0xFF1E2026),
            outline = Color(0xFF3B3F48),
            outlineVariant = Color(0xFF2A2D34),
            error = Color(0xFFFF8A80),
        ),
        content = content,
    )
}
