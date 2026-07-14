package com.purebrowse.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat

class PureBrowseVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null

    companion object {
        const val ACTION_CONNECT = "com.purebrowse.vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.purebrowse.vpn.DISCONNECT"
        private const val TAG = "PureBrowseVpnService"
        var isRunning = false

        init {
            System.loadLibrary("vpn-engine")
        }
    }

    @androidx.annotation.Keep
    private external fun startPacketProcessing(tunFd: Int)

    // Called from C++ via JNI
    @androidx.annotation.Keep
    fun isDomainBlocked(domain: String): Boolean {
        val db = com.purebrowse.vpn.db.AppDatabase.getDatabase(this)
        val dao = db.domainDao()
        
        var currentDomain = domain
        while (currentDomain.isNotEmpty()) {
            if (dao.isAutoBlockedSync(currentDomain) > 0 || dao.isUserBlockedSync(currentDomain) > 0) {
                return true
            }
            val firstDotIndex = currentDomain.indexOf('.')
            if (firstDotIndex == -1 || firstDotIndex == currentDomain.length - 1) {
                break
            }
            currentDomain = currentDomain.substring(firstDotIndex + 1)
        }
        return false
    }

    // Called from C++ via JNI to protect the proxy socket
    @androidx.annotation.Keep
    fun protectSocket(fd: Int): Boolean {
        return protect(fd)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            stopVpn()
            return Service.START_NOT_STICKY
        }

        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "VPN_CHANNEL")
            .setContentTitle("PureBrowse VPN")
            .setContentText("Protection is active.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
        
        startForeground(1, notification)
        connectVpn()
        return Service.START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("VPN_CHANNEL", "VPN Status", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun connectVpn() {
        if (vpnInterface != null) {
            Log.i(TAG, "VPN already connected")
            return
        }

        val builder = Builder()
        builder.setSession("PureBrowse VPN")
        
        // MVP DNS Routing Trick:
        // We set our TUN interface to receive all DNS queries.
        // We do NOT route 0.0.0.0/0, so regular TCP traffic (like HTTPS) bypasses the VPN.
        builder.addAddress("10.0.0.2", 32)
        builder.addDnsServer("10.0.0.3")
        builder.addRoute("10.0.0.3", 32)

        builder.setBlocking(true)

        try {
            vpnInterface = builder.establish()
            Log.i(TAG, "VPN interface established")
            
            val fd = vpnInterface?.fd ?: return
            isRunning = true
            
            Thread {
                startPacketProcessing(fd)
            }.start()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish VPN", e)
        }
    }

    private fun stopVpn() {
        try {
            vpnInterface?.close()
            vpnInterface = null
            isRunning = false
            Log.i(TAG, "VPN disconnected")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN interface", e)
        }
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN access revoked by user system settings.")
        super.onRevoke()
        stopVpn()
    }
}
