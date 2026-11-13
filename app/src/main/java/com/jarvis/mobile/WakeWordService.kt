
package com.jarvis.mobile

import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.speech.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class WakeWordService : Service() {

    private var recognizer: SpeechRecognizer? = null

    private val handler = Handler(Looper.getMainLooper())

    private var listening = false
    private var commandMode = false
    private var running = false

    private var restartTask: Runnable? = null

    private var receiver: BroadcastReceiver? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()

        running = true

        createChannel()

        startForeground(
            NOTIFICATION_ID,
            notification("Starting JARVIS...")
        )

        acquireWakeLock()
        registerControlReceiver()

        if (!hasPermission()) {
            stopSelf()
            return
        }

        if (
            Build.VERSION.SDK_INT < 31 ||
            !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        ) {
            updateNotification(
                "Offline speech recognition unavailable"
            )
            stopSelf()
            return
        }

        startListening()
    }

    private fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager

            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:WakeWord"
            )

            wakeLock?.setReferenceCounted(false)
            wakeLock?.acquire()

        } catch (_: Exception) {
        }
    }

    private fun registerControlReceiver() {

        val control = object : BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {

                when (intent?.action) {

                    ACTION_PAUSE -> {
                        pauseListening()
                    }

                    ACTION_RESUME -> {
                        commandMode = false
                        scheduleRestart(300)
                    }
                }
            }
        }

        receiver = control

        val filter = IntentFilter().apply {
            addAction(ACTION_PAUSE)
            addAction(ACTION_RESUME)
        }

        ContextCompat.registerReceiver(
            this,
            control,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun startListening() {

        if (!running || commandMode || listening) return

        if (!hasPermission()) {
            stopSelf()
            return
        }

        try {

            val speech = SpeechRecognizer.createOnDeviceSpeechRecognizer(
                this
            )

            recognizer = speech
            listening = true

            speech.setRecognitionListener(
                object : RecognitionListener {

                    override fun onReadyForSpeech(params: Bundle?) {
                        updateNotification(
                            "Listening for Hey JARVIS"
                        )
                    }

                    override fun onBeginningOfSpeech() {}

                    override fun onRmsChanged(rmsdB: Float) {}

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {}

                    override fun onEvent(
                        eventType: Int,
                        params: Bundle?
                    ) {}

                    override fun onPartialResults(
                        results: Bundle?
                    ) {

                        val text = results
                            ?.getStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION
                            )
                            ?.firstOrNull()
                            .orEmpty()

                        if (isWakeWord(text)) {
                            triggerWake()
                        }
                    }

                    override fun onResults(results: Bundle?) {

                        val text = results
                            ?.getStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION
                            )
                            ?.firstOrNull()
                            .orEmpty()

                        if (isWakeWord(text)) {
                            triggerWake()
                        } else {
                            releaseRecognizer(speech)
                            scheduleRestart(1000)
                        }
                    }

                    override fun onError(error: Int) {

                        releaseRecognizer(speech)

                        scheduleRestart(
                            if (error ==
                                SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                            ) 2500 else 1200
                        )
                    }
                }
            )

            val intent = Intent(
                RecognizerIntent.ACTION_RECOGNIZE_SPEECH
            ).apply {

                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )

                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE,
                    "en-IN"
                )

                putExtra(
                    RecognizerIntent.EXTRA_PREFER_OFFLINE,
                    true
                )

                putExtra(
                    RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                    true
                )

                putExtra(
                    RecognizerIntent.EXTRA_MAX_RESULTS,
                    3
                )

                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                    1400L
                )
            }

            speech.startListening(intent)

        } catch (_: Exception) {

            stopRecognizer()
            scheduleRestart(2500)
        }
    }

    private fun isWakeWord(text: String): Boolean {

        val normalized = text
            .lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        return normalized.contains("hey jarvis") ||
            normalized.contains("hello jarvis")
    }

    private fun triggerWake() {

        if (commandMode) return

        commandMode = true

        cancelRestart()
        stopRecognizer()

        val open = Intent(
            this,
            MainActivity::class.java
        ).apply {

            putExtra(
                EXTRA_WAKE_TRIGGERED,
                true
            )

            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }

        val pending = PendingIntent.getActivity(
            this,
            4202,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        updateNotification(
            "JARVIS heard you. Tap to speak.",
            pending
        )

        sendBroadcast(
            Intent(ACTION_WAKE)
                .setPackage(packageName)
        )
    }

    private fun pauseListening() {

        commandMode = true

        cancelRestart()
        stopRecognizer()

        updateNotification(
            "JARVIS processing your command"
        )
    }

    private fun releaseRecognizer(
        speech: SpeechRecognizer
    ) {

        if (recognizer === speech) {
            recognizer = null
            listening = false
            runCatching { speech.destroy() }
        }
    }

    private fun stopRecognizer() {

        val current = recognizer

        recognizer = null
        listening = false

        current?.let {
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
    }

    private fun scheduleRestart(delay: Long) {

        if (!running || commandMode) return

        cancelRestart()

        val task = Runnable {
            restartTask = null
            startListening()
        }

        restartTask = task

        handler.postDelayed(task, delay)
    }

    private fun cancelRestart() {

        restartTask?.let {
            handler.removeCallbacks(it)
        }

        restartTask = null
    }

    private fun notification(
        message: String,
        pending: PendingIntent? = null
    ): Notification {

        val builder = NotificationCompat.Builder(
            this,
            CHANNEL_ID
        )
            .setSmallIcon(
                android.R.drawable.ic_btn_speak_now
            )
            .setContentTitle("JARVIS Offline")
            .setContentText(message)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(
                NotificationCompat.CATEGORY_SERVICE
            )
            .setPriority(
                NotificationCompat.PRIORITY_LOW
            )

        if (pending != null) {
            builder.setContentIntent(pending)
        }

        return builder.build()
    }

    private fun updateNotification(
        message: String,
        pending: PendingIntent? = null
    ) {

        getSystemService(
            NotificationManager::class.java
        ).notify(
            NOTIFICATION_ID,
            notification(message, pending)
        )
    }

    private fun createChannel() {

        if (Build.VERSION.SDK_INT >= 26) {

            val channel = NotificationChannel(
                CHANNEL_ID,
                "JARVIS Wake Word",
                NotificationManager.IMPORTANCE_LOW
            )

            getSystemService(
                NotificationManager::class.java
            ).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {

            ACTION_PAUSE -> pauseListening()

            ACTION_RESUME -> {
                commandMode = false
                scheduleRestart(300)
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {

        running = false

        cancelRestart()
        handler.removeCallbacksAndMessages(null)

        stopRecognizer()

        receiver?.let {
            runCatching {
                unregisterReceiver(it)
            }
        }

        receiver = null

        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }

        wakeLock = null

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {

        const val ACTION_WAKE =
            "com.jarvis.mobile.WAKE_DETECTED"

        const val ACTION_PAUSE =
            "com.jarvis.mobile.PAUSE_WAKE_LISTENER"

        const val ACTION_RESUME =
            "com.jarvis.mobile.RESUME_WAKE_LISTENER"

        const val EXTRA_WAKE_TRIGGERED =
            "wake_triggered"

        private const val CHANNEL_ID = "jarvis_wake"
        private const val NOTIFICATION_ID = 4201
    }
}
