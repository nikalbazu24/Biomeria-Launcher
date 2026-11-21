package com.prissma.launcher.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.prissma.launcher.MainActivity
import com.prissma.launcher.R
import java.util.concurrent.TimeUnit

internal const val OPEN_UPDATE_SETTINGS = "com.prissma.launcher.OPEN_UPDATE_SETTINGS"

internal object UpdateNotifications {
    const val CHANNEL = "launcher_updates"

    fun enabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        return NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun schedule(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val manager = WorkManager.getInstance(context)
        manager.enqueueUniquePeriodicWork(
            "launcher-updates", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS).setConstraints(constraints)
                .setInitialDelay(6, TimeUnit.HOURS).build()
        )
        manager.enqueueUniqueWork(
            "launcher-update-now", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<UpdateWorker>().setConstraints(constraints).build()
        )
    }
}

class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        if (!UpdateNotifications.enabled(context)) return Result.success()
        val release = AppUpdater(context).checkForUpdate().getOrElse {
            return if (runAttemptCount < 2) Result.retry() else Result.failure()
        } ?: return Result.success()

        val prefs = context.getSharedPreferences("prissma_updates", Context.MODE_PRIVATE)
        if (prefs.getString("notified_release", null) == release.versionTag) return Result.success()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(UpdateNotifications.CHANNEL, "Actualizaciones", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val openSettings = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).setAction(OPEN_UPDATE_SETTINGS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, UpdateNotifications.CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Actualización de PRISSMA")
            .setContentText(release.versionTag + " disponible")
            .setContentIntent(openSettings)
            .setAutoCancel(true)
            .build()
        manager.notify(201, notification)
        prefs.edit().putString("notified_release", release.versionTag).apply()
        return Result.success()
    }
}
