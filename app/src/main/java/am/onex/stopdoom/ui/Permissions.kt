package am.onex.stopdoom.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.PowerManager
import android.provider.Settings
import am.onex.stopdoom.guard.AdminReceiver
import am.onex.stopdoom.guard.ServiceWatchdog

/**
 * The setup checklist.
 *
 * Every item is something the system will only grant from its own Settings UI, so
 * each one pairs a live check with the exact intent that opens the right screen.
 * Guessing at the intent is what makes these flows feel broken, so they are
 * spelled out rather than left to a generic app-details page.
 */
enum class SetupStep(
    val title: String,
    val why: String,
    val required: Boolean,
) {
    ACCESSIBILITY(
        "Accessibility service",
        "The core of the app. Without it nothing can see which screen you are on.",
        required = true,
    ),
    OVERLAY(
        "Draw over other apps",
        "Lets the block screen appear on top of the feed instead of behind it.",
        required = true,
    ),
    NOTIFICATIONS(
        "Notifications",
        "Used to tell you when protection has been switched off.",
        required = true,
    ),
    VPN_CONSENT(
        "Website filter (VPN)",
        "A local DNS filter. No traffic leaves the device and no server is involved.",
        required = false,
    ),
    BATTERY_UNRESTRICTED(
        "Unrestricted battery",
        "One UI will otherwise put the app to sleep and blocking stops silently.",
        required = true,
    ),
    DEVICE_ADMIN(
        "Uninstall lock",
        "Stops an impulsive uninstall by forcing a deactivation step first.",
        required = false,
    ),
    ;

    fun isSatisfied(context: Context): Boolean = when (this) {
        ACCESSIBILITY -> ServiceWatchdog.isAccessibilityEnabled(context)
        OVERLAY -> Settings.canDrawOverlays(context)
        NOTIFICATIONS -> context.checkSelfPermission(
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        VPN_CONSENT -> VpnService.prepare(context) == null
        BATTERY_UNRESTRICTED -> isIgnoringBatteryOptimizations(context)
        DEVICE_ADMIN -> AdminReceiver.isActive(context)
    }

    /** Null for steps handled by a runtime permission request or a result contract. */
    fun settingsIntent(context: Context): Intent? = when (this) {
        ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        OVERLAY -> Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
        NOTIFICATIONS -> null
        VPN_CONSENT -> null
        @Suppress("BatteryLife")
        BATTERY_UNRESTRICTED -> Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )
        DEVICE_ADMIN -> AdminReceiver.enableIntent(context)
    }

    companion object {
        fun isIgnoringBatteryOptimizations(context: Context): Boolean {
            val power = context.getSystemService(PowerManager::class.java) ?: return false
            return power.isIgnoringBatteryOptimizations(context.packageName)
        }
    }
}

/**
 * Samsung-specific steps with no intent to deep-link to, so they are shown as
 * instructions. These are the usual reason a blocker "just stops working" on One UI.
 */
val ONE_UI_MANUAL_STEPS = listOf(
    "Settings › Battery › Background usage limits › make sure DoomGuard is NOT in " +
        "\"Sleeping\" or \"Deep sleeping\" apps.",
    "Settings › Battery › turn off \"Put unused apps to sleep\", or add DoomGuard as " +
        "an exception.",
    "Settings › Connections › More connection settings › VPN › gear icon next to " +
        "DoomGuard › turn on \"Always-on VPN\" so the filter survives reboots.",
)
