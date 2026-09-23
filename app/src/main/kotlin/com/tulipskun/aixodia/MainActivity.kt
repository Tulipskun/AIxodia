package com.tulipskun.aixodia

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.tulipskun.aixodia.ui.chat.ChatScreen
import com.tulipskun.aixodia.ui.theme.AIxodiaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = (application as AixodiaApp).container
        setContent {
            AIxodiaTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    ChatScreen(repo = c.repo, settings = c.settings)
                }
            }
        }
    }
}
