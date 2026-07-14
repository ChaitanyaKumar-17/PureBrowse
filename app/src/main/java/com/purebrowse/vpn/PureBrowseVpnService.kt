package com.purebrowse.vpn

import android.app.Service
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log

class PureBrowseVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null

    companion object {
        const val ACTION_CONNECT = "com.purebrowse.vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.purebrowse.vpn.DISCONNECT"
        private const val TAG = "PureBrowseVpnService"

        init {
            System.loadLibrary("vpn-engine")
        }
    }

    private external fun startPacketProcessing(tunFd: Int)

    // Called from C++ via JNI
    fun isDomainBlocked(domain: String): Boolean {
        val db = com.purebrowse.vpn.db.AppDatabase.getDatabase(this)
        val dao = db.domainDao()
        return dao.isAutoBlockedSync(domain) > 0 || dao.isUserBlockedSync(domain) > 0
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            stopVpn()
            return Service.START_NOT_STICKY
        }

        connectVpn()
        return Service.START_STICKY
    }

    private fun connectVpn() {
        if (vpnInterface != null) {
            Log.i(TAG, "VPN already connected")
            return
        }

        val builder = Builder()
        builder.setSession("PureBrowse VPN")
        
        // Add a route to capture all traffic (0.0.0.0/0) or specific DNS traffic
        // To only capture DNS, we would normally route the DNS server IPs, 
        // but often we just capture all and filter port 53 packets in our native tunnel.
        // For now we set a dummy local address for the TUN interface.
        builder.addAddress("10.0.0.2", 24)
        
        // Route all traffic through the VPN. (Later we might refine this or just drop non-DNS traffic locally)
        builder.addRoute("0.0.0.0", 0)

        // Block connections without VPN (kill switch equivalent)
        builder.setBlocking(true)

        // Exclude our own app so we can actually fetch the blocklist!
        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to exclude own app", e)
        }

        try {
            vpnInterface = builder.establish()
            Log.i(TAG, "VPN interface established")
            
            val fd = vpnInterface?.fd ?: return
            
            // Start the JNI loop in a background thread so we don't block the Service
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
            Log.i(TAG, "VPN disconnected")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN interface", e)
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
    }

    override fun onRevoke() {
        // This fires if the user disables the VPN from system settings
        // §7a: This is where we will log the disconnect event to public storage to track "streaks".
        Log.w(TAG, "VPN access revoked by user system settings.")
        super.onRevoke()
        stopVpn()
    }
}
