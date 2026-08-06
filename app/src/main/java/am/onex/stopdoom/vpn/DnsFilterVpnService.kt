package am.onex.stopdoom.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import am.onex.stopdoom.App
import am.onex.stopdoom.R
import am.onex.stopdoom.ui.MainActivity
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.concurrent.thread

/**
 * A local DNS filter built on VpnService. Nothing leaves the device except the
 * DNS queries that are allowed through, and those go to the configured upstream.
 *
 * The important design choice is the routing: only the tunnel's own fake DNS
 * address is routed into it. Every other packet takes its normal path and never
 * touches this process, which keeps throughput and battery cost near zero. A VPN
 * that routed 0.0.0.0/0 to userspace would be far more expensive for no extra
 * blocking power.
 */
class DnsFilterVpnService : VpnService() {

    @Volatile
    private var tunnel: ParcelFileDescriptor? = null

    @Volatile
    private var running = false

    private var worker: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            teardown()
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_STICKY
        startForegroundNotification()
        startTunnel()
        return START_STICKY
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    override fun onRevoke() {
        // The system revokes the tunnel when another VPN takes over. Say so rather
        // than silently stopping - website blocking is off from this moment.
        am.onex.stopdoom.guard.ServiceWatchdog.warn(
            this,
            "Website filter was switched off",
            "Another VPN took over the connection, so DNS filtering has stopped.",
        )
        teardown()
        super.onRevoke()
    }

    private fun startTunnel() {
        val container = (application as App).container
        val descriptor = runCatching {
            Builder()
                .setSession("DoomGuard DNS filter")
                .addAddress(TUN_ADDRESS, 32)
                .addDnsServer(TUN_DNS)
                // Route only the fake resolver. This is the whole trick.
                .addRoute(TUN_DNS, 32)
                .addDisallowedApplication(packageName)
                .setBlocking(true)
                .setMtu(MTU)
                .establish()
        }.getOrElse {
            Log.e(TAG, "could not establish tunnel", it)
            null
        }

        if (descriptor == null) {
            stopSelf()
            return
        }

        tunnel = descriptor
        running = true
        container.usage.log(System.currentTimeMillis(), "dns_filter_started")

        worker = thread(name = "doomguard-dns", isDaemon = true) {
            runLoop(descriptor, container)
        }
    }

    private fun runLoop(descriptor: ParcelFileDescriptor, container: am.onex.stopdoom.AppContainer) {
        val input = FileInputStream(descriptor.fileDescriptor)
        val output = FileOutputStream(descriptor.fileDescriptor)
        val buffer = ByteArray(MTU)

        val upstream = DatagramSocket()
        // Without protect(), our own upstream query would be routed back into the
        // tunnel and loop forever.
        protect(upstream)
        upstream.soTimeout = UPSTREAM_TIMEOUT_MS

        try {
            while (running) {
                val read = runCatching { input.read(buffer) }.getOrElse { -1 }
                if (read <= 0) {
                    if (read < 0) break else continue
                }

                val datagram = DnsPacket.parseIpv4Udp(buffer, read)
                if (datagram == null || datagram.dstPort != DNS_PORT) continue

                val host = DnsPacket.questionName(datagram.payload)
                val blocked = host != null &&
                    container.blocklist.current().blocksHost(host) &&
                    container.settings.current.webBlockingEnabled &&
                    !container.settings.current.maintenanceActiveAt(System.currentTimeMillis())

                if (blocked) {
                    val reply = DnsPacket.buildNxDomain(datagram.payload)
                    if (reply != null) {
                        writeBack(output, datagram, reply)
                        container.onDomainBlocked(host)
                    }
                    continue
                }

                forward(upstream, output, datagram, container)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "dns loop stopped", t)
        } finally {
            runCatching { upstream.close() }
            runCatching { input.close() }
            runCatching { output.close() }
        }
    }

    private fun forward(
        upstream: DatagramSocket,
        output: FileOutputStream,
        datagram: DnsPacket.Datagram,
        container: am.onex.stopdoom.AppContainer,
    ) {
        val resolver = container.settings.current.upstreamDns
        val target = runCatching { InetAddress.getByName(resolver) }.getOrNull() ?: return

        runCatching {
            upstream.send(
                DatagramPacket(
                    datagram.payload,
                    datagram.payload.size,
                    InetSocketAddress(target, DNS_PORT),
                ),
            )
            val response = ByteArray(MTU)
            val packet = DatagramPacket(response, response.size)
            upstream.receive(packet)
            writeBack(output, datagram, response.copyOf(packet.length))
        }.onFailure {
            // A timeout here is normal on a flaky network. Dropping the query lets
            // the client retry, which is better than synthesising a wrong answer.
            Log.d(TAG, "upstream query failed: ${it.message}")
        }
    }

    /** Swaps source and destination so the reply looks like it came from the resolver. */
    private fun writeBack(
        output: FileOutputStream,
        query: DnsPacket.Datagram,
        dnsPayload: ByteArray,
    ) {
        val packet = DnsPacket.buildIpv4Udp(
            srcIp = query.dstIp,
            dstIp = query.srcIp,
            srcPort = query.dstPort,
            dstPort = query.srcPort,
            payload = dnsPayload,
        )
        runCatching { output.write(packet) }
    }

    private fun teardown() {
        running = false
        worker?.interrupt()
        worker = null
        runCatching { tunnel?.close() }
        tunnel = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun startForegroundNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_vpn),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Website filter on")
            .setContentText("Filtering DNS locally. No traffic leaves the device.")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(
            NOTIF_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    companion object {
        private const val TAG = "DoomGuard/Dns"
        private const val CHANNEL_ID = "dns_filter"
        private const val NOTIF_ID = 4202
        private const val MTU = 1500
        private const val DNS_PORT = 53
        private const val UPSTREAM_TIMEOUT_MS = 5_000

        // Link-local-ish addresses inside the tunnel. Nothing else on the device
        // uses this range, so there is no collision with the real network.
        private const val TUN_ADDRESS = "10.7.7.2"
        private const val TUN_DNS = "10.7.7.3"

        const val ACTION_STOP = "am.onex.stopdoom.STOP_DNS"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, DnsFilterVpnService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, DnsFilterVpnService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
