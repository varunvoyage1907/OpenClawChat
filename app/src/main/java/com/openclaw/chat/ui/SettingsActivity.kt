package com.openclaw.chat.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.openclaw.chat.databinding.ActivitySettingsBinding
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class SettingsActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivitySettingsBinding
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        loadSettings()
        setupButtons()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
    
    private fun loadSettings() {
        val prefs = getSharedPreferences("openclaw_config", MODE_PRIVATE)
        
        // Default port is 18789 for OpenClaw gateway
        binding.etOpenClawUrl.setText(
            prefs.getString("openclaw_url", "http://76.13.247.120:18789/v1/chat/completions")
        )
        // Gateway auth token from gateway.auth.token in openclaw.json
        binding.etOpenClawToken.setText(
            prefs.getString("openclaw_token", "")
        )
        binding.etSystemPrompt.setText(
            prefs.getString("system_prompt", "You are a helpful AI assistant. Be concise and friendly.")
        )
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
        
        binding.btnResetPrompt.setOnClickListener {
            binding.etSystemPrompt.setText("You are a helpful AI assistant. Be concise and friendly.")
        }
    }
    
    private fun saveSettings() {
        val url = binding.etOpenClawUrl.text.toString().trim()
        val token = binding.etOpenClawToken.text.toString().trim()
        val systemPrompt = binding.etSystemPrompt.text.toString().trim()
        
        if (url.isEmpty()) {
            Toast.makeText(this, "URL cannot be empty", Toast.LENGTH_SHORT).show()
            return
        }
        
        getSharedPreferences("openclaw_config", MODE_PRIVATE)
            .edit()
            .putString("openclaw_url", url)
            .putString("openclaw_token", token)
            .putString("system_prompt", systemPrompt)
            .apply()
        
        Toast.makeText(this, "Settings saved!", Toast.LENGTH_SHORT).show()
        finish()
    }
    
    private fun testConnection() {
        val url = binding.etOpenClawUrl.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(this, "Enter URL first", Toast.LENGTH_SHORT).show()
            return
        }
        
        binding.tvTestResult.text = "Testing connection..."
        binding.btnTest.isEnabled = false
        
        scope.launch {
            try {
                val testUrl = url.replace("/v1/chat/completions", "/v1/models")
                val result = withContext(Dispatchers.IO) {
                    val request = Request.Builder()
                        .url(testUrl)
                        .get()
                        .build()
                    
                    val response = httpClient.newCall(request).execute()
                    Pair(response.code, response.body?.string() ?: "")
                }
                
                binding.tvTestResult.text = when {
                    result.first == 200 -> "✅ Connection successful!\nOpenClaw is responding."
                    result.first == 401 -> "⚠️ Unauthorized - check your token"
                    else -> "⚠️ Response code: ${result.first}"
                }
                
            } catch (e: Exception) {
                binding.tvTestResult.text = "❌ Connection failed:\n${e.message}"
            } finally {
                binding.btnTest.isEnabled = true
            }
        }
    }
}
