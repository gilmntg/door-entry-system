package com.doorentry.household

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class UpdateChecker(private val context: Context) {

    fun checkForUpdates(onComplete: (() -> Unit)? = null) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("http://192.168.68.61:8123/local/apps-version.json")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                }
                val json = conn.inputStream.bufferedReader().readText()
                val remoteVersion = JSONObject(json).getJSONObject("household")
                val remoteCode = remoteVersion.getInt("versionCode")
                val remoteName = remoteVersion.getString("versionName")
                val downloadUrl = remoteVersion.getString("downloadUrl")
                val releaseNotes = remoteVersion.optString("releaseNotes", "")

                if (remoteCode > BuildConfig.VERSION_CODE) {
                    CoroutineScope(Dispatchers.Main).launch {
                        showUpdateDialog(remoteName, releaseNotes, downloadUrl)
                    }
                }
            } catch (e: Exception) {
                // Silently fail — no network or JSON parse error
            } finally {
                onComplete?.invoke()
            }
        }
    }

    private fun showUpdateDialog(versionName: String, releaseNotes: String, downloadUrl: String) {
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
