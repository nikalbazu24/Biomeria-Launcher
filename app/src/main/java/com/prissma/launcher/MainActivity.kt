package com.prissma.launcher

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.prissma.launcher.data.InstanceStorage
import com.prissma.launcher.ui.hub.HubScreen
import com.prissma.launcher.ui.theme.PrissmaTheme

class MainActivity : ComponentActivity() {

    private var externalLaunchInstanceId by mutableStateOf<String?>(null)
    private var sourceFrontendPackage by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncomingIntent(intent)

        setContent {
            PrissmaTheme {
                HubScreen(
                    externalLaunchInstanceId = externalLaunchInstanceId,
                    sourceFrontendPackage = sourceFrontendPackage,
                    onConsumeExternalLaunch = { externalLaunchInstanceId = null }
                )
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
        sourceFrontendPackage = dataUri.getQueryParameter("source_frontend")
            ?: intent.getStringExtra("source_frontend")

        val fromQuery = dataUri.getQueryParameter("instance_id")
        if (!fromQuery.isNullOrBlank()) {
            externalLaunchInstanceId = fromQuery
            return
        }

        // Si el frontend abrió un archivo .mcinst
        val storage = InstanceStorage(applicationContext)
        val fromFile = storage.extractInstanceIdFromUri(dataUri)
        if (!fromFile.isNullOrBlank()) {
            externalLaunchInstanceId = fromFile
        }
    }
}
