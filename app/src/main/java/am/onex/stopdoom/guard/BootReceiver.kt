package am.onex.stopdoom.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import am.onex.stopdoom.vpn.DnsFilterVpnService

/**
 * Restores what can be restored after a reboot or an app update.
 *
 * The accessibility service is brought back by the system on its own - an app
 * cannot enable it - so all this can do is bring the DNS filter back up and
 * re-arm the periodic jobs.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            else -> return
        }

        ApplyPendingWorker.schedule(context)
        ServiceWatchdog.schedule(context)

        // VpnService.prepare returns non-null when consent has not been granted yet;
        // consent cannot be requested from a receiver, so in that case the honest
        // move is to notify rather than fail silently.
        if (VpnService.prepare(context) != null) {
            ServiceWatchdog.warn(
                context,
                "Website filter needs a tap",
                "Open DoomGuard once to restart the DNS filter after this restart. " +
                    "Setting DoomGuard as an always-on VPN in network settings avoids this.",
            )
            return
        }

        runCatching { DnsFilterVpnService.start(context) }
            .onFailure { Log.w(TAG, "could not restart DNS filter after boot", it) }
    }

    private companion object {
        const val TAG = "DoomGuard/Boot"
    }
}
