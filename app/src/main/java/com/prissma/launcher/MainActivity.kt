package com.prissma.launcher

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.prissma.launcher.engine.bedrock.BedrockInstallation
import com.prissma.launcher.engine.bedrock.BedrockLauncher
import com.prissma.launcher.engine.java.GameDownloads
import com.prissma.launcher.data.InstanceStorage
import com.prissma.launcher.data.OPEN_UPDATE_SETTINGS
import com.prissma.launcher.data.UpdateNotifications
import com.prissma.launcher.ui.hub.HubScreen
import com.prissma.launcher.ui.splash.PrissmaSplashScreen
import com.prissma.launcher.ui.theme.PrissmaTheme

class MainActivity : ComponentActivity() {

    private val bedrockLauncher by lazy { BedrockLauncher(this) }
    private var bedrock by mutableStateOf<BedrockInstallation?>(null)
    private var updateSettingsRequest by mutableStateOf(0)
    private var externalLaunchInstanceId by mutableStateOf<String?>(null)
    private var sourceFrontendPackage by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            handleIncomingIntent(intent)
        } else {
            externalLaunchInstanceId = savedInstanceState.getString("pendingExternalLaunch")
            sourceFrontendPackage = savedInstanceState.getString("sourceFrontendPackage")
        }
        UpdateNotifications.schedule(applicationContext)
        GameDownloads(applicationContext).runtime()

        setContent {
            PrissmaTheme {
                var showSplash by rememberSaveable { mutableStateOf(externalLaunchInstanceId == null) }

                if (showSplash && externalLaunchInstanceId == null && updateSettingsRequest == 0) {
                    PrissmaSplashScreen(
                        onSplashFinished = { showSplash = false }
                    )
                } else {
                    HubScreen(
                        bedrock = bedrock,
                        bedrockLauncher = bedrockLauncher,
                        onRefreshBedrock = ::refreshBedrock,
                        onOpenBedrock = ::openBedrock,
                        externalLaunchInstanceId = externalLaunchInstanceId,
                        updateSettingsRequest = updateSettingsRequest,
                        sourceFrontendPackage = sourceFrontendPackage,
                        onConsumeExternalLaunch = {
                            showSplash = false
                            externalLaunchInstanceId = null
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshBedrock()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("pendingExternalLaunch", externalLaunchInstanceId)
        outState.putString("sourceFrontendPackage", sourceFrontendPackage)
    }

    private fun refreshBedrock() {
        bedrock = bedrockLauncher.detect()
    }

    private fun openBedrock(): String? = bedrockLauncher.open()?.also { refreshBedrock() }

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
