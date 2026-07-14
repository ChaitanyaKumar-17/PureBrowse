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
        // Assuming blocklist is here. In reality, we might extract this URL from the meta.json response.
        private const val LIST_URL = "https://raw.githubusercontent.com/Bon-Appetit/porn-domains/main/blocklist.txt"
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
            val newVersion = metaJson.optString("version", "") // Or "hash" depending on their JSON structure

            // TODO: Compare newVersion with a locally stored version in SharedPreferences.
            // If they are the same, return Result.success() immediately.

            Log.i(TAG, "New version detected: \$newVersion. Downloading blocklist...")

            setProgress(workDataOf("PROGRESS" to "Downloading list..."))

            // 2. Fetch the actual blocklist
            val listResponse = fetchUrl(LIST_URL)
            if (listResponse == null) {
                Log.e(TAG, "Failed to fetch blocklist.txt")
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
            setProgress(workDataOf("PROGRESS" to "Complete!"))

            // TODO: Save the newVersion string to SharedPreferences so we don't re-download it tomorrow unless it changes.

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
