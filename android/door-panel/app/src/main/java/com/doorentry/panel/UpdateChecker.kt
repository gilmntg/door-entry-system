package com.doorentry.panel

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class UpdateChecker(private val context: Context) {

    companion object {
        private const val TAG = "UpdateChecker"
    }

    fun checkForUpdates(onComplete: (() -> Unit)? = null) {
        Log.d(TAG, "checkForUpdates: starting...")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Fetching version from http://192.168.68.61:8123/local/apps-version.json")
                val url = URL("http://192.168.68.61:8123/local/apps-version.json")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                }
                Log.d(TAG, "HTTP Response: ${conn.responseCode}")
                val json = conn.inputStream.bufferedReader().readText()
                Log.d(TAG, "JSON received: $json")
                val remoteVersion = JSONObject(json).getJSONObject("panel")
                val remoteCode = remoteVersion.getInt("versionCode")
                val remoteName = remoteVersion.getString("versionName")
                val downloadUrl = remoteVersion.getString("downloadUrl")
                val releaseNotes = remoteVersion.optString("releaseNotes", "")

                Log.d(TAG, "Local versionCode: ${BuildConfig.VERSION_CODE}, Remote: $remoteCode")

                if (remoteCode > BuildConfig.VERSION_CODE) {
                    Log.d(TAG, "Update available! Showing dialog...")
                    CoroutineScope(Dispatchers.Main).launch {
                        showUpdateDialog(remoteName, releaseNotes, downloadUrl)
                    }
                } else {
                    Log.d(TAG, "No update needed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking for updates: ${e.message}", e)
            } finally {
                onComplete?.invoke()
            }
        }
    }

    private fun showUpdateDialog(versionName: String, releaseNotes: String, downloadUrl: String) {
        Log.d(TAG, "showUpdateDialog: $versionName")
        AlertDialog.Builder(context)
            .setTitle("Update Available")
            .setMessage("Version $versionName is available.\n\n$releaseNotes")
            .setPositiveButton("Download") { _, _ ->
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)))
            }
            .setNegativeButton("Later", null)
            .show()
    }
}

