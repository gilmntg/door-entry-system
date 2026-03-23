package com.doorentry.household

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class CallActivity : AppCompatActivity() {

    companion object {
        private const val TIMEOUT_MAX_CALL_MS = 300_000L  // 5 minutes max
        private const val PERM_REQUEST_CODE   = 1001
    }

    private lateinit var webView: WebView
    private lateinit var mqttManager: MqttManager
    private val handler = Handler(Looper.getMainLooper())
    private var callStarted = false
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private var audioTrack: AudioTrack? = null
    private var playbackThread: Thread? = null
    private val audioQueue = java.util.concurrent.LinkedBlockingQueue<ByteArray>(100)

    private val maxCallTimeout = Runnable { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        // Dismiss the doorbell notification now that we're answering
        getSystemService(NotificationManager::class.java)
            .cancel(DoorbellService.NOTIF_ID_BELL)

        val cameraOk = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val audioOk  = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        if (cameraOk && audioOk) {
            startCall()
        } else {
            val needed = mutableListOf<String>()
            if (!cameraOk) needed.add(Manifest.permission.CAMERA)
            if (!audioOk)  needed.add(Manifest.permission.RECORD_AUDIO)
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERM_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_REQUEST_CODE) startCall()
    }

    private fun startCall() {
        if (callStarted) return
        callStarted = true

        // Request audio focus for a voice call — this causes Hey Google / Bixby
        // wake-word engines to release the microphone so the WebView can capture it.
        val am = getSystemService(AudioManager::class.java)
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        am.isSpeakerphoneOn = true
        val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAcceptsDelayedFocusGain(false)
            .build()
        am.requestAudioFocus(focusReq)
        audioFocusRequest = focusReq

        val config = AppConfig(this)

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            @Suppress("SetJavaScriptEnabled")
            javaScriptEnabled = true
        }

        webView.webViewClient = WebViewClient()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { request.grant(request.resources) }
            }
        }

        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun publishAnswer(json: String) {
                mqttManager.publishAnswer(json)
            }

            @JavascriptInterface
            fun unlockDoor() {
                mqttManager.publishUnlock()
            }

            @JavascriptInterface
            fun onCallEnd(reason: String) {
                handler.post { finish() }
            }
        }, "Android")

        val html = assets.open("household_call.html").bufferedReader().readText()
            .replace("MQTT_HOST_PLACEHOLDER", config.mqttHost)
            .replace("MQTT_USER_PLACEHOLDER", config.mqttUsername)
            .replace("MQTT_PASS_PLACEHOLDER", config.mqttPassword)

        webView.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null)

        handler.postDelayed(maxCallTimeout, TIMEOUT_MAX_CALL_MS)

        // Connect MQTT and deliver the pending offer to the WebView once loaded
        mqttManager = MqttManager(this)
        mqttManager.connect()

        // Capture mic natively and stream PCM over MQTT (WebView getUserMedia is broken on this device)
        startAudioStream()

        // AudioTrack to play incoming panel voice audio
        val sampleRate = 16000
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build())
            .setBufferSizeInBytes(minBuf * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack?.play()
        playbackThread = Thread {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val pcm = audioQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (pcm != null) audioTrack?.write(pcm, 0, pcm.size)
                } catch (e: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }
        mqttManager.onAudio = { pcm -> audioQueue.offer(pcm) }

        // Deliver the offer that was cached by DoorbellService
        val offer = DoorbellService.pendingOffer
        if (offer != null) {
            DoorbellService.pendingOffer = null
            // Wait briefly for the WebView JS to initialise before injecting
            handler.postDelayed({
                deliverOffer(offer)
            }, 800)
        }
    }

    private fun startAudioStream() {
        val sampleRate = 16000
        val chunkBytes = 640  // 20 ms at 16kHz 16-bit mono
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, chunkBytes * 4)
        )
        audioRecord = rec
        rec.startRecording()
        audioThread = Thread {
            val buf = ByteArray(chunkBytes)
            while (!Thread.currentThread().isInterrupted) {
                val read = rec.read(buf, 0, buf.size)
                if (read > 0) mqttManager.publishAudio(buf.copyOf(read))
            }
        }.apply { isDaemon = true; start() }
    }

    private fun stopAudioStream() {
        audioThread?.interrupt()
        audioThread = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    private fun deliverOffer(offerJson: String) {
        val encoded = android.util.Base64.encodeToString(
            offerJson.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        handler.post {
            webView.evaluateJavascript("window.onWebRtcOffer(atob('$encoded'))", null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(maxCallTimeout)
        stopAudioStream()
        playbackThread?.interrupt()
        playbackThread = null
        audioQueue.clear()
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        try { mqttManager.publishHangup(); mqttManager.disconnect() } catch (e: Exception) {}
        if (callStarted) webView.destroy()
        audioFocusRequest?.let {
            val am = getSystemService(AudioManager::class.java)
            am.abandonAudioFocusRequest(it)
            am.isSpeakerphoneOn = false
            am.mode = AudioManager.MODE_NORMAL
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Ignore back — use the Hang Up button in the WebView
    }
}
