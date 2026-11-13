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
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
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
    private var wakeReceiver: BroadcastReceiver? = null
    private var pendingWake = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale("en", "IN")
        }
        pendingWake = intent?.getBooleanExtra("wake_triggered", false) == true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                pendingWake = true
                if (hasWindowFocus()) startCommandRecognition()
            }
        }
        wakeReceiver = receiver
        ContextCompat.registerReceiver(this, receiver, IntentFilter(WakeWordService.ACTION_WAKE), ContextCompat.RECEIVER_NOT_EXPORTED)
        setContent { JarvisOfflineApp(onSpeak = { speak(it) }, onWakeChanged = { enabled -> setWakeWord(enabled) }, onVoice = { startCommandRecognition() }, wakeOnOpen = pendingWake) }
    }

    private fun speak(text: String) {
        val locale = if (text.any { it in '\u0D00'..'\u0D7F' }) Locale("ml", "IN") else Locale("en", "IN")
        val result = tts?.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) tts?.language = Locale.getDefault()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-response")
    }

    private fun setWakeWord(enabled: Boolean) {
        if (enabled) {
            val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) {
                requestPermissions(missing.toTypedArray(), 701)
                return
            }
            if (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                android.widget.Toast.makeText(this, "On-device speech recognition is not available. Install an offline speech language pack in Android settings.", android.widget.Toast.LENGTH_LONG).show()
                return
            }
            ContextCompat.startForegroundService(this, Intent(this, WakeWordService::class.java))
        } else stopService(Intent(this, WakeWordService::class.java))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 701 && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            setWakeWord(true)
        }
    }

    private fun startCommandRecognition() {
        pendingWake = false
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 702); return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            android.widget.Toast.makeText(this, "Speech recognition is unavailable", android.widget.Toast.LENGTH_SHORT).show(); return
        }
        val recognizer = if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) SpeechRecognizer.createOnDeviceSpeechRecognizer(this) else SpeechRecognizer.createSpeechRecognizer(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            // Ask compatible Android recognizers to detect either supported language locally.
            if (Build.VERSION.SDK_INT >= 34) {
                putExtra("android.speech.extra.ENABLE_LANGUAGE_DETECTION", true)
                putStringArrayListExtra("android.speech.extra.LANGUAGE_DETECTION_ALLOWED_LANGUAGES", arrayListOf("en-IN", "ml-IN"))
            }
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to JARVIS / JARVIS-നോട് സംസാരിക്കൂ")
        }
        recognizer.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { recognizer.destroy() }
            override fun onResults(results: Bundle?) {
                val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!spoken.isNullOrBlank()) sendBroadcast(Intent(ACTION_COMMAND).setPackage(packageName).putExtra("command", spoken))
                recognizer.destroy()
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        recognizer.startListening(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("wake_triggered", false)) startCommandRecognition()
    }

    override fun onDestroy() {
        wakeReceiver?.let { runCatching { unregisterReceiver(it) } }
        tts?.stop(); tts?.shutdown(); super.onDestroy()
    }

    companion object { const val ACTION_COMMAND = "com.jarvis.mobile.COMMAND" }
}

data class ChatLine(val role: String, val text: String)

class NotesDb(context: Context) : android.database.sqlite.SQLiteOpenHelper(context, "jarvis_offline.db", null, 1) {
    override fun onCreate(db: android.database.sqlite.SQLiteDatabase) {
        db.execSQL("CREATE TABLE notes(id INTEGER PRIMARY KEY AUTOINCREMENT, body TEXT NOT NULL, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE chat(id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT NOT NULL, body TEXT NOT NULL, created_at INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: android.database.sqlite.SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    fun addNote(body: String) { writableDatabase.execSQL("INSERT INTO notes(body,created_at) VALUES(?,?)", arrayOf(body, System.currentTimeMillis())) }
    fun notes(): List<String> { val out = mutableListOf<String>(); readableDatabase.rawQuery("SELECT body FROM notes ORDER BY id DESC", null).use { c -> while (c.moveToNext()) out.add(c.getString(0)) }; return out }
    fun addChat(role: String, body: String) { writableDatabase.execSQL("INSERT INTO chat(role,body,created_at) VALUES(?,?,?)", arrayOf(role, body, System.currentTimeMillis())) }
    fun chat(): List<ChatLine> { val out = mutableListOf<ChatLine>(); readableDatabase.rawQuery("SELECT role,body FROM chat ORDER BY id DESC LIMIT 80", null).use { c -> while (c.moveToNext()) out.add(ChatLine(c.getString(0), c.getString(1))) }; return out.reversed() }
}

class LocalModel(private val context: Context) {
    private var engine: LlmInference? = null
    fun load(file: File): Result<Unit> = runCatching {
        engine?.close();
        val options = LlmInference.LlmInferenceOptions.builder().setModelPath(file.absolutePath).setMaxTokens(512).build()
        engine = LlmInference.createFromOptions(context, options)
    }
    fun answer(prompt: String): String? = engine?.generateResponse("You are JARVIS, a helpful offline Android assistant. Reply clearly. Support English and Malayalam. Do not claim to perform actions you cannot perform.\nUser: $prompt\nJARVIS:")
    fun close() { engine?.close(); engine = null }
}

@Composable
fun JarvisOfflineApp(onSpeak: (String) -> Unit, onWakeChanged: (Boolean) -> Unit, onVoice: () -> Unit, wakeOnOpen: Boolean) {
    val context = LocalContext.current
    val db = remember { NotesDb(context) }
    val model = remember { LocalModel(context) }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf("Home") }
    var lines by remember { mutableStateOf(db.chat()) }
    var notes by remember { mutableStateOf(db.notes()) }
    var input by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Offline command engine ready") }
    var wakeEnabled by remember { mutableStateOf(false) }
    var modelLoaded by remember { mutableStateOf(false) }
    var language by remember { mutableStateOf("English + Malayalam") }
    val modelFile = remember { File(context.filesDir, "jarvis-model.task") }
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            status = "Importing local model…"
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)!!.use { input -> modelFile.outputStream().use { output -> input.copyTo(output) } }
                    model.load(modelFile).getOrThrow()
                }
            }
            modelLoaded = result.isSuccess
            status = if (result.isSuccess) "On-device AI model loaded" else "Model could not be loaded: ${result.exceptionOrNull()?.message ?: "unsupported model"}"
        }
    }

    fun addLine(role: String, text: String) { lines = lines + ChatLine(role, text); db.addChat(role, text) }
    fun handleCommand(raw: String) {
        val text = raw.trim(); if (text.isEmpty() || busy) return
        input = ""; addLine("user", text)
        val lower = text.lowercase(Locale.ROOT)
        val reply = when {
            lower == "help" || lower.contains("what can you do") || lower.contains("സഹായം") -> "I can open supported apps, tell the time and date, save notes, search your saved notes, and answer with an imported local AI model. മലയാളത്തിലും ഇംഗ്ലീഷിലും സംസാരിക്കാം."
            lower.startsWith("note ") || lower.startsWith("remember ") || lower.startsWith("കുറിപ്പ് ") -> {
                val body = text.substringAfter(' ').trim(); if (body.isNotBlank()) { db.addNote(body); notes = db.notes(); "Saved on this phone. നിങ്ങളുടെ കുറിപ്പ് ഫോണിൽ സൂക്ഷിച്ചു." } else "What should I save? എന്താണ് സൂക്ഷിക്കേണ്ടത്?"
            }
            lower.contains("what time") || lower == "time" || lower.contains("സമയം") -> "The time is ${SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())}. ഇപ്പോഴത്തെ സമയം ${SimpleDateFormat("h:mm", Locale("en", "IN")).format(Date())}."
            lower.contains("what date") || lower == "date" || lower.contains("ഇന്നത്തെ തീയതി") -> "Today is ${SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(Date())}."
            lower.contains("open settings") || lower.contains("ക്രമീകരണങ്ങൾ തുറക്കൂ") -> { context.startActivity(Intent(Settings.ACTION_SETTINGS)); "Opening Android Settings." }
            lower.contains("open youtube") || lower.contains("യൂട്യൂബ് തുറക്കൂ") -> { openApp(context, "com.google.android.youtube"); "Opening YouTube if installed." }
            lower.contains("open chrome") || lower.contains("open browser") -> {
    val launch = context.packageManager.getLaunchIntentForPackage("com.android.chrome")
    if (launch != null) {
        context.startActivity(launch)
        "Opening Chrome."
    } else {
        "Chrome is not installed."
    }
            }
            lower.contains("open maps") -> { openApp(context, "com.google.android.apps.maps"); "Opening Maps if installed." }
            lower.contains("search notes") || lower.contains("find note") || lower.contains("കുറിപ്പ് തിരയുക") -> { val term = text.substringAfterLast(' ').lowercase(); val found = notes.filter { it.lowercase().contains(term) }; if (found.isEmpty()) "No matching saved notes." else found.take(5).joinToString("\n") }
            else -> null
        }
        if (reply != null) { addLine("assistant", reply); onSpeak(reply); return }
        if (modelLoaded) {
            busy = true
            scope.launch {
                val result = withContext(Dispatchers.Default) { runCatching { model.answer(text) ?: "Load a compatible local model in Settings." } }
                busy = false
                val answer = result.getOrElse { "Local model error: ${it.message ?: "unknown error"}" }
                addLine("assistant", answer); onSpeak(answer)
            }
        } else {
            val answer = "My offline command engine is ready, but open Settings and import a compatible on-device .task model to enable generative AI. I can still handle time, date, notes and supported app shortcuts."
            addLine("assistant", answer); onSpeak(answer)
        }
    }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() { override fun onReceive(c: Context?, i: Intent?) { i?.getStringExtra("command")?.let { handleCommand(it) } } }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(MainActivity.ACTION_COMMAND), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) }; model.close() }
    }
    LaunchedEffect(wakeOnOpen) { if (wakeOnOpen) onVoice() }

    MaterialTheme(colorScheme = darkColorScheme(background = Navy, surface = Panel, primary = Cyan, onBackground = White, onSurface = White)) {
        Scaffold(containerColor = Navy, bottomBar = {
            NavigationBar(containerColor = Panel) {
                listOf("Home" to Icons.Default.Home, "Chat" to Icons.Default.Forum, "Notes" to Icons.Default.StickyNote2, "Settings" to Icons.Default.Settings).forEach { (name, icon) ->
                    NavigationBarItem(selected = tab == name, onClick = { tab = name }, icon = { Icon(icon, null) }, label = { Text(name) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Cyan, selectedTextColor = Cyan, indicatorColor = Color(0xFF163047), unselectedIconColor = Muted, unselectedTextColor = Muted))
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().background(Navy).padding(padding).padding(horizontal = 18.dp)) {
                Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column { Text("J.A.R.V.I.S", color = Cyan, fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp); Text("OFFLINE PERSONAL ASSISTANT", color = Muted, fontSize = 9.sp, letterSpacing = 2.sp) }
                    Surface(color = Color(0xFF10283A), shape = RoundedCornerShape(30.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Cyan.copy(alpha = .35f))) { Text("NO API KEYS", Modifier.padding(horizontal = 10.dp, vertical = 7.dp), color = White, fontSize = 10.sp) }
                }
                when (tab) {
                    "Home" -> HomeScreen(status, wakeEnabled, modelLoaded, onVoice, onNavigate = { tab = it }, onWake = { enabled -> wakeEnabled = enabled; onWakeChanged(enabled); status = if (enabled) "Wake-word service requested" else "Wake-word listening stopped" }, onQuick = { handleCommand(it) })
                    "Chat" -> ChatScreen(lines, input, { input = it }, busy, onSend = { handleCommand(input) }, onVoice = onVoice)
                    "Notes" -> NotesScreen(notes, draft, { draft = it }, { if (draft.isNotBlank()) { db.addNote(draft.trim()); notes = db.notes(); draft = "" } })
                    else -> SettingsScreen(language, { language = it }, modelLoaded, status, onImport = { modelPicker.launch(arrayOf("application/octet-stream", "application/x-task", "*/*")) }, onHelp = { context.startActivity(Intent(Settings.ACTION_SETTINGS)) })
                }
            }
        }
    }
}

private fun openApp(context: Context, pkg: String) { val i = context.packageManager.getLaunchIntentForPackage(pkg); if (i != null) context.startActivity(i) else android.widget.Toast.makeText(context, "App is not installed", android.widget.Toast.LENGTH_SHORT).show() }

@Composable private fun HomeScreen(status: String, wake: Boolean, model: Boolean, onVoice: () -> Unit, onNavigate: (String) -> Unit, onWake: (Boolean) -> Unit, onQuick: (String) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        item { Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) { Box(Modifier.size(196.dp).shadow(24.dp, CircleShape).background(Brush.radialGradient(listOf(Color(0xFF174E70), Color(0xFF0C1C31), Navy)), CircleShape).border(1.dp, Cyan.copy(alpha = .7f), CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Default.GraphicEq, null, tint = Cyan, modifier = Modifier.size(64.dp)) } }; Text("At your service.", Modifier.fillMaxWidth().padding(top = 12.dp), color = White, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center); Text("English • മലയാളം • Offline", Modifier.fillMaxWidth(), color = Muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        item { Card(colors = CardDefaults.cardColors(containerColor = Panel)) { Column(Modifier.padding(14.dp)) { Text(status, color = White); Text(if (model) "Local AI model loaded" else "Local command mode active", color = Muted, fontSize = 12.sp) } } }
        item { Button(onClick = onVoice, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Mic, null); Spacer(Modifier.width(8.dp)); Text("TALK TO JARVIS") } }
        item { Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Hey JARVIS wake word", color = White, fontWeight = FontWeight.SemiBold); Text("Foreground microphone notification required", color = Muted, fontSize = 11.sp) }; Switch(checked = wake, onCheckedChange = onWake) } }
        item { Text("QUICK COMMANDS", color = Muted, fontSize = 11.sp, letterSpacing = 2.sp) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { QuickCard("What time is it?", Icons.Default.AccessTime, Modifier.weight(1f)) { onQuick("what time is it") }; QuickCard("Open settings", Icons.Default.Settings, Modifier.weight(1f)) { onQuick("open settings") } } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { QuickCard("Chat", Icons.Default.Forum, Modifier.weight(1f)) { onNavigate("Chat") }; QuickCard("Notes", Icons.Default.StickyNote2, Modifier.weight(1f)) { onNavigate("Notes") } } }
        item { CapabilityRow("Offline commands", "Available", true) }
        item { CapabilityRow("Local generative AI", if (model) "Model loaded" else "Import a compatible model", model) }
        item { CapabilityRow("Wake-word listener", if (wake) "Requested; check notification" else "Off", wake) }
    }
}

@Composable private fun QuickCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) { Card(onClick = onClick, modifier = modifier, colors = CardDefaults.cardColors(containerColor = Panel)) { Column(Modifier.padding(14.dp)) { Icon(icon, null, tint = Cyan); Spacer(Modifier.height(8.dp)); Text(title, color = White, fontWeight = FontWeight.SemiBold) } } }
@Composable private fun CapabilityRow(title: String, value: String, ready: Boolean) { Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(title, color = White); Text(value, color = if (ready) Color(0xFF55E6A5) else Muted, fontSize = 12.sp) } }

@Composable private fun ChatScreen(lines: List<ChatLine>, input: String, onInput: (String) -> Unit, busy: Boolean, onSend: () -> Unit, onVoice: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(lines) { line -> Row(Modifier.fillMaxWidth(), horizontalArrangement = if (line.role == "user") Arrangement.End else Arrangement.Start) { Surface(color = if (line.role == "user") Color(0xFF123A52) else Panel, shape = RoundedCornerShape(16.dp)) { Text(line.text, Modifier.widthIn(max = 300.dp).padding(12.dp), color = White) } } }
            if (busy) item { Text("JARVIS is thinking locally…", color = Muted) }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = input, onValueChange = onInput, modifier = Modifier.weight(1f), placeholder = { Text("Message JARVIS…") }, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences), maxLines = 3)
            IconButton(onClick = onVoice) { Icon(Icons.Default.Mic, "Voice", tint = Cyan) }
            IconButton(onClick = onSend, enabled = input.isNotBlank() && !busy) { Icon(Icons.Default.Send, "Send", tint = Cyan) }
        }
    }
}

@Composable private fun NotesScreen(notes: List<String>, draft: String, onDraft: (String) -> Unit, onSave: () -> Unit) {
    Column(Modifier.fillMaxSize()) { Text("YOUR PRIVATE OFFLINE NOTES", color = Muted, fontSize = 11.sp, letterSpacing = 1.5.sp); OutlinedTextField(value = draft, onValueChange = onDraft, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), placeholder = { Text("Write a note…") }); Button(onClick = onSave, enabled = draft.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Save on this phone") }; LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) { items(notes) { note -> Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) { Text(note, Modifier.padding(14.dp), color = White) } } } }
}

@Composable private fun SettingsScreen(language: String, onLanguage: (String) -> Unit, modelLoaded: Boolean, status: String, onImport: () -> Unit, onHelp: () -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        item { Text("OFFLINE CONFIGURATION", color = Muted, fontSize = 11.sp, letterSpacing = 2.sp) }
        item { Text("Language", color = White, fontWeight = FontWeight.SemiBold); listOf("English + Malayalam", "English", "Malayalam").forEach { item -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = language == item, onClick = { onLanguage(item) }); Text(item, color = White) } } }
        item { Divider(color = Muted.copy(alpha = .3f)) }
        item { Text("Local AI model", color = White, fontWeight = FontWeight.SemiBold); Text("Import a compatible MediaPipe .task model stored on your phone. Model files can be large and are not included in the APK.", color = Muted, fontSize = 12.sp); Button(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text(if (modelLoaded) "Replace local model" else "Import model from phone") } }
        item { Text(status, color = Muted, fontSize = 12.sp) }
        item { Text("Wake word", color = White, fontWeight = FontWeight.SemiBold); Text("Wake-word listening uses Android's on-device speech recognizer when available. Keep the persistent notification visible. If your phone lacks an offline English speech pack, wake detection will not start.", color = Muted, fontSize = 12.sp) }
        item { OutlinedButton(onClick = onHelp, modifier = Modifier.fillMaxWidth()) { Text("Android language & speech settings") } }
        item { Text("Privacy: no API keys, no account, no app backend, and no network permission. Notes and chat history remain in this app's private storage.", color = Muted, fontSize = 12.sp) }
    }
}
