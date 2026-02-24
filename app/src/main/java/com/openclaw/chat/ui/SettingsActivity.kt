package com.openclaw.chat.ui

import android.media.MediaPlayer
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.openclaw.chat.R
import com.openclaw.chat.databinding.ActivitySettingsBinding
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class SettingsActivity : AppCompatActivity() {
    
    companion object {
        // Bulbul v3 CORRECT voices - from API validation
        val FEMALE_VOICES = listOf(
            "priya" to "Priya (Hindi - Recommended)",
            "neha" to "Neha (Hindi)",
            "ritu" to "Ritu (Hindi)",
            "pooja" to "Pooja (Hindi)",
            "simran" to "Simran (Punjabi)",
            "kavya" to "Kavya (South Indian)",
            "ishita" to "Ishita (Young)",
            "shreya" to "Shreya (Energetic)",
            "roopa" to "Roopa (Mature)",
            "amelia" to "Amelia (English - American)",
            "sophia" to "Sophia (English - British)",
            "tanya" to "Tanya (Modern)",
            "shruti" to "Shruti (Soft)",
            "suhani" to "Suhani (Sweet)",
            "kavitha" to "Kavitha (Tamil)",
            "rupali" to "Rupali (Bengali)"
        )
        
        val MALE_VOICES = listOf(
            "aditya" to "Aditya (Hindi - Deep)",
            "rahul" to "Rahul (Hindi - Clear)",
            "amit" to "Amit (Hindi - Neutral)",
            "dev" to "Dev (Hindi - Young)",
            "rohan" to "Rohan (Professional)",
            "ratan" to "Ratan (Mature)",
            "varun" to "Varun (Energetic)",
            "manan" to "Manan (Calm)",
            "sumit" to "Sumit (Friendly)",
            "kabir" to "Kabir (Deep)",
            "aayan" to "Aayan (Modern)",
            "shubh" to "Shubh (Young)",
            "ashutosh" to "Ashutosh (Professional)",
            "advait" to "Advait (Soft)",
            "anand" to "Anand (Warm)",
            "tarun" to "Tarun (Clear)",
            "sunny" to "Sunny (English - Cheerful)",
            "mani" to "Mani (Tamil)",
            "gokul" to "Gokul (Tamil)",
            "vijay" to "Vijay (Telugu)",
            "mohit" to "Mohit (Neutral)",
            "rehan" to "Rehan (Urdu)",
            "soham" to "Soham (Bengali)"
        )
        
        val ALL_VOICES = FEMALE_VOICES + MALE_VOICES
        
        val LANGUAGES = listOf(
            "hi-IN" to "Hindi",
            "en-IN" to "English (Indian)",
            "ta-IN" to "Tamil",
            "te-IN" to "Telugu",
            "bn-IN" to "Bengali",
            "mr-IN" to "Marathi",
            "gu-IN" to "Gujarati",
            "kn-IN" to "Kannada",
            "ml-IN" to "Malayalam",
            "pa-IN" to "Punjabi",
            "od-IN" to "Odia"
        )
    }
    
    private lateinit var binding: ActivitySettingsBinding
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var mediaPlayer: MediaPlayer? = null
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private var selectedVoice = "priya"
    private var selectedLanguage = "hi-IN"
    private var speechPace = 1.3f
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupSpinners()
        setupSeekBar()
        loadSettings()
        setupButtons()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        mediaPlayer?.release()
        mediaPlayer = null
    }
    
    private fun setupSpinners() {
        // Voice Spinner
        val voiceNames = ALL_VOICES.map { it.second }
        val voiceAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, voiceNames)
        voiceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerVoice.adapter = voiceAdapter
        
        binding.spinnerVoice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedVoice = ALL_VOICES[position].first
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        // Language Spinner
        val languageNames = LANGUAGES.map { it.second }
        val languageAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, languageNames)
        languageAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerLanguage.adapter = languageAdapter
        
        binding.spinnerLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedLanguage = LANGUAGES[position].first
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
    
    private fun setupSeekBar() {
        // SeekBar: 0-15 maps to 0.5x - 2.0x (step of 0.1)
        binding.seekBarPace.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                speechPace = 0.5f + (progress * 0.1f)
                binding.tvPaceValue.text = String.format("%.1fx", speechPace)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }
    
    private fun loadSettings() {
        val prefs = getSharedPreferences("openclaw_config", MODE_PRIVATE)
        
        // OpenClaw settings
        binding.etOpenClawUrl.setText(
            prefs.getString("openclaw_url", "http://76.13.247.120:59269")
        )
        binding.etOpenClawToken.setText(
            prefs.getString("openclaw_token", "")
        )
        binding.etSystemPrompt.setText(
            prefs.getString("system_prompt", "You are a helpful AI assistant. Be concise and friendly.")
        )
        
        // Sarvam AI settings
        binding.etSarvamApiKey.setText(
            prefs.getString("sarvam_api_key", "")
        )
        
        // Voice selection
        selectedVoice = prefs.getString("sarvam_voice", "priya") ?: "priya"
        val voiceIndex = ALL_VOICES.indexOfFirst { it.first == selectedVoice }
        if (voiceIndex >= 0) {
            binding.spinnerVoice.setSelection(voiceIndex)
        }
        
        // Language selection
        selectedLanguage = prefs.getString("sarvam_language", "hi-IN") ?: "hi-IN"
        val languageIndex = LANGUAGES.indexOfFirst { it.first == selectedLanguage }
        if (languageIndex >= 0) {
            binding.spinnerLanguage.setSelection(languageIndex)
        }
        
        // Speech pace
        speechPace = prefs.getFloat("sarvam_pace", 1.3f)
        val paceProgress = ((speechPace - 0.5f) / 0.1f).toInt().coerceIn(0, 15)
        binding.seekBarPace.progress = paceProgress
        binding.tvPaceValue.text = String.format("%.1fx", speechPace)
    }
    
    private fun setupButtons() {
        binding.btnSave.setOnClickListener {
            saveSettings()
        }
        
        binding.btnBack.setOnClickListener {
            finish()
        }
        
        binding.btnTest.setOnClickListener {
            testConnection()
        }
        
        binding.btnTestVoice.setOnClickListener {
            testVoice()
        }
        
        binding.btnResetPrompt.setOnClickListener {
            binding.etSystemPrompt.setText("You are a helpful AI assistant. Be concise and friendly.")
        }
    }
    
    private fun saveSettings() {
        val url = binding.etOpenClawUrl.text.toString().trim()
        val token = binding.etOpenClawToken.text.toString().trim()
        val systemPrompt = binding.etSystemPrompt.text.toString().trim()
        val sarvamApiKey = binding.etSarvamApiKey.text.toString().trim()
        
        if (url.isEmpty()) {
            Toast.makeText(this, "OpenClaw URL cannot be empty", Toast.LENGTH_SHORT).show()
            return
        }
        
        getSharedPreferences("openclaw_config", MODE_PRIVATE)
            .edit()
            .putString("openclaw_url", url)
            .putString("openclaw_token", token)
            .putString("system_prompt", systemPrompt)
            .putString("sarvam_api_key", sarvamApiKey)
            .putString("sarvam_voice", selectedVoice)
            .putString("sarvam_language", selectedLanguage)
            .putFloat("sarvam_pace", speechPace)
            .apply()
        
        Toast.makeText(this, "Settings saved!", Toast.LENGTH_SHORT).show()
        finish()
    }
    
    private fun testConnection() {
        val url = binding.etOpenClawUrl.text.toString().trim()
        val token = binding.etOpenClawToken.text.toString().trim()
        
        if (url.isEmpty()) {
            Toast.makeText(this, "Enter URL first", Toast.LENGTH_SHORT).show()
            return
        }
        
        binding.tvTestResult.text = "Testing WebSocket connection..."
        binding.btnTest.isEnabled = false
        
        scope.launch {
            try {
                // Test WebSocket handshake
                val wsUrl = url.replace("http://", "ws://").replace("https://", "wss://")
                    .replace("/v1/chat/completions", "")
                
                val result = withContext(Dispatchers.IO) {
                    val request = Request.Builder()
                        .url(wsUrl)
                        .build()
                    
                    try {
                        val response = httpClient.newCall(
                            Request.Builder()
                                .url(url.replace("/v1/chat/completions", "/health").replace("ws://", "http://").replace("wss://", "https://"))
                                .get()
                                .build()
                        ).execute()
                        
                        val body = response.body?.string() ?: ""
                        Triple(response.code, body, response.isSuccessful)
                    } catch (e: Exception) {
                        Triple(-1, e.message ?: "Connection failed", false)
                    }
                }
                
                binding.tvTestResult.text = when {
                    result.first == 200 -> "✅ Server reachable!\nToken: ${if (token.isNotEmpty()) "Set" else "Not set"}"
                    result.first == -1 -> "❌ Connection failed:\n${result.second}"
                    else -> "⚠️ Response: ${result.first}\n${result.second.take(100)}"
                }
                
            } catch (e: Exception) {
                binding.tvTestResult.text = "❌ Connection failed:\n${e.message}"
            } finally {
                binding.btnTest.isEnabled = true
            }
        }
    }
    
    private fun testVoice() {
        val apiKey = binding.etSarvamApiKey.text.toString().trim()
        
        if (apiKey.isEmpty()) {
            binding.tvVoiceTestResult.text = "❌ Enter Sarvam AI API key first"
            return
        }
        
        binding.tvVoiceTestResult.text = "🔊 Testing voice..."
        binding.btnTestVoice.isEnabled = false
        
        scope.launch {
            try {
                val testText = when {
                    selectedLanguage.startsWith("hi") -> "नमस्ते, मैं आपकी मदद के लिए तैयार हूं।"
                    selectedLanguage.startsWith("ta") -> "வணக்கம், நான் உங்களுக்கு உதவ தயாராக இருக்கிறேன்."
                    selectedLanguage.startsWith("te") -> "నమస్కారం, నేను మీకు సహాయం చేయడానికి సిద్ధంగా ఉన్నాను."
                    selectedLanguage.startsWith("bn") -> "নমস্কার, আমি আপনাকে সাহায্য করতে প্রস্তুত।"
                    else -> "Hello, I am ready to help you."
                }
                
                val audioFile = withContext(Dispatchers.IO) {
                    fetchTtsAudio(apiKey, testText)
                }
                
                if (audioFile != null) {
                    withContext(Dispatchers.Main) {
                        playAudio(audioFile)
                        binding.tvVoiceTestResult.text = "✅ Voice: $selectedVoice | Pace: ${String.format("%.1f", speechPace)}x"
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        binding.tvVoiceTestResult.text = "❌ Failed to generate audio"
                    }
                }
                
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.tvVoiceTestResult.text = "❌ Error: ${e.message}"
                }
            } finally {
                withContext(Dispatchers.Main) {
                    binding.btnTestVoice.isEnabled = true
                }
            }
        }
    }
    
    private fun fetchTtsAudio(apiKey: String, text: String): File? {
        // CORRECT FORMAT for Bulbul v3: inputs is an ARRAY, NO pitch/loudness
        val json = JSONObject().apply {
            put("inputs", org.json.JSONArray().apply { put(text) })
            put("target_language_code", selectedLanguage)
            put("speaker", selectedVoice)
            put("model", "bulbul:v3")
            put("pace", speechPace)
            put("enable_preprocessing", true)
        }
        
        val request = Request.Builder()
            .url("https://api.sarvam.ai/text-to-speech")
            .addHeader("api-subscription-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()
        
        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string()
        
        android.util.Log.d("SarvamTTS", "Response code: ${response.code}")
        android.util.Log.d("SarvamTTS", "Response body: ${responseBody?.take(200)}")
        
        if (response.isSuccessful && responseBody != null) {
            val responseJson = JSONObject(responseBody)
            
            // audios is an ARRAY - get first element
            val audiosArray = responseJson.optJSONArray("audios")
            if (audiosArray != null && audiosArray.length() > 0) {
                val audioBase64 = audiosArray.getString(0)
                android.util.Log.d("SarvamTTS", "Audio base64 length: ${audioBase64.length}")
                
                val audioData = Base64.decode(audioBase64, Base64.DEFAULT)
                val audioFile = File(cacheDir, "test_voice_${System.currentTimeMillis()}.wav")
                FileOutputStream(audioFile).use { it.write(audioData) }
                return audioFile
            } else {
                android.util.Log.e("SarvamTTS", "No audios array in response")
            }
        } else {
            android.util.Log.e("SarvamTTS", "API error: ${response.code} - $responseBody")
        }
        
        return null
    }
    
    private fun playAudio(file: File) {
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            prepare()
            setOnCompletionListener {
                file.delete()
            }
            start()
        }
    }
}
