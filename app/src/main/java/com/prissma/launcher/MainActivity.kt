package com.prissma.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.prissma.launcher.ui.hub.HubScreen
import com.prissma.launcher.ui.theme.PrissmaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PrissmaTheme {
                HubScreen()
            }
        }
    }
}
