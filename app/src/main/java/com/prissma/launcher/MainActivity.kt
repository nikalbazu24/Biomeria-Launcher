package com.prissma.launcher

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.prissma.launcher.data.InstanceStorage
import com.prissma.launcher.ui.hub.HubScreen
import com.prissma.launcher.ui.splash.PrissmaSplashScreen
import com.prissma.launcher.ui.theme.PrissmaTheme

class MainActivity : ComponentActivity() {

    private var externalLaunchInstanceId by mutableStateOf<String?>(null)
    private var sourceFrontendPackage by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingIntent(intent)

        setContent {
            PrissmaTheme {
                var showSplash by remember { mutableStateOf(externalLaunchInstanceId == null) }

                if (showSplash && externalLaunchInstanceId == null) {
                    PrissmaSplashScreen(
                        onSplashFinished = { showSplash = false }
                    )
                } else {
                    HubScreen(
                        externalLaunchInstanceId = externalLaunchInstanceId,
                        sourceFrontendPackage = sourceFrontendPackage,
                        onConsumeExternalLaunch = { externalLaunchInstanceId = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val dataUri = intent?.data ?: return
        if (dataUri.isHierarchical && dataUri.host == "launch" && dataUri.scheme in listOf("prissma", "mchub")) {
            sourceFrontendPackage = dataUri.getQueryParameter("source_frontend")
                ?: intent.getStringExtra("source_frontend")
            val instanceId = dataUri.getQueryParameter("instance_id")
            if (!instanceId.isNullOrBlank()) externalLaunchInstanceId = instanceId
            return
        }

        if (dataUri.scheme !in listOf("content", "file")) return
        sourceFrontendPackage = intent.getStringExtra("source_frontend")
        val storage = InstanceStorage(applicationContext)
        val fromFile = storage.extractInstanceIdFromUri(dataUri)
        if (!fromFile.isNullOrBlank()) {
            externalLaunchInstanceId = fromFile
        }
    }
}
