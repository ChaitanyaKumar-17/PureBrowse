package com.purebrowse.vpn

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnStartVpn: Button = findViewById(R.id.btnStartVpn)
        val btnStopVpn: Button = findViewById(R.id.btnStopVpn)

        btnStartVpn.setOnClickListener {
            prepareVpn()
        }

        btnStopVpn.setOnClickListener {
            val intent = Intent(this, PureBrowseVpnService::class.java)
            intent.action = PureBrowseVpnService.ACTION_DISCONNECT
            startService(intent)
        }
    }

    private fun prepareVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            // We need to ask for permission
            startActivityForResult(intent, 1)
        } else {
            // We already have permission, start the VPN service
            startVpnService()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 1 && resultCode == RESULT_OK) {
            startVpnService()
        } else {
            super.onActivityResult(requestCode, resultCode, data)
            Toast.makeText(this, "VPN permission denied.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, PureBrowseVpnService::class.java)
        intent.action = PureBrowseVpnService.ACTION_CONNECT
        startService(intent)
    }
}
