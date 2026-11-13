package com.jarvis.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat

class WakeWordService : Service() {
    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var waitingForUser = false
    private var running = true
    private var commandReceiver: android.content.BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Listening for Hey JARVIS — on-device speech only"))
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: Intent?) {
                waitingForUser = false
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification("Listening for Hey JARVIS — on-device speech only"))
                scheduleRestart()
            }
        }
        commandReceiver = receiver
        androidx.core.content.ContextCompat.registerReceiver(this, receiver, android.content.IntentFilter(MainActivity.ACTION_COMMAND), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) startListening()
        else { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }

    private fun startListening() {
        if (waitingForUser) return
        runCatching {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this).also { r ->
                r.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(error: Int) { scheduleRestart() }
                    override fun onResults(results: Bundle?) {
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                        if (text.contains("hey jarvis", ignoreCase = true) || text.contains("hello jarvis", ignoreCase = true)) {
                            waitingForUser = true
                            sendBroadcast(Intent(ACTION_WAKE).setPackage(packageName))
                            val open = Intent(this@WakeWordService, MainActivity::class.java).putExtra("wake_triggered", true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            val pending = PendingIntent.getActivity(this@WakeWordService, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification("JARVIS heard you. Tap to speak.", pending))
                        } else scheduleRestart()
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                        if (text.contains("hey jarvis", ignoreCase = true)) {
                            onResults(partialResults)
                            runCatching { r.cancel() }
                        }
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
                }
                r.startListening(intent)
            }
        }.onFailure { scheduleRestart() }
    }

    private fun scheduleRestart() { handler.postDelayed({ if (running && !waitingForUser) startListening() }, 900) }

    private fun notification(message: String, pending: PendingIntent? = null): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("JARVIS Offline").setContentText(message).setOngoing(true).setCategory(NotificationCompat.CATEGORY_SERVICE).setPriority(NotificationCompat.PRIORITY_LOW)
        if (pending != null) builder.setContentIntent(pending).setAutoCancel(false)
        return builder.build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL_ID, "JARVIS wake word", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onDestroy() {
        running = false
        commandReceiver?.let { runCatching { unregisterReceiver(it) } }
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy(); recognizer = null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_WAKE = "com.jarvis.mobile.WAKE_DETECTED"
        private const val CHANNEL_ID = "jarvis_wake"
        private const val NOTIFICATION_ID = 4201
    }
}
