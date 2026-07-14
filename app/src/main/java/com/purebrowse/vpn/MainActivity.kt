package com.purebrowse.vpn

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.work.*
import com.purebrowse.vpn.db.AppDatabase
import com.purebrowse.vpn.db.UserDomain
import com.purebrowse.vpn.worker.BlocklistUpdateWorker
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var manualDomainsList: LinearLayout
    private lateinit var etManualDomain: EditText
    private lateinit var tvStreak: TextView
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE)

        val btnStartVpn: Button = findViewById(R.id.btnStartVpn)
        val btnStopVpn: Button = findViewById(R.id.btnStopVpn)
        val btnAddDomain: Button = findViewById(R.id.btnAddDomain)
        
        manualDomainsList = findViewById(R.id.manualDomainsList)
        etManualDomain = findViewById(R.id.etManualDomain)
        tvStreak = findViewById(R.id.tvStreak)

        btnStartVpn.setOnClickListener { prepareVpn() }
        
        btnStopVpn.setOnClickListener {
            // Track the streak break
            val count = prefs.getInt("disable_count", 0) + 1
            prefs.edit().putInt("disable_count", count).apply()
            updateStreakUI()

            val intent = Intent(this, PureBrowseVpnService::class.java)
            intent.action = PureBrowseVpnService.ACTION_DISCONNECT
            startService(intent)
        }

        btnAddDomain.setOnClickListener {
            val domain = etManualDomain.text.toString().trim().lowercase()
            if (domain.isNotEmpty() && !domain.contains(" ")) {
                addManualDomain(domain)
            } else {
                Toast.makeText(this, "Invalid domain format", Toast.LENGTH_SHORT).show()
            }
        }

        updateStreakUI()
        scheduleBlocklistUpdates()
        loadManualDomains()
    }

    private fun updateStreakUI() {
        val count = prefs.getInt("disable_count", 0)
        tvStreak.text = "Protection Broken: \$count Times"
    }

    private fun addManualDomain(domain: String) {
        Thread {
            val db = AppDatabase.getDatabase(this)
            db.domainDao().insertUserDomain(UserDomain(domain, System.currentTimeMillis()))
            runOnUiThread {
                etManualDomain.text.clear()
                loadManualDomains()
            }
        }.start()
    }

    private fun removeManualDomain(domain: String) {
        Thread {
            val db = AppDatabase.getDatabase(this)
            db.domainDao().deleteUserDomain(domain)
            runOnUiThread {
                loadManualDomains()
            }
        }.start()
    }

    private fun loadManualDomains() {
        Thread {
            val db = AppDatabase.getDatabase(this)
            val domains = db.domainDao().getAllUserDomains()
            runOnUiThread {
                manualDomainsList.removeAllViews()
                for (userDomain in domains) {
                    val view = layoutInflater.inflate(android.R.layout.simple_list_item_1, null) as TextView
                    view.text = "\${userDomain.domain} (Tap to remove)"
                    view.setOnClickListener {
                        removeManualDomain(userDomain.domain)
                    }
                    manualDomainsList.addView(view)
                }
            }
        }.start()
    }

    private fun prepareVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, 1)
        } else {
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

    private fun scheduleBlocklistUpdates() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val updateRequest = PeriodicWorkRequestBuilder<BlocklistUpdateWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "BlocklistUpdateWork",
            ExistingPeriodicWorkPolicy.KEEP,
            updateRequest
        )
    }
}
