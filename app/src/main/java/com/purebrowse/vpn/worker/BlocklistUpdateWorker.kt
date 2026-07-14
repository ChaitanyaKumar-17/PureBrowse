package com.purebrowse.vpn.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.purebrowse.vpn.db.AppDatabase
import com.purebrowse.vpn.db.AutoDomain
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class BlocklistUpdateWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "BlocklistUpdateWorker"
        private const val META_URL = "https://raw.githubusercontent.com/Bon-Appetit/porn-domains/main/meta.json"
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Starting blocklist update check...")
        try {
            // 1. Fetch meta.json
            val metaResponse = fetchUrl(META_URL)
            if (metaResponse == null) {
                Log.e(TAG, "Failed to fetch meta.json")
                return Result.retry()
            }

            val metaJson = JSONObject(metaResponse)
            val blocklistObj = metaJson.optJSONObject("blocklist")
            val listUrl = blocklistObj?.optString("raw_url")
            val newVersion = blocklistObj?.optString("updated") ?: ""

            val prefs = applicationContext.getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE)
            val currentVersion = prefs.getString("blocklist_version", "")

            if (newVersion == currentVersion && currentVersion!!.isNotEmpty()) {
                Log.i(TAG, "Blocklist is already up to date ($newVersion). Skipping download.")
                setProgress(workDataOf("PROGRESS" to "Database Up to Date"))
                return Result.success()
            }

            Log.i(TAG, "New version detected: \$newVersion. Downloading blocklist...")

            setProgress(workDataOf("PROGRESS" to "Downloading list..."))

            if (listUrl.isNullOrEmpty()) {
                Log.e(TAG, "Failed to find raw_url in meta.json")
                return Result.retry()
            }

            // 2. Fetch the actual blocklist
            val listResponse = fetchUrl(listUrl)
            if (listResponse == null) {
                Log.e(TAG, "Failed to fetch blocklist from \$listUrl")
                return Result.retry()
            }

            // 3. Parse domains
            val domains = listResponse.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { AutoDomain(it) }

            Log.i(TAG, "Parsed \${domains.size} domains. Updating database...")

            setProgress(workDataOf("PROGRESS" to "Building database..."))

            // 4. Update the database within a transaction
            val database = AppDatabase.getDatabase(applicationContext)
            database.domainDao().replaceAllAutoDomains(domains)

            Log.i(TAG, "Blocklist update complete!")
            prefs.edit().putString("blocklist_version", newVersion).apply()
            setProgress(workDataOf("PROGRESS" to "Complete!"))

            return Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Error updating blocklist", e)
            return Result.retry()
        }
    }

    private fun fetchUrl(urlString: String): String? {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    response.append(line).append("\n")
                }
                return response.toString()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed HTTP GET for \$urlString", e)
        } finally {
            connection?.disconnect()
        }
        return null
    }
}
