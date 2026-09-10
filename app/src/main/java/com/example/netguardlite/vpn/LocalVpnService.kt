package com.example.netguardlite.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.example.netguardlite.MainActivity
import java.io.FileInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * خدمة VPN محلية: تلتقط حركة الجهاز (حسب نطاق الشبكة المختار)، وتستثني
 * التطبيقات "المسموحة" فقط عبر addDisallowedApplication. أي تطبيق غير
 * مسموح، حزمه تدخل النفق ولا تُمرر لأي مكان = قطع كامل، مع تسجيل
 * محاولة الاتصال (اسم التطبيق + الوجهة) بالسجل.
 */
class LocalVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.example.netguardlite.START"
        const val ACTION_STOP = "com.example.netguardlite.STOP"
        const val ACTION_UPDATE = "com.example.netguardlite.UPDATE"

        private const val CHANNEL_ID = "netguard_vpn_channel"
        private const val NOTIF_ID = 1001
        private const val FLOW_CACHE_WINDOW_MS = 4000L

        @Volatile
        var isRunning: Boolean = false
            private set

        /** هل النفق فعلياً شغال الآن (يتأثر بإعداد نطاق الشبكة) */
        @Volatile
        var isTunnelActive: Boolean = false
            private set
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var drainThread: Thread? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val recentFlows = ConcurrentHashMap<String, Long>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE -> {
                if (isRunning) refreshVpnState()
                return START_STICKY
            }
            else -> {
                startForeground(NOTIF_ID, buildNotification())
                isRunning = true
                registerNetworkCallback()
                refreshVpnState()
                return START_STICKY
            }
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                refreshVpnState()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                refreshVpnState()
            }

            override fun onLost(network: Network) {
                refreshVpnState()
            }
        }

        networkCallback = callback
        try {
            connectivityManager?.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
        }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
            } catch (e: Exception) {
            }
        }
        networkCallback = null
    }

    private fun currentTransportAllowsProtection(): Boolean {
        val scope = AllowListStore.getNetworkScope(this)
        if (scope == AllowListStore.SCOPE_BOTH) return true

        val cm = connectivityManager
            ?: (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).also {
                connectivityManager = it
            }

        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false

        return when (scope) {
            AllowListStore.SCOPE_WIFI_ONLY -> caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            AllowListStore.SCOPE_MOBILE_ONLY -> caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            else -> true
        }
    }

    /** يعيد تقييم هل النفق لازم يكون شغال أو متوقف حسب الشبكة الحالية */
    private fun refreshVpnState() {
        if (!isRunning) return
        if (currentTransportAllowsProtection()) {
            establishVpn()
        } else {
            teardownTunnelOnly()
        }
    }

    private fun establishVpn() {
        drainThread?.interrupt()
        vpnInterface?.close()

        val builder = Builder()
            .setSession("NetGuard Lite")
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .setBlocking(false)

        val allowedPackages = AllowListStore.getAllowedPackages(this)
        for (pkg in allowedPackages) {
            try {
                builder.addDisallowedApplication(pkg)
            } catch (e: PackageManager.NameNotFoundException) {
            }
        }

        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
        }

        vpnInterface = builder.establish()
        isTunnelActive = vpnInterface != null
        startDrainThread()
    }

    private fun teardownTunnelOnly() {
        drainThread?.interrupt()
        try {
            vpnInterface?.close()
        } catch (e: IOException) {
        }
        vpnInterface = null
        isTunnelActive = false
    }

    /**
     * يقرأ حزم التطبيقات المحجوبة، يستخرج منها اسم التطبيق ووجهة الاتصال
     * للتسجيل بالسجل، ثم يتجاهلها تماماً (بدون أي تمرير أو رد).
     */
    private fun startDrainThread() {
        val fd = vpnInterface ?: return
        drainThread = Thread {
            try {
                FileInputStream(fd.fileDescriptor).use { input ->
                    val buffer = ByteArray(32767)
                    while (!Thread.currentThread().isInterrupted) {
                        val bytesRead = input.read(buffer)
                        if (bytesRead < 0) break
                        try {
                            handleBlockedPacket(buffer, bytesRead)
                        } catch (e: Exception) {
                            // تجاهل أي خطأ بتحليل حزمة تالفة، ما يوقف الخدمة
                        }
                    }
                }
            } catch (e: IOException) {
                // طبيعي عند إغلاق النفق
            }
        }
        drainThread?.isDaemon = true
        drainThread?.start()
    }

    private fun handleBlockedPacket(buffer: ByteArray, length: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        val parsed = PacketParser.parse(buffer, length) ?: return

        val flowKey = "${parsed.protocol}:${parsed.sourcePort}:" +
            "${parsed.destAddress.joinToString(".") { (it.toInt() and 0xFF).toString() }}:${parsed.destPort}"

        val now = System.currentTimeMillis()
        val lastSeen = recentFlows[flowKey]
        if (lastSeen != null && now - lastSeen < FLOW_CACHE_WINDOW_MS) return
        recentFlows[flowKey] = now
        if (recentFlows.size > 2000) {
            val cutoff = now - FLOW_CACHE_WINDOW_MS
            recentFlows.entries.removeAll { it.value < cutoff }
        }

        val cm = connectivityManager
            ?: (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).also {
                connectivityManager = it
            }

        val local = InetSocketAddress(InetAddress.getByAddress(parsed.sourceAddress), parsed.sourcePort)
        val remote = InetSocketAddress(InetAddress.getByAddress(parsed.destAddress), parsed.destPort)

        val uid = try {
            cm.getConnectionOwnerUid(parsed.protocol, local, remote)
        } catch (e: Exception) {
            -1
        }
        if (uid <= 0) return

        val pm = packageManager
        val packages = try {
            pm.getPackagesForUid(uid)
        } catch (e: Exception) {
            null
        } ?: return
        val pkg = packages.firstOrNull() ?: return

        val appName = try {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }

        val protocolName = if (parsed.protocol == 6) "TCP" else "UDP"
        val destText = "${remote.address.hostAddress}:${remote.port}"

        ConnectionLog.record(pkg, appName, destText, protocolName)
    }

    private fun stopVpn() {
        isRunning = false
        unregisterNetworkCallback()
        teardownTunnelOnly()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopVpn()
        stopSelf()
        super.onRevoke()
    }

    private fun buildNotification(): android.app.Notification {
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "حماية الإنترنت",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NetGuard Lite نشط")
            .setContentText("الحماية شغالة - التطبيقات غير المسموحة محجوبة")
            .setSmallIcon(com.example.netguardlite.R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
