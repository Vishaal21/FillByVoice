package com.vishal.fillbyvoice

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.vishal.fillbyvoice.ui.FillByVoiceApp
import com.vishal.fillbyvoice.ui.theme.FillByVoiceTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FillByVoiceTheme {
                FillByVoiceApp()
            }
        }
    }
}
