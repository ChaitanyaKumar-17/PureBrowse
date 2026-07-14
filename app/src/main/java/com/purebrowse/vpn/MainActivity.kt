package com.purebrowse.vpn

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.VpnService
import android.os.Bundle
import android.view.View
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
    private lateinit var tvProgress: TextView
    private lateinit var btnStartVpn: Button
    private lateinit var btnStopVpn: Button
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE)

        btnStartVpn = findViewById(R.id.btnStartVpn)
        btnStopVpn = findViewById(R.id.btnStopVpn)
        val btnAddDomain: Button = findViewById(R.id.btnAddDomain)
        
        manualDomainsList = findViewById(R.id.manualDomainsList)
        etManualDomain = findViewById(R.id.etManualDomain)
        tvStreak = findViewById(R.id.tvStreak)
        tvProgress = findViewById(R.id.tvProgress)

        btnStartVpn.setOnClickListener { prepareVpn() }
        
        btnStopVpn.setOnClickListener {
            val count = prefs.getInt("disable_count", 0) + 1
            prefs.edit().putInt("disable_count", count).apply()
            updateUI()

            val intent = Intent(this, PureBrowseVpnService::class.java)
            intent.action = PureBrowseVpnService.ACTION_DISCONNECT
            startService(intent)
        }

        btnAddDomain.setOnClickListener {
            var domain = etManualDomain.text.toString().trim().lowercase()
            domain = domain.replace("https://", "").replace("http://", "")
            domain = domain.substringBefore("/")

            if (domain.isNotEmpty() && !domain.contains(" ")) {
                addManualDomain(domain)
            } else {
                Toast.makeText(this, "Invalid domain format", Toast.LENGTH_SHORT).show()
            }
        }

        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData("InitialBlocklistUpdateWork")
            .observe(this) { workInfos ->
                if (workInfos.isNotEmpty()) {
                    val workInfo = workInfos[0]
                    if (workInfo.state == WorkInfo.State.RUNNING) {
                        val progress = workInfo.progress.getString("PROGRESS") ?: "Syncing database..."
                        tvProgress.text = progress
                    } else if (workInfo.state == WorkInfo.State.SUCCEEDED) {
                        tvProgress.text = "Database Up to Date"
                    }
                }
            }

        updateUI()
        scheduleBlocklistUpdates()
        loadManualDomains()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun updateUI() {
        val count = prefs.getInt("disable_count", 0)
        tvStreak.text = "Protection Broken: $count Times"
        
        val isRunning = PureBrowseVpnService.isRunning
        btnStartVpn.visibility = if (isRunning) View.GONE else View.VISIBLE
        btnStopVpn.visibility = if (isRunning) View.VISIBLE else View.GONE
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
                    view.text = "${userDomain.domain} (Tap to remove)"
                    view.setTextColor(Color.parseColor("#B0B0B0"))
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
        // Delay UI update slightly to allow service to start
        etManualDomain.postDelayed({ updateUI() }, 500)
    }

    private fun scheduleBlocklistUpdates() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
            
        // Initial sync on startup
        val oneTimeRequest = OneTimeWorkRequestBuilder<BlocklistUpdateWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            "InitialBlocklistUpdateWork",
            ExistingWorkPolicy.KEEP,
            oneTimeRequest
        )

        // Periodic sync every 24 hours
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
