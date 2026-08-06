package am.onex.stopdoom.guard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import am.onex.stopdoom.R
import am.onex.stopdoom.service.DoomAccessibilityService
import am.onex.stopdoom.ui.MainActivity
import java.util.concurrent.TimeUnit

/**
 * Notices when the protection has been switched off and says so.
 *
 * It cannot re-enable itself - only the user can grant an accessibility service -
 * so the honest thing it can do is be loud about the gap rather than pretend
 * everything is running.
 */
object ServiceWatchdog {

    const val CHANNEL_ID = "guard_warnings"
    private const val NOTIF_ID = 4201

    fun isAccessibilityEnabled(context: Context): Boolean {
        val expected = ComponentName(context, DoomAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any {
            ComponentName.unflattenFromString(it) == expected
        }
    }

    /**
     * Private DNS silently defeats the local DNS filter: queries go straight out
     * over TLS and never enter the tunnel. Worth surfacing, because otherwise
     * website blocking looks like it is working when it is not.
     */
    fun privateDnsMode(context: Context): String? =
        Settings.Global.getString(context.contentResolver, "private_dns_mode")

    fun privateDnsDefeatsFilter(context: Context): Boolean =
        when (privateDnsMode(context)?.lowercase()) {
            null, "off", "opportunistic" -> false
            else -> true
        }

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel_guard),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }

    fun warn(context: Context, title: String, body: String) {
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        manager.notify(NOTIF_ID, notification)
    }

    fun clearWarning(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
    }

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "guard-watchdog",
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}

class WatchdogWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        if (!ServiceWatchdog.isAccessibilityEnabled(context)) {
            ServiceWatchdog.warn(
                context,
                "DoomGuard is not running",
                "Its accessibility service is turned off, so nothing is being blocked. " +
                    "Tap to re-enable it.",
            )
        } else if (ServiceWatchdog.privateDnsDefeatsFilter(context)) {
            ServiceWatchdog.warn(
                context,
                "Private DNS is bypassing the filter",
                "Website blocking cannot see encrypted DNS queries. Set Private DNS to " +
                    "Off or Automatic in network settings, or rely on browser URL checking alone.",
            )
        } else {
            ServiceWatchdog.clearWarning(context)
        }
        return Result.success()
    }
}
