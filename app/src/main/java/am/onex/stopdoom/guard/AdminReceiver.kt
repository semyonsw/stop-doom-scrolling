package am.onex.stopdoom.guard

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Device admin exists here for exactly one property: an active admin cannot be
 * uninstalled. That forces a deliberate deactivation step before removal.
 *
 * Worth being clear-eyed about the limit - since Android 6 the user can always
 * deactivate an admin, so this is friction rather than a lock. Paired with the
 * cooldown it is enough to outlast an impulse, which is the actual goal.
 */
class AdminReceiver : DeviceAdminReceiver() {

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "Turning this off lets you uninstall DoomGuard immediately. " +
            "If you are doing this to get at a feed, that is the impulse talking. " +
            "Maintenance mode is on the Guard screen if you need to work on the app."

    override fun onEnabled(context: Context, intent: Intent) {
        (context.applicationContext as? am.onex.stopdoom.App)?.container
            ?.usage?.log(System.currentTimeMillis(), "admin_enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        (context.applicationContext as? am.onex.stopdoom.App)?.container
            ?.usage?.log(System.currentTimeMillis(), "admin_disabled")
    }

    companion object {
        fun component(context: Context) = ComponentName(context, AdminReceiver::class.java)

        fun isActive(context: Context): Boolean {
            val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return false
            return dpm.isAdminActive(component(context))
        }

        fun enableIntent(context: Context): Intent =
            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(context))
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Stops DoomGuard from being uninstalled on impulse. " +
                        "No other device policies are used.",
                )
            }

        /** Deactivating is intentionally left to the system UI so it stays reversible. */
        fun deactivate(context: Context) {
            val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
            if (dpm.isAdminActive(component(context))) {
                dpm.removeActiveAdmin(component(context))
            }
        }
    }
}
