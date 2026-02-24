package com.openclaw.chat.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import com.openclaw.chat.R
import com.openclaw.chat.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    
    companion object {
        private const val REQUEST_RECORD_AUDIO = 100
        private const val SAMPLE_RATE = 16000
    }
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var chatAdapter: ChatAdapter
    private val messages = mutableListOf<ChatMessage>()
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    // WebSocket
    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var pendingResponse = StringBuilder()
    private var currentTypingIndex = -1
    private var currentRunId = ""
    
    // Settings loaded from preferences
    private var sarvamApiKey = ""
    private var sarvamVoice = "priya"
    private var sarvamLanguage = "hi-IN"
    private var sarvamPace = 1.3f
    
    // Audio Recording for Sarvam STT
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingJob: Job? = null
    private val audioBuffer = mutableListOf<ByteArray>()
    
    // Sarvam TTS with streaming prefetch
    private var autoSpeak = true
    private val ttsTextQueue = mutableListOf<String>()
    private val ttsAudioQueue = ConcurrentLinkedQueue<File>()
    private var ttsFetchJob: Job? = null
    private var ttsPlayJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null
    private var isTtsSpeaking = false
    private var lastSpokenText = ""
    private var detectedLanguage = "hi-IN"
    
    // Input source tracking
    private var lastInputSource = "Chat"
    
    // HTTP Client for Sarvam API
    private val sarvamClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    // WebSocket Client
    private val wsClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
    
    // Conversation history
    private val conversationHistory = mutableListOf<Map<String, String>>()
    private val MAX_HISTORY = 20
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        loadSarvamSettings()
        setupUI()
        checkPermissions()
        connectWebSocket()
    }
    
    private fun loadSarvamSettings() {
        val prefs = getSharedPreferences("openclaw_config", MODE_PRIVATE)
        sarvamApiKey = prefs.getString("sarvam_api_key", "") ?: ""
        sarvamVoice = prefs.getString("sarvam_voice", "priya") ?: "priya"
        sarvamLanguage = prefs.getString("sarvam_language", "hi-IN") ?: "hi-IN"
        sarvamPace = prefs.getFloat("sarvam_pace", 1.3f)
        
        android.util.Log.d("OpenClawChat", "Loaded Sarvam settings: voice=$sarvamVoice, language=$sarvamLanguage, pace=$sarvamPace, apiKey=${if (sarvamApiKey.isNotEmpty()) "set" else "not set"}")
    }
    
    override fun onResume() {
        super.onResume()
        loadSarvamSettings()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        webSocket?.close(1000, "App closed")
        stopRecording()
        stopSpeaking()
    }
    
    private fun setupUI() {
        // Handle window insets for status bar and navigation bar
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            
            // Add padding to header for status bar
            binding.header.updatePadding(top = insets.top)
            
            // Add padding to input container for navigation bar
            binding.inputContainer.updatePadding(bottom = insets.bottom + 8)
            
            WindowInsetsCompat.CONSUMED
        }
        
        chatAdapter = ChatAdapter(messages)
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply {
                stackFromEnd = true
            }
            adapter = chatAdapter
        }
        
        binding.btnSend.setOnClickListener {
            lastInputSource = "Chat"
            sendMessage()
        }
        
        // Hold-to-record: press and hold while speaking, release to send
        binding.btnVoice.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startRecording()
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    stopRecording()
                    true
                }
                else -> false
            }
        }
        
        binding.etMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                lastInputSource = "Chat"
                sendMessage()
                true
            } else false
        }
        
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        
        binding.btnToggleTts.setOnClickListener {
            autoSpeak = !autoSpeak
            updateTtsButton()
            toast(if (autoSpeak) "Voice output ON" else "Voice output OFF")
        }
        
        binding.btnStopSpeaking.setOnClickListener {
            stopSpeaking()
        }
        
        binding.btnClear.setOnClickListener {
            messages.clear()
            conversationHistory.clear()
            chatAdapter.notifyDataSetChanged()
            toast("Chat cleared")
        }
        
        updateTtsButton()
        updateConnectionStatus(false)
    }
    
    private fun connectWebSocket() {
        val prefs = getSharedPreferences("openclaw_config", MODE_PRIVATE)
        val wsUrl = prefs.getString("openclaw_url", "ws://76.13.247.120:59269") ?: "ws://76.13.247.120:59269"
        val authToken = prefs.getString("openclaw_token", "0XwYfZWuoWUXJRVlo4S1X1dVHDzhHFHS") ?: ""
        
        val normalizedUrl = wsUrl
            .replace("http://", "ws://")
            .replace("https://", "wss://")
            .replace("/v1/chat/completions", "")
            .trimEnd('/')
        
        android.util.Log.d("OpenClawChat", "Connecting to WebSocket: $normalizedUrl")
        
        // Add Origin header for openclaw-control-ui client
        val request = Request.Builder()
            .url(normalizedUrl)
            .addHeader("Origin", "http://76.13.247.120:59269")
            .build()
        
        webSocket = wsClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                android.util.Log.d("OpenClawChat", "WebSocket opened")
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                android.util.Log.d("OpenClawChat", "WS message: ${text.take(200)}")
                handleWebSocketMessage(text, authToken)
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                android.util.Log.e("OpenClawChat", "WebSocket error: ${t.message}")
                runOnUiThread {
                    isConnected = false
                    updateConnectionStatus(false)
                    toast("Connection failed: ${t.message}")
                }
                
                scope.launch {
                    delay(5000)
                    if (!isConnected) {
                        connectWebSocket()
                    }
                }
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                android.util.Log.d("OpenClawChat", "WebSocket closed: $reason")
                runOnUiThread {
                    isConnected = false
                    updateConnectionStatus(false)
                }
            }
        })
    }
    
    private fun handleWebSocketMessage(text: String, authToken: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type", "")
            val event = json.optString("event", "")
            
            when {
                event == "connect.challenge" -> {
                    // Send connect request with correct client ID and mode
                    val connectMsg = JSONObject().apply {
                        put("type", "req")
                        put("id", "connect-${System.currentTimeMillis()}")
                        put("method", "connect")
                        put("params", JSONObject().apply {
                            put("minProtocol", 3)
                            put("maxProtocol", 3)
                            put("client", JSONObject().apply {
                                put("id", "openclaw-control-ui")
                                put("displayName", "OpenClaw Android")
                                put("version", "1.0.0")
                                put("platform", "android")
                                put("mode", "webchat")
                            })
                            put("role", "operator")
                            put("scopes", JSONArray().apply {
                                put("operator.read")
                                put("operator.write")
                            })
                            put("auth", JSONObject().apply {
                                put("token", authToken)
                            })
                        })
                    }
                    webSocket?.send(connectMsg.toString())
                }
                
                type == "res" -> {
                    val ok = json.optBoolean("ok", false)
                    val payload = json.optJSONObject("payload")
                    
                    if (ok && payload?.optString("type") == "hello-ok") {
                        runOnUiThread {
                            isConnected = true
                            updateConnectionStatus(true)
                            toast("Connected to OpenClaw")
                        }
                    } else if (ok && payload?.optString("status") == "started") {
                        // Chat started
                        currentRunId = payload.optString("runId", "")
                    } else if (!ok) {
                        val error = json.optJSONObject("error")
                        val errorMsg = error?.optString("message", "Unknown error") ?: "Unknown error"
                        android.util.Log.e("OpenClawChat", "Error response: $errorMsg")
                        
                        runOnUiThread {
                            if (currentTypingIndex >= 0 && currentTypingIndex < messages.size) {
                                messages[currentTypingIndex] = ChatMessage(
                                    "Error: $errorMsg",
                                    false,
                                    System.currentTimeMillis(),
                                    isError = true
                                )
                                chatAdapter.notifyItemChanged(currentTypingIndex)
                            }
                            toast("Error: $errorMsg")
                        }
                    }
                }
                
                event == "chat" -> {
                    val payload = json.optJSONObject("payload") ?: return
                    val state = payload.optString("state", "")
                    val message = payload.optJSONObject("message") ?: return
                    val content = message.optJSONArray("content")
                    
                    // Extract text from content array
                    var responseText = ""
                    if (content != null) {
                        for (i in 0 until content.length()) {
                            val item = content.optJSONObject(i)
                            if (item?.optString("type") == "text") {
                                responseText = item.optString("text", "")
                            }
                        }
                    }
                    
                    runOnUiThread {
                        if (responseText.isNotEmpty() && currentTypingIndex >= 0 && currentTypingIndex < messages.size) {
                            messages[currentTypingIndex] = ChatMessage(
                                responseText,
                                false,
                                System.currentTimeMillis(),
                                isTyping = state != "final"
                            )
                            chatAdapter.notifyItemChanged(currentTypingIndex)
                            scrollToBottom()
                            
                            // STREAMING TTS: Start speaking as soon as we have complete sentences
                            // Don't wait for final state - queue sentences as they arrive
                            if (autoSpeak) {
                                queueNewSentencesForTts(responseText)
                            }
                        }
                        
                        if (state == "final") {
                            conversationHistory.add(mapOf("role" to "assistant", "content" to responseText))
                            trimHistory()
                            currentTypingIndex = -1
                            currentRunId = ""
                        }
                    }
                }
                
                event == "agent" -> {
                    // Handle agent events (tool calls, etc.)
                    val payload = json.optJSONObject("payload") ?: return
                    val agentState = payload.optString("state", "")
                    
                    if (agentState == "thinking") {
                        runOnUiThread {
                            if (currentTypingIndex >= 0 && currentTypingIndex < messages.size) {
                                messages[currentTypingIndex] = ChatMessage(
                                    "Thinking...",
                                    false,
                                    System.currentTimeMillis(),
                                    isTyping = true
                                )
                                chatAdapter.notifyItemChanged(currentTypingIndex)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("OpenClawChat", "Parse error: ${e.message}")
        }
    }
    
    private fun updateConnectionStatus(connected: Boolean) {
        binding.btnSend.isEnabled = connected
        binding.btnVoice.isEnabled = connected && ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
            != PackageManager.PERMISSION_GRANTED) {
            toast("Microphone permission required")
            return
        }
        
        stopSpeaking()
        
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 2
            )
            
            audioBuffer.clear()
            audioRecord?.startRecording()
            isRecording = true
            
            binding.btnVoice.setImageResource(R.drawable.ic_mic_active)
            binding.tvListening.visibility = View.VISIBLE
            binding.tvListening.text = "🎤 Listening... (release to send)"
            
            recordingJob = scope.launch(Dispatchers.IO) {
                val buffer = ByteArray(bufferSize)
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        audioBuffer.add(buffer.copyOf(read))
                    }
                }
            }
        } catch (e: SecurityException) {
            toast("Microphone permission denied")
        }
    }
    
    private fun stopRecording() {
        if (!isRecording) return
        
        isRecording = false
        
        // Wait a moment for the last audio chunk to be captured
        scope.launch {
            // Give the recording job time to capture the last chunk
            delay(100)
            
            recordingJob?.cancel()
            recordingJob = null
            
            withContext(Dispatchers.Main) {
                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null
                
                binding.btnVoice.setImageResource(R.drawable.ic_mic)
                binding.tvListening.text = "Processing..."
            }
            
            // Check if we have enough audio data (at least 0.5 second worth)
            val totalBytes = audioBuffer.sumOf { it.size }
            val minBytesRequired = SAMPLE_RATE * 2 / 2  // 0.5 seconds of audio (16-bit mono)
            
            android.util.Log.d("OpenClawChat", "Audio buffer size: $totalBytes bytes, min required: $minBytesRequired")
            
            if (totalBytes >= minBytesRequired) {
                try {
                    val transcript = sendToSarvamSTT()
                    
                    withContext(Dispatchers.Main) {
                        binding.tvListening.visibility = View.GONE
                        
                        if (transcript.isNotEmpty()) {
                            binding.etMessage.setText(transcript)
                            lastInputSource = "Voice"
                            sendMessage()
                        } else {
                            toast("Could not recognize speech")
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("OpenClawChat", "STT error: ${e.message}", e)
                    withContext(Dispatchers.Main) {
                        binding.tvListening.visibility = View.GONE
                        toast("Speech recognition error: ${e.message}")
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    binding.tvListening.visibility = View.GONE
                    toast("Please hold longer while speaking")
                }
            }
        }
    }
    
    private suspend fun sendToSarvamSTT(): String {
        return withContext(Dispatchers.IO) {
            val audioData = audioBuffer.flatMap { it.toList() }.toByteArray()
            val wavFile = createWavFile(audioData)
            
            try {
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("model", "saaras:v3")
                    .addFormDataPart("with_timestamps", "false")
                    .addFormDataPart(
                        "file", "audio.wav",
                        wavFile.readBytes().toRequestBody("audio/wav".toMediaType())
                    )
                    .build()
                
                if (sarvamApiKey.isEmpty()) {
                    throw Exception("Sarvam API key not set. Go to Settings to add your API key.")
                }
                
                val request = Request.Builder()
                    .url("https://api.sarvam.ai/speech-to-text")
                    .addHeader("api-subscription-key", sarvamApiKey)
                    .post(requestBody)
                    .build()
                
                val response = sarvamClient.newCall(request).execute()
                
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    android.util.Log.d("OpenClawChat", "STT response: $responseBody")
                    val json = JSONObject(responseBody ?: "{}")
                    detectedLanguage = json.optString("language_code", "hi-IN")
                    val transcript = json.optString("transcript", "")
                    android.util.Log.d("OpenClawChat", "Transcript: '$transcript', Language: $detectedLanguage")
                    transcript
                } else {
                    val errorBody = response.body?.string()
                    android.util.Log.e("OpenClawChat", "STT error: ${response.code}, body: $errorBody")
                    ""
                }
            } finally {
                wavFile.delete()
            }
        }
    }
    
    private fun createWavFile(audioData: ByteArray): File {
        val wavFile = File(cacheDir, "recording_${System.currentTimeMillis()}.wav")
        FileOutputStream(wavFile).use { out ->
            val totalDataLen = audioData.size + 36
            val byteRate = SAMPLE_RATE * 2
            
            out.write("RIFF".toByteArray())
            out.write(intToByteArray(totalDataLen))
            out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray())
            out.write(intToByteArray(16))
            out.write(shortToByteArray(1))
            out.write(shortToByteArray(1))
            out.write(intToByteArray(SAMPLE_RATE))
            out.write(intToByteArray(byteRate))
            out.write(shortToByteArray(2))
            out.write(shortToByteArray(16))
            out.write("data".toByteArray())
            out.write(intToByteArray(audioData.size))
            out.write(audioData)
        }
        return wavFile
    }
    
    private fun intToByteArray(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
    }
    
    private fun shortToByteArray(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte()
        )
    }
    
    private fun sendMessage() {
        val text = binding.etMessage.text.toString().trim()
        if (text.isEmpty()) return
        if (!isConnected) {
            toast("Not connected to OpenClaw")
            return
        }
        
        binding.etMessage.setText("")
        
        val prefixedText = "[Android:$lastInputSource] $text"
        
        val userMessage = ChatMessage(text, true, System.currentTimeMillis())
        messages.add(userMessage)
        chatAdapter.notifyItemInserted(messages.size - 1)
        scrollToBottom()
        
        conversationHistory.add(mapOf("role" to "user", "content" to prefixedText))
        trimHistory()
        
        val typingMessage = ChatMessage("...", false, System.currentTimeMillis(), isTyping = true)
        messages.add(typingMessage)
        currentTypingIndex = messages.size - 1
        chatAdapter.notifyItemInserted(currentTypingIndex)
        scrollToBottom()
        
        pendingResponse.clear()
        
        // Clear TTS tracking for new response
        queuedSentences.clear()
        
        // Send via WebSocket with correct format
        val chatMsg = JSONObject().apply {
            put("type", "req")
            put("id", "chat-${System.currentTimeMillis()}")
            put("method", "chat.send")
            put("params", JSONObject().apply {
                put("sessionKey", "agent:main:main")
                put("message", prefixedText)
                put("idempotencyKey", UUID.randomUUID().toString())
            })
        }
        webSocket?.send(chatMsg.toString())
    }
    
    // Track sentences already queued for TTS to avoid duplicates during streaming
    private val queuedSentences = mutableSetOf<String>()
    
    private fun queueNewSentencesForTts(fullText: String) {
        // Split into sentences - only queue COMPLETE sentences (ending with punctuation)
        val sentences = fullText.split(Regex("(?<=[.!?।])\\s*"))
        
        for (i in sentences.indices) {
            val sentence = sentences[i].trim()
            
            // Skip empty sentences
            if (sentence.isEmpty()) continue
            
            // Only process complete sentences (ones that end with punctuation)
            // Skip the last part if it doesn't end with punctuation (still being typed)
            val isComplete = sentence.endsWith(".") || sentence.endsWith("!") || 
                           sentence.endsWith("?") || sentence.endsWith("।") ||
                           i < sentences.size - 1  // Not the last segment
            
            if (isComplete && !queuedSentences.contains(sentence)) {
                queuedSentences.add(sentence)
                synchronized(ttsTextQueue) {
                    ttsTextQueue.add(sentence)
                }
                android.util.Log.d("OpenClawChat", "TTS queued: ${sentence.take(50)}...")
            }
        }
        
        // Start fetcher and player if not running
        if (ttsFetchJob == null || ttsFetchJob?.isActive != true) {
            startTtsFetching()
        }
        
        if (ttsPlayJob == null || ttsPlayJob?.isActive != true) {
            startTtsPlayback()
        }
    }
    
    private fun speakStreamingChunk(fullText: String) {
        // Clear tracking for new response
        queuedSentences.clear()
        queueNewSentencesForTts(fullText)
    }
    
    private fun startTtsFetching() {
        ttsFetchJob = scope.launch(Dispatchers.IO) {
            while (coroutineContext.isActive) {
                val sentence = synchronized(ttsTextQueue) {
                    if (ttsTextQueue.isNotEmpty()) ttsTextQueue.removeAt(0) else null
                }
                
                if (sentence != null && sentence != lastSpokenText) {
                    lastSpokenText = sentence
                    val audioFile = fetchTtsAudio(sentence)
                    if (audioFile != null) {
                        ttsAudioQueue.add(audioFile)
                    }
                } else if (sentence == null) {
                    delay(100)
                }
            }
        }
    }
    
    private fun startTtsPlayback() {
        ttsPlayJob = scope.launch(Dispatchers.Main) {
            while (coroutineContext.isActive) {
                val audioFile = ttsAudioQueue.poll()
                
                if (audioFile != null) {
                    isTtsSpeaking = true
                    binding.btnStopSpeaking.visibility = View.VISIBLE
                    
                    playAudioFile(audioFile)
                    
                    audioFile.delete()
                } else {
                    if (ttsTextQueue.isEmpty() && ttsAudioQueue.isEmpty()) {
                        isTtsSpeaking = false
                        binding.btnStopSpeaking.visibility = View.GONE
                    }
                    delay(100)
                }
            }
        }
    }
    
    private suspend fun fetchTtsAudio(text: String): File? {
        return withContext(Dispatchers.IO) {
            try {
                val cleanText = text
                    .replace(Regex("```[\\s\\S]*?```"), " code block ")
                    .replace(Regex("`[^`]+`"), "")
                    .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
                    .replace(Regex("\\*([^*]+)\\*"), "$1")
                    .replace(Regex("#+\\s*"), "")
                    .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
                    .replace("[CAPTURE_IMAGE]", "")
                    .trim()
                
                if (cleanText.isEmpty() || cleanText.length < 2) return@withContext null
                
                if (sarvamApiKey.isEmpty()) {
                    android.util.Log.w("OpenClawChat", "Sarvam API key not set, skipping TTS")
                    return@withContext null
                }
                
                // Use detected language for TTS, or fall back to settings
                val ttsLanguage = detectedLanguage.ifEmpty { sarvamLanguage }
                
                // CORRECT FORMAT for Bulbul v3: inputs is an ARRAY, NO pitch/loudness
                val jsonBody = JSONObject().apply {
                    put("inputs", JSONArray().apply { put(cleanText.take(500)) })
                    put("model", "bulbul:v3")
                    put("speaker", sarvamVoice)
                    put("target_language_code", ttsLanguage)
                    put("pace", sarvamPace)
                    put("enable_preprocessing", true)
                }
                
                val request = Request.Builder()
                    .url("https://api.sarvam.ai/text-to-speech")
                    .addHeader("api-subscription-key", sarvamApiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                
                val response = sarvamClient.newCall(request).execute()
                val responseBody = response.body?.string()
                
                android.util.Log.d("OpenClawChat", "TTS response code: ${response.code}")
                
                if (response.isSuccessful && responseBody != null) {
                    val json = JSONObject(responseBody)
                    val audiosArray = json.optJSONArray("audios")
                    
                    if (audiosArray != null && audiosArray.length() > 0) {
                        val audioBase64 = audiosArray.getString(0)
                        val audioBytes = Base64.decode(audioBase64, Base64.DEFAULT)
                        val audioFile = File(cacheDir, "tts_${System.currentTimeMillis()}.wav")
                        audioFile.writeBytes(audioBytes)
                        return@withContext audioFile
                    } else {
                        android.util.Log.e("OpenClawChat", "No audios in TTS response")
                    }
                } else {
                    android.util.Log.e("OpenClawChat", "TTS API error: ${response.code} - ${responseBody?.take(200)}")
                }
                null
            } catch (e: Exception) {
                android.util.Log.e("OpenClawChat", "TTS error: ${e.message}")
                null
            }
        }
    }
    
    private suspend fun playAudioFile(file: File) {
        return suspendCancellableCoroutine { continuation ->
            try {
                mediaPlayer?.release()
                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .build()
                    )
                    setDataSource(file.absolutePath)
                    prepare()
                    
                    setOnCompletionListener {
                        continuation.resumeWith(Result.success(Unit))
                    }
                    
                    setOnErrorListener { _, _, _ ->
                        continuation.resumeWith(Result.success(Unit))
                        true
                    }
                    
                    start()
                }
                
                continuation.invokeOnCancellation {
                    mediaPlayer?.release()
                    mediaPlayer = null
                }
            } catch (e: Exception) {
                continuation.resumeWith(Result.success(Unit))
            }
        }
    }
    
    private fun stopSpeaking() {
        ttsFetchJob?.cancel()
        ttsPlayJob?.cancel()
        ttsFetchJob = null
        ttsPlayJob = null
        
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        
        synchronized(ttsTextQueue) { ttsTextQueue.clear() }
        ttsAudioQueue.clear()
        queuedSentences.clear()
        lastSpokenText = ""
        
        isTtsSpeaking = false
        binding.btnStopSpeaking.visibility = View.GONE
    }
    
    private fun updateTtsButton() {
        binding.btnToggleTts.setImageResource(
            if (autoSpeak) R.drawable.ic_volume_on else R.drawable.ic_volume_off
        )
    }
    
    private fun scrollToBottom() {
        binding.recyclerView.post {
            if (messages.isNotEmpty()) {
                binding.recyclerView.smoothScrollToPosition(messages.size - 1)
            }
        }
    }
    
    private fun trimHistory() {
        while (conversationHistory.size > MAX_HISTORY) {
            conversationHistory.removeAt(0)
        }
    }
    
    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO
            )
        }
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                binding.btnVoice.isEnabled = false
                toast("Voice input requires microphone permission")
            } else {
                updateConnectionStatus(isConnected)
            }
        }
    }
    
    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long,
    val isTyping: Boolean = false,
    val isError: Boolean = false
)
