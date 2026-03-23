package com.doorentry.panel

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
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

class VideoCallActivity : AppCompatActivity() {

    companion object {
        private const val TIMEOUT_NO_ANSWER_MS = 60_000L   // close if no one joins within 60s
        private const val TIMEOUT_MAX_CALL_MS  = 180_000L  // force-close after 3 min regardless
        private const val PERM_REQUEST_CODE    = 1001
    }

    private lateinit var webView: WebView
    private lateinit var mqttManager: MqttManager
    private val handler = Handler(Looper.getMainLooper())
    private var callStarted = false
    private var audioTrack: AudioTrack? = null
    private var playbackThread: Thread? = null
    private val audioQueue = java.util.concurrent.LinkedBlockingQueue<ByteArray>(100)
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null

    private val noAnswerTimeout = Runnable { closeCall() }
    private val maxCallTimeout  = Runnable { closeCall() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

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

        // Route audio through speaker, not earpiece
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true

        // AudioTrack for receiving household voice over MQTT PCM stream
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
                // Must run on main thread; grants camera/mic to the WebView
                runOnUiThread { request.grant(request.resources) }
            }
        }

        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onParticipantJoined() {
                // Household connected — cancel the no-answer timer
                handler.post { handler.removeCallbacks(noAnswerTimeout) }
            }

            @JavascriptInterface
            fun onCallEnd(reason: String) {
                handler.post { closeCall() }
            }

            @JavascriptInterface
            fun publishOffer(json: String) {
                // Route WebRTC offer through Java MQTT (TCP 1883) to avoid
                // Chrome Private Network Access block on ws:// from http://localhost
                mqttManager.publishVideoOffer(json)
            }
        }, "Android")

        val html = assets.open("call.html").bufferedReader().readText()
            .replace("MQTT_HOST_PLACEHOLDER", config.mqttHost)
            .replace("MQTT_USER_PLACEHOLDER", config.mqttUsername)
            .replace("MQTT_PASS_PLACEHOLDER", config.mqttPassword)

        // http://localhost is a secure context in Chromium — getUserMedia works and ws:// is allowed
        webView.loadDataWithBaseURL("http://localhost", html, "text/html", "UTF-8", null)

        // Start timers
        handler.postDelayed(noAnswerTimeout, TIMEOUT_NO_ANSWER_MS)
        handler.postDelayed(maxCallTimeout, TIMEOUT_MAX_CALL_MS)

        // Listen for MQTT messages via Java TCP client (no WebSocket needed in WebView)
        mqttManager = MqttManager(this)
        mqttManager.onAccessResult = { granted ->
            if (granted) handler.post { closeCall() }
        }
        mqttManager.onAudio = { pcm ->
            audioQueue.offer(pcm)  // non-blocking; drops frame if queue is full
        }
        mqttManager.onHangup = {
            handler.post { closeCall() }
        }
        mqttManager.onWebRtcAnswer = { answerJson ->
            // Pass answer to WebView via bridge — Base64 encode to safely embed in JS string
            val encoded = android.util.Base64.encodeToString(
                answerJson.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            handler.post {
                webView.evaluateJavascript("window.onWebRtcAnswer(atob('$encoded'))", null)
            }
        }
        mqttManager.connect()
        startAudioCapture()
    }

    private fun startAudioCapture() {
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
        captureThread = Thread {
            val buf = ByteArray(chunkBytes)
            while (!Thread.currentThread().isInterrupted) {
                val read = rec.read(buf, 0, buf.size)
                if (read > 0) mqttManager.publishAudio(buf.copyOf(read))
            }
        }.apply { isDaemon = true; start() }
    }

    private fun stopAudioCapture() {
        captureThread?.interrupt()
        captureThread = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    fun closeCall() {
        handler.removeCallbacks(noAnswerTimeout)
        handler.removeCallbacks(maxCallTimeout)
        try {
            mqttManager.clearVideoOffer()  // wipe retained offer so stale calls don't confuse household page
            mqttManager.disconnect()
        } catch (e: Exception) { }
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(noAnswerTimeout)
        handler.removeCallbacks(maxCallTimeout)
        try { mqttManager.disconnect() } catch (e: Exception) { }
        if (callStarted) webView.destroy()
        stopAudioCapture()
        playbackThread?.interrupt()
        playbackThread = null
        audioQueue.clear()
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioManager.isSpeakerphoneOn = false
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Block back button — kiosk mode, call ends only on timeout or hang-up
    }
}
