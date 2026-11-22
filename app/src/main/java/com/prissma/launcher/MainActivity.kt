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
import com.prissma.launcher.engine.java.GameDownloads
import com.prissma.launcher.engine.java.OPEN_WORKSHOP_DOWNLOADS
import com.prissma.launcher.data.InstanceStorage
import com.prissma.launcher.data.OPEN_UPDATE_SETTINGS
import com.prissma.launcher.data.UpdateNotifications
import com.prissma.launcher.ui.hub.HubScreen
import com.prissma.launcher.ui.splash.PrissmaSplashScreen
import com.prissma.launcher.ui.theme.PrissmaTheme

class MainActivity : ComponentActivity() {

    private var updateSettingsRequest by mutableStateOf(0)
    private var workshopDownloadsRequest by mutableStateOf(0)
    private var externalLaunchInstanceId by mutableStateOf<String?>(null)
    private var sourceFrontendPackage by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingIntent(intent)
        UpdateNotifications.schedule(applicationContext)
        GameDownloads(applicationContext).runtime()

        setContent {
            PrissmaTheme {
                var showSplash by remember { mutableStateOf(externalLaunchInstanceId == null) }

                if (showSplash && externalLaunchInstanceId == null && updateSettingsRequest == 0 && workshopDownloadsRequest == 0) {
                    PrissmaSplashScreen(
                        onSplashFinished = { showSplash = false }
                    )
                } else {
                    HubScreen(
                        externalLaunchInstanceId = externalLaunchInstanceId,
                        updateSettingsRequest = updateSettingsRequest,
                        workshopDownloadsRequest = workshopDownloadsRequest,
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
        if (intent?.action == OPEN_UPDATE_SETTINGS) {
            updateSettingsRequest += 1
            return
        }
        if (intent?.action == OPEN_WORKSHOP_DOWNLOADS) {
            workshopDownloadsRequest += 1
            return
        }
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
