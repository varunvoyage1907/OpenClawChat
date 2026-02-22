package com.openclaw.chat.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.openclaw.chat.R
import com.openclaw.chat.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.*
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    
    companion object {
        private const val REQUEST_RECORD_AUDIO = 100
    }
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var chatAdapter: ChatAdapter
    private val messages = mutableListOf<ChatMessage>()
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val gson = Gson()
    
    // TTS
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var autoSpeak = true
    
    // Speech Recognition
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    
    // HTTP Client
    private val streamingClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
    
    // Conversation history for context
    private val conversationHistory = mutableListOf<Map<String, String>>()
    private val MAX_HISTORY = 20
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Make app draw behind system bars
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Handle window insets for proper padding
        setupWindowInsets()
        
        setupUI()
        setupTTS()
        setupSpeechRecognizer()
        checkPermissions()
    }
    
    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            
            // Apply top padding to header
            binding.header.updatePadding(top = insets.top)
            
            // Apply bottom padding to input container (use keyboard height if visible, otherwise nav bar)
            val bottomPadding = if (imeInsets.bottom > 0) imeInsets.bottom else insets.bottom
            binding.inputContainer.updatePadding(bottom = bottomPadding + 8)
            
            windowInsets
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        tts?.shutdown()
        speechRecognizer?.destroy()
    }
    
    private fun setupUI() {
        // RecyclerView
        chatAdapter = ChatAdapter(messages)
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply {
                stackFromEnd = true
            }
            adapter = chatAdapter
        }
        
        // Send button
        binding.btnSend.setOnClickListener {
            sendMessage()
        }
        
        // Voice button
        binding.btnVoice.setOnClickListener {
            toggleVoiceInput()
        }
        
        // Enter key sends message
        binding.etMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else false
        }
        
        // Settings
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        
        // Toggle TTS
        binding.btnToggleTts.setOnClickListener {
            autoSpeak = !autoSpeak
            updateTtsButton()
            toast(if (autoSpeak) "Voice output ON" else "Voice output OFF")
        }
        
        // Stop speaking
        binding.btnStopSpeaking.setOnClickListener {
            tts?.stop()
            binding.btnStopSpeaking.visibility = View.GONE
        }
        
        // Clear chat
        binding.btnClear.setOnClickListener {
            messages.clear()
            conversationHistory.clear()
            chatAdapter.notifyDataSetChanged()
            toast("Chat cleared")
        }
        
        updateTtsButton()
    }
    
    private fun setupTTS() {
        tts = TextToSpeech(this, this)
    }
    
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            tts?.setSpeechRate(1.0f)
            isTtsReady = true
            
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    runOnUiThread {
                        binding.btnStopSpeaking.visibility = View.VISIBLE
                    }
                }
                override fun onDone(utteranceId: String?) {
                    runOnUiThread {
                        binding.btnStopSpeaking.visibility = View.GONE
                    }
                }
                override fun onError(utteranceId: String?) {
                    runOnUiThread {
                        binding.btnStopSpeaking.visibility = View.GONE
                    }
                }
            })
        }
    }
    
    private fun setupSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    runOnUiThread {
                        binding.btnVoice.setImageResource(R.drawable.ic_mic_active)
                        binding.tvListening.visibility = View.VISIBLE
                    }
                }
                
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                
                override fun onEndOfSpeech() {
                    runOnUiThread {
                        binding.btnVoice.setImageResource(R.drawable.ic_mic)
                        binding.tvListening.visibility = View.GONE
                        isListening = false
                    }
                }
                
                override fun onError(error: Int) {
                    runOnUiThread {
                        binding.btnVoice.setImageResource(R.drawable.ic_mic)
                        binding.tvListening.visibility = View.GONE
                        isListening = false
                        
                        val errorMsg = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                            SpeechRecognizer.ERROR_AUDIO -> "Audio error"
                            else -> "Voice error: $error"
                        }
                        if (error != SpeechRecognizer.ERROR_NO_MATCH) {
                            toast(errorMsg)
                        }
                    }
                }
                
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val text = matches[0]
                        runOnUiThread {
                            binding.etMessage.setText(text)
                            sendMessage()
                        }
                    }
                }
                
                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!partial.isNullOrEmpty()) {
                        runOnUiThread {
                            binding.etMessage.setText(partial[0])
                        }
                    }
                }
                
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        } else {
            binding.btnVoice.isEnabled = false
            toast("Speech recognition not available")
        }
    }
    
    private fun toggleVoiceInput() {
        if (isListening) {
            speechRecognizer?.stopListening()
            isListening = false
            binding.btnVoice.setImageResource(R.drawable.ic_mic)
            binding.tvListening.visibility = View.GONE
        } else {
            // Stop any ongoing TTS first
            tts?.stop()
            
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            
            speechRecognizer?.startListening(intent)
            isListening = true
        }
    }
    
    private fun sendMessage() {
        val text = binding.etMessage.text.toString().trim()
        if (text.isEmpty()) return
        
        binding.etMessage.setText("")
        
        // Add user message
        val userMessage = ChatMessage(text, true, System.currentTimeMillis())
        messages.add(userMessage)
        chatAdapter.notifyItemInserted(messages.size - 1)
        scrollToBottom()
        
        // Add to history
        conversationHistory.add(mapOf("role" to "user", "content" to text))
        trimHistory()
        
        // Show typing indicator
        val typingMessage = ChatMessage("...", false, System.currentTimeMillis(), isTyping = true)
        messages.add(typingMessage)
        val typingIndex = messages.size - 1
        chatAdapter.notifyItemInserted(typingIndex)
        scrollToBottom()
        
        // Send to OpenClaw
        sendToOpenClaw(text, typingIndex)
    }
    
    private fun sendToOpenClaw(message: String, typingIndex: Int) {
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    callOpenClawStreaming(message, typingIndex)
                }
                
                // Update typing message with response
                if (typingIndex < messages.size) {
                    messages[typingIndex] = ChatMessage(response, false, System.currentTimeMillis())
                    chatAdapter.notifyItemChanged(typingIndex)
                    scrollToBottom()
                    
                    // Add to history
                    conversationHistory.add(mapOf("role" to "assistant", "content" to response))
                    trimHistory()
                    
                    // Speak response
                    if (autoSpeak && isTtsReady) {
                        speakText(response)
                    }
                }
                
            } catch (e: Exception) {
                runOnUiThread {
                    if (typingIndex < messages.size) {
                        messages[typingIndex] = ChatMessage("Error: ${e.message}", false, System.currentTimeMillis(), isError = true)
                        chatAdapter.notifyItemChanged(typingIndex)
                    }
                    toast("Error: ${e.message}")
                }
            }
        }
    }
    
    private suspend fun callOpenClawStreaming(message: String, typingIndex: Int): String {
        val prefs = getSharedPreferences("openclaw_config", MODE_PRIVATE)
        // Default to port 18789 which is OpenClaw's default gateway port
        val openClawUrl = prefs.getString("openclaw_url", "http://76.13.247.120:18789/v1/chat/completions") ?: ""
        val authToken = prefs.getString("openclaw_token", "") ?: ""
        val systemPrompt = prefs.getString("system_prompt", "You are a helpful AI assistant. Be concise and friendly.") ?: ""
        
        // Build messages with system prompt first
        val messagesForApi = mutableListOf<Map<String, String>>()
        messagesForApi.add(mapOf("role" to "system", "content" to systemPrompt))
        messagesForApi.addAll(conversationHistory)
        
        // Use "openclaw:main" as model - routes to the main agent
        val requestBody = gson.toJson(mapOf(
            "model" to "openclaw:main",
            "messages" to messagesForApi,
            "stream" to true
        ))
        
        android.util.Log.d("OpenClawChat", "Sending to: $openClawUrl")
        android.util.Log.d("OpenClawChat", "Request: $requestBody")
        
        val requestBuilder = Request.Builder()
            .url(openClawUrl)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .addHeader("x-openclaw-agent-id", "main")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
        
        // Always add auth token for OpenClaw gateway
        if (authToken.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $authToken")
        }
        
        val response = streamingClient.newCall(requestBuilder.build()).execute()
        
        android.util.Log.d("OpenClawChat", "Response code: ${response.code}")
        
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "No error body"
            android.util.Log.e("OpenClawChat", "API error: ${response.code} - $errorBody")
            throw Exception("API error ${response.code}: $errorBody")
        }
        
        val fullResponse = StringBuilder()
        
        response.body?.source()?.let { source ->
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                
                if (line.startsWith("data: ")) {
                    val data = line.removePrefix("data: ").trim()
                    if (data == "[DONE]") break
                    
                    try {
                        val json = JsonParser.parseString(data).asJsonObject
                        val choices = json.getAsJsonArray("choices")
                        if (choices != null && choices.size() > 0) {
                            val delta = choices[0].asJsonObject.getAsJsonObject("delta")
                            val content = delta?.get("content")?.asString
                            if (!content.isNullOrEmpty()) {
                                fullResponse.append(content)
                                
                                // Update UI with streaming content
                                withContext(Dispatchers.Main) {
                                    if (typingIndex < messages.size) {
                                        messages[typingIndex] = ChatMessage(
                                            fullResponse.toString(),
                                            false,
                                            System.currentTimeMillis(),
                                            isTyping = true
                                        )
                                        chatAdapter.notifyItemChanged(typingIndex)
                                        scrollToBottom()
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("OpenClawChat", "Parse error: ${e.message} for line: $line")
                    }
                }
            }
        }
        
        return fullResponse.toString()
    }
    
    private fun speakText(text: String) {
        if (!isTtsReady) return
        
        // Clean text for better TTS
        val cleanText = text
            .replace(Regex("```[\\s\\S]*?```"), " code block ")
            .replace(Regex("`[^`]+`"), "")
            .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
            .replace(Regex("\\*([^*]+)\\*"), "$1")
            .replace(Regex("#+\\s*"), "")
            .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
            .trim()
        
        if (cleanText.isNotEmpty()) {
            tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
        }
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
            }
        }
    }
    
    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

// Data class for chat messages
data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long,
    val isTyping: Boolean = false,
    val isError: Boolean = false
)
