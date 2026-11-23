package com.biomeria.launcher

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import com.biomeria.launcher.engine.bedrock.BedrockInstallation
import com.biomeria.launcher.engine.bedrock.BedrockLauncher
import com.biomeria.launcher.engine.java.GameDownloads
import com.biomeria.launcher.engine.rust.StarlingInstallation
import com.biomeria.launcher.engine.rust.StarlingLauncher
import com.biomeria.launcher.data.InstanceStorage
import com.biomeria.launcher.data.GameInstance
import com.biomeria.launcher.data.OPEN_UPDATE_SETTINGS
import com.biomeria.launcher.data.UpdateNotifications
import com.biomeria.launcher.ui.hub.HubScreen
import com.biomeria.launcher.ui.splash.BiomeriaSplashScreen
import com.biomeria.launcher.ui.intro.IntroPreferences
import com.biomeria.launcher.ui.intro.IntroResult
import com.biomeria.launcher.ui.intro.IntroScreen
import com.biomeria.launcher.ui.theme.BiomeriaTheme

class MainActivity : ComponentActivity() {

    private val bedrockLauncher by lazy { BedrockLauncher(this) }
    private var bedrock by mutableStateOf<BedrockInstallation?>(null)
    private val starlingLauncher by lazy { StarlingLauncher(this) }
    private var starling by mutableStateOf<StarlingInstallation?>(null)
    private var starlingReport by mutableStateOf<Uri?>(null)
    private var updateSettingsRequest by mutableStateOf(0)
    private var externalLaunchInstanceId by mutableStateOf<String?>(null)
    private var externalLaunchFromWeb by mutableStateOf(false)
    private var sourceFrontendPackage by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            handleIncomingIntent(intent)
        } else {
            externalLaunchInstanceId = savedInstanceState.getString("pendingExternalLaunch")
            externalLaunchFromWeb = savedInstanceState.getBoolean("pendingExternalLaunchFromWeb")
            sourceFrontendPackage = savedInstanceState.getString("sourceFrontendPackage")
            starlingReport = savedInstanceState.getString("pendingStarlingReport")?.let(Uri::parse)
        }
        UpdateNotifications.schedule(applicationContext)
        Thread { com.biomeria.launcher.data.PlayStats.recoverOrphan(applicationContext); com.biomeria.launcher.data.PlayStats.export(applicationContext) }.start()
        GameDownloads(applicationContext).runtime()

        setContent {
            BiomeriaTheme {
                var showSplash by rememberSaveable { mutableStateOf(externalLaunchInstanceId == null && starlingReport == null) }

                var showIntro by rememberSaveable { mutableStateOf(!IntroPreferences.done(this@MainActivity)) }
                var introResult by remember { mutableStateOf<IntroResult?>(null) }
                if (showSplash && externalLaunchInstanceId == null && starlingReport == null && updateSettingsRequest == 0) {
                    BiomeriaSplashScreen(
                        onSplashFinished = { showSplash = false }
                    )
                } else if (showIntro && externalLaunchInstanceId == null && starlingReport == null && updateSettingsRequest == 0) {
                    IntroScreen { result ->
                        introResult = result
                        showIntro = false
                    }
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
                        introResult = introResult,
                        onConsumeIntro = { introResult = null },
                        externalLaunchInstanceId = externalLaunchInstanceId,
                        externalLaunchFromWeb = externalLaunchFromWeb,
                        updateSettingsRequest = updateSettingsRequest,
                        sourceFrontendPackage = sourceFrontendPackage,
                        onConsumeExternalLaunch = {
                            showSplash = false
                            externalLaunchInstanceId = null
                            externalLaunchFromWeb = false
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
        outState.putBoolean("pendingExternalLaunchFromWeb", externalLaunchFromWeb)
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
        if (dataUri.isHierarchical && dataUri.host == "launch" && dataUri.scheme in listOf("biomeria", "biomeria", "mchub")) {
            sourceFrontendPackage = dataUri.getQueryParameter("source_frontend")
                ?: intent.getStringExtra("source_frontend")
            val instanceId = dataUri.getQueryParameter("instance_id")
            if (!instanceId.isNullOrBlank()) {
                // Browsers add CATEGORY_BROWSABLE; frontends such as Díptico or Cocoon launch directly.
                externalLaunchFromWeb = intent.hasCategory(Intent.CATEGORY_BROWSABLE)
                externalLaunchInstanceId = instanceId
            }
            return
        }

        if (dataUri.scheme !in listOf("content", "file")) return
        sourceFrontendPackage = intent.getStringExtra("source_frontend")
        val storage = InstanceStorage(applicationContext)
        val fromFile = storage.extractInstanceIdFromUri(dataUri)
        if (!fromFile.isNullOrBlank()) {
            externalLaunchFromWeb = intent.hasCategory(Intent.CATEGORY_BROWSABLE)
            externalLaunchInstanceId = fromFile
        }
    }
}
