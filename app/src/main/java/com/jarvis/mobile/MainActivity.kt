
package com.jarvis.mobile

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Navy = Color(0xFF070B16)
private val Panel = Color(0xFF101A2B)
private val Cyan = Color(0xFF39D8FF)
private val Muted = Color(0xFF91A5BC)
private val White = Color(0xFFEAF5FF)

class MainActivity : ComponentActivity() {

    private var tts: TextToSpeech? = null
    private var commandRecognizer: SpeechRecognizer? = null
    private var wakeReceiver: BroadcastReceiver? = null

    private var commandRecognitionActive = false
    private var pendingWake = false
    private var wakeEnabled = false

    private val preferences by lazy {
        getSharedPreferences("jarvis_settings", MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pendingWake =
            intent?.getBooleanExtra("wake_triggered", false) == true

        wakeEnabled = preferences.getBoolean("wake_enabled", false)

        initializeTts()
        registerWakeReceiver()

        setContent {
            JarvisOfflineApp(
                onSpeak = { speak(it) },
                onWakeChanged = { enabled ->
                    setWakeWord(enabled)
                },
                onVoice = {
                    startCommandRecognition()
                },
                wakeOnOpen = pendingWake,
                initiallyWakeEnabled = wakeEnabled
            )
        }

        // Restore the wake listener when the activity is opened again.
        if (wakeEnabled && hasRequiredPermissions()) {
            startWakeService()
        }
    }

    private fun initializeTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("en", "IN")
            }
        }

        tts?.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {

                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    runOnUiThread {
                        resumeWakeListener()
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    runOnUiThread {
                        resumeWakeListener()
                    }
                }

                override fun onError(
                    utteranceId: String?,
                    errorCode: Int
                ) {
                    runOnUiThread {
                        resumeWakeListener()
                    }
                }
            }
        )
    }

    private fun speak(text: String) {
        val engine = tts

        if (engine == null) {
            resumeWakeListener()
            return
        }

        val locale = if (
            text.any { it in '\u0D00'..'\u0D7F' }
        ) {
            Locale("ml", "IN")
        } else {
            Locale("en", "IN")
        }

        val languageResult = engine.setLanguage(locale)

        if (
            languageResult == TextToSpeech.LANG_MISSING_DATA ||
            languageResult == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            engine.setLanguage(Locale.getDefault())
        }

        engine.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "jarvis-response"
        )
    }

    private fun registerWakeReceiver() {
        val receiver = object : BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {
                if (intent?.action == WakeWordService.ACTION_WAKE) {
                    pendingWake = true

                    if (hasWindowFocus()) {
                        startCommandRecognition()
                    }
                }
            }
        }

        wakeReceiver = receiver

        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(WakeWordService.ACTION_WAKE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun hasRequiredPermissions(): Boolean {
        val audioGranted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

        val notificationGranted =
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED

        return audioGranted && notificationGranted
    }

    private fun setWakeWord(enabled: Boolean) {
        if (!enabled) {
            wakeEnabled = false

            preferences.edit()
                .putBoolean("wake_enabled", false)
                .apply()

            stopService(
                Intent(this, WakeWordService::class.java)
            )

            return
        }

        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO
        )

        if (Build.VERSION.SDK_INT >= 33) {
            permissions.add(
                Manifest.permission.POST_NOTIFICATIONS
            )
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(
                this,
                it
            ) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            requestPermissions(
                missingPermissions.toTypedArray(),
                REQUEST_WAKE_PERMISSIONS
            )
            return
        }

        if (
            Build.VERSION.SDK_INT < 31 ||
            !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        ) {
            Toast.makeText(
                this,
                "On-device speech recognition is unavailable. Check your offline speech language pack.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        wakeEnabled = true

        preferences.edit()
            .putBoolean("wake_enabled", true)
            .apply()

        startWakeService()
    }

    private fun startWakeService() {
        if (!hasRequiredPermissions()) return

        if (
            Build.VERSION.SDK_INT >= 31 &&
            !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        ) {
            return
        }

        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, WakeWordService::class.java)
            )
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Could not start the wake-word service: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == REQUEST_WAKE_PERMISSIONS) {
            if (
                grantResults.isNotEmpty() &&
                grantResults.all {
                    it == PackageManager.PERMISSION_GRANTED
                }
            ) {
                setWakeWord(true)
            } else {
                wakeEnabled = false

                preferences.edit()
                    .putBoolean("wake_enabled", false)
                    .apply()

                Toast.makeText(
                    this,
                    "Microphone and notification permissions are required.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        if (requestCode == REQUEST_COMMAND_PERMISSION) {
            if (
                grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                startCommandRecognition()
            }
        }
    }

    private fun pauseWakeListener() {
        if (!wakeEnabled) return

        sendBroadcast(
            Intent(WakeWordService.ACTION_PAUSE)
                .setPackage(packageName)
        )
    }

    private fun resumeWakeListener() {
        if (!wakeEnabled || isFinishing || isDestroyed) return

        sendBroadcast(
            Intent(WakeWordService.ACTION_RESUME)
                .setPackage(packageName)
        )
    }

    private fun startCommandRecognition() {
        if (commandRecognitionActive) return

        pendingWake = false

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_COMMAND_PERMISSION
            )
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(
                this,
                "Speech recognition is unavailable.",
                Toast.LENGTH_SHORT
            ).show()
            resumeWakeListener()
            return
        }

        commandRecognitionActive = true
        pauseWakeListener()

        try {
            val recognizer =
                if (
                    Build.VERSION.SDK_INT >= 31 &&
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
                ) {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
                } else {
                    SpeechRecognizer.createSpeechRecognizer(this)
                }

            commandRecognizer = recognizer

            val recognitionIntent =
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {

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
                        false
                    )

                    if (Build.VERSION.SDK_INT >= 34) {
                        putExtra(
                            "android.speech.extra.ENABLE_LANGUAGE_DETECTION",
                            true
                        )

                        putStringArrayListExtra(
                            "android.speech.extra.LANGUAGE_DETECTION_ALLOWED_LANGUAGES",
                            arrayListOf("en-IN", "ml-IN")
                        )
                    }

                    putExtra(
                        RecognizerIntent.EXTRA_PROMPT,
                        "Speak to JARVIS / JARVIS-നോട് സംസാരിക്കൂ"
                    )
                }

            recognizer.setRecognitionListener(
                object : RecognitionListener {

                    override fun onReadyForSpeech(params: Bundle?) {}

                    override fun onBeginningOfSpeech() {}

                    override fun onRmsChanged(rmsdB: Float) {}

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {}

                    override fun onPartialResults(
                        partialResults: Bundle?
                    ) {}

                    override fun onEvent(
                        eventType: Int,
                        params: Bundle?
                    ) {}

                    override fun onError(error: Int) {
                        finishCommandRecognition()
                    }

                    override fun onResults(results: Bundle?) {
                        val spoken =
                            results
                                ?.getStringArrayList(
                                    SpeechRecognizer.RESULTS_RECOGNITION
                                )
                                ?.firstOrNull()

                        finishCommandRecognition()

                        if (!spoken.isNullOrBlank()) {
                            sendBroadcast(
                                Intent(ACTION_COMMAND)
                                    .setPackage(packageName)
                                    .putExtra("command", spoken)
                            )
                        } else {
                            resumeWakeListener()
                        }
                    }
                }
            )

            recognizer.startListening(recognitionIntent)

        } catch (e: Exception) {
            finishCommandRecognition()

            Toast.makeText(
                this,
                "Could not start voice recognition: ${e.message}",
                Toast.LENGTH_LONG
            ).show()

            resumeWakeListener()
        }
    }

    private fun finishCommandRecognition() {
        commandRecognitionActive = false

        commandRecognizer?.let {
            try {
                it.destroy()
            } catch (_: Exception) {
            }
        }

        commandRecognizer = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        if (intent.getBooleanExtra("wake_triggered", false)) {
            pendingWake = true
            startCommandRecognition()
        }
    }

    override fun onDestroy() {
        wakeReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }

        finishCommandRecognition()

        tts?.stop()
        tts?.shutdown()
        tts = null

        super.onDestroy()
    }

    companion object {
        const val ACTION_COMMAND =
            "com.jarvis.mobile.COMMAND"

        private const val REQUEST_WAKE_PERMISSIONS = 701
        private const val REQUEST_COMMAND_PERMISSION = 702
    }
}

data class ChatLine(
    val role: String,
    val text: String
)

class NotesDb(context: Context) :
    android.database.sqlite.SQLiteOpenHelper(
        context,
        "jarvis_offline.db",
        null,
        1
    ) {

    override fun onCreate(
        db: android.database.sqlite.SQLiteDatabase
    ) {
        db.execSQL(
            "CREATE TABLE notes(id INTEGER PRIMARY KEY AUTOINCREMENT, body TEXT NOT NULL, created_at INTEGER NOT NULL)"
        )

        db.execSQL(
            "CREATE TABLE chat(id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT NOT NULL, body TEXT NOT NULL, created_at INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(
        db: android.database.sqlite.SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
    }

    fun addNote(body: String) {
        writableDatabase.execSQL(
            "INSERT INTO notes(body,created_at) VALUES(?,?)",
            arrayOf(body, System.currentTimeMillis())
        )
    }

    fun notes(): List<String> {
        val output = mutableListOf<String>()

        readableDatabase.rawQuery(
            "SELECT body FROM notes ORDER BY id DESC",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                output.add(cursor.getString(0))
            }
        }

        return output
    }

    fun addChat(role: String, body: String) {
        writableDatabase.execSQL(
            "INSERT INTO chat(role,body,created_at) VALUES(?,?,?)",
            arrayOf(role, body, System.currentTimeMillis())
        )
    }

    fun chat(): List<ChatLine> {
        val output = mutableListOf<ChatLine>()

        readableDatabase.rawQuery(
            "SELECT role,body FROM chat ORDER BY id DESC LIMIT 80",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                output.add(
                    ChatLine(
                        cursor.getString(0),
                        cursor.getString(1)
                    )
                )
            }
        }

        return output.reversed()
    }
}

class LocalModel(private val context: Context) {

    private var engine: LlmInference? = null

    fun load(file: File): Result<Unit> = runCatching {
        engine?.close()

        val options =
            LlmInference.LlmInferenceOptions.builder()
                .setModelPath(file.absolutePath)
                .setMaxTokens(512)
                .build()

        engine = LlmInference.createFromOptions(
            context,
            options
        )
    }

    fun answer(prompt: String): String? {
        return engine?.generateResponse(
            """
            You are JARVIS, a helpful offline Android assistant.
            Reply clearly and naturally.
            Support English and Malayalam.
            Do not claim to perform actions you cannot perform.

            User: $prompt
            JARVIS:
            """.trimIndent()
        )
    }

    fun close() {
        engine?.close()
        engine = null
    }
}

@Composable
fun JarvisOfflineApp(
    onSpeak: (String) -> Unit,
    onWakeChanged: (Boolean) -> Unit,
    onVoice: () -> Unit,
    wakeOnOpen: Boolean,
    initiallyWakeEnabled: Boolean
) {
    val context = LocalContext.current

    val db = remember {
        NotesDb(context)
    }

    val model = remember {
        LocalModel(context)
    }

    val scope = rememberCoroutineScope()

    var tab by remember {
        mutableStateOf("Home")
    }

    var lines by remember {
        mutableStateOf(db.chat())
    }

    var notes by remember {
        mutableStat
