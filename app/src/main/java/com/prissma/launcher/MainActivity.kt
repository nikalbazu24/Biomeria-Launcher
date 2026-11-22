package com.prissma.launcher

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import com.prissma.launcher.engine.bedrock.BedrockInstallation
import com.prissma.launcher.engine.bedrock.BedrockLauncher
import com.prissma.launcher.engine.java.GameDownloads
import com.prissma.launcher.engine.rust.StarlingInstallation
import com.prissma.launcher.engine.rust.StarlingLauncher
import com.prissma.launcher.data.InstanceStorage
import com.prissma.launcher.data.GameInstance
import com.prissma.launcher.data.OPEN_UPDATE_SETTINGS
import com.prissma.launcher.data.UpdateNotifications
import com.prissma.launcher.ui.hub.HubScreen
import com.prissma.launcher.ui.splash.PrissmaSplashScreen
import com.prissma.launcher.ui.theme.PrissmaTheme

class MainActivity : ComponentActivity() {

    private val bedrockLauncher by lazy { BedrockLauncher(this) }
    private var bedrock by mutableStateOf<BedrockInstallation?>(null)
    private val starlingLauncher by lazy { StarlingLauncher(this) }
    private var starling by mutableStateOf<StarlingInstallation?>(null)
    private var starlingReport by mutableStateOf<Uri?>(null)
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
            starlingReport = savedInstanceState.getString("pendingStarlingReport")?.let(Uri::parse)
        }
        UpdateNotifications.schedule(applicationContext)
        GameDownloads(applicationContext).runtime()

        setContent {
            PrissmaTheme {
                var showSplash by rememberSaveable { mutableStateOf(externalLaunchInstanceId == null && starlingReport == null) }

                if (showSplash && externalLaunchInstanceId == null && starlingReport == null && updateSettingsRequest == 0) {
                    PrissmaSplashScreen(
                        onSplashFinished = { showSplash = false }
                    )
                } else {
                    HubScreen(
                        bedrock = bedrock,
                        bedrockLauncher = bedrockLauncher,
                        onRefreshBedrock = ::refreshBedrock,
                        onOpenBedrock = ::openBedrock,
                        starling = starling,
                        starlingLauncher = starlingLauncher,
                        starlingReport = starlingReport,
                        onOpenStarling = ::openStarling,
                        onConsumeStarlingReport = { source ->
                            showSplash = false
                            if (starlingReport == source) starlingReport = null
                        },
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
        starling = starlingLauncher.detect()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("pendingExternalLaunch", externalLaunchInstanceId)
        outState.putString("sourceFrontendPackage", sourceFrontendPackage)
        outState.putString("pendingStarlingReport", starlingReport?.toString())
    }

    private fun refreshBedrock() {
        bedrock = bedrockLauncher.detect()
    }

    private fun openBedrock(): String? = bedrockLauncher.open()?.also { refreshBedrock() }

    private fun openStarling(instance: GameInstance): String? = starlingLauncher.open(instance)?.also { starling = starlingLauncher.detect() }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "application/zip") {
            val source = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?: intent.clipData?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.uri
            if (source?.scheme == "content") {
                externalLaunchInstanceId = null
                starlingReport = source
            }
            return
        }
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
