package com.openclaw.chat.glasses

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.DeviceManager
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyListener
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyRsp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages connection and communication with smart glasses via BLE.
 * Uses the Oudmon SDK (glasses_sdk.aar)
 * 
 * Image capture flow:
 * 1. Register notification listener
 * 2. Send command to trigger AI photo
 * 3. Wait for 0x02 notification
 * 4. Call getPictureThumbnails() to receive image data in chunks
 * 
 * This is a SINGLETON to ensure callbacks persist across activities.
 */
class GlassesManager private constructor(private var context: Context) {
    
    companion object {
        private const val TAG = "GlassesManager"
        private const val LISTENER_ID = 100
        
        // AI Photo command: 0x02, 0x01, 0x06, thumbnailSize, thumbnailSize, 0x02
        // thumbnailSize: 0-6 (higher = better quality)
        private const val THUMBNAIL_SIZE: Byte = 0x04
        
        @Volatile
        private var instance: GlassesManager? = null
        
        fun getInstance(context: Context): GlassesManager {
            return instance ?: synchronized(this) {
                instance ?: GlassesManager(context.applicationContext).also { 
                    instance = it 
                    Log.d(TAG, "Created GlassesManager singleton instance")
                }
            }
        }
    }
    
    fun updateContext(newContext: Context) {
        this.context = newContext.applicationContext
    }
    
    enum class ConnectionState {
        DISCONNECTED,
        SCANNING,
        CONNECTING,
        CONNECTED
    }
    
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    private val _batteryLevel = MutableStateFlow(0)
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()
    
    private val _isCharging = MutableStateFlow(false)
    val isCharging: StateFlow<Boolean> = _isCharging.asStateFlow()
    
    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()
    
    // Callbacks
    var onImageReceived: ((ByteArray) -> Unit)? = null
    var onAIPhotoTriggered: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onMicrophoneActivated: (() -> Unit)? = null
    var onConnected: ((String?) -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    
    private var listenerRegistered = false
    
    // Buffer to accumulate image data chunks
    private val imageDataBuffer = mutableListOf<Byte>()
    private var isReceivingImage = false
    
    private val deviceNotifyListener = object : GlassesDeviceNotifyListener() {
        override fun parseData(cmdType: Int, response: GlassesDeviceNotifyRsp) {
            try {
                if (response.loadData == null || response.loadData.size < 7) {
                    return
                }
                
                val eventType = response.loadData[6].toInt() and 0xFF
                Log.d(TAG, "Notification: eventType=0x${String.format("%02X", eventType)}")
                
                when (eventType) {
                    // Battery status (0x05)
                    0x05 -> {
                        val battery = response.loadData.getOrNull(7)?.toInt()?.and(0xFF) ?: 0
                        val charging = (response.loadData.getOrNull(8)?.toInt()?.and(0xFF) ?: 0) == 1
                        _batteryLevel.value = battery
                        _isCharging.value = charging
                        Log.d(TAG, "Battery: $battery%, Charging: $charging")
                    }
                    
                    // AI Photo notification (0x02)
                    0x02 -> {
                        Log.d(TAG, "AI PHOTO NOTIFICATION")
                        
                        if (response.loadData.size > 9) {
                            val subType = response.loadData[9].toInt() and 0xFF
                            if (subType == 0x02) {
                                onAIPhotoTriggered?.invoke()
                            }
                        }
                        
                        fetchThumbnailNow()
                    }
                    
                    // Microphone event (0x03)
                    0x03 -> {
                        val micState = response.loadData.getOrNull(7)?.toInt()?.and(0xFF) ?: 0
                        if (micState == 1) {
                            Log.d(TAG, "Glasses microphone ACTIVATED")
                            onMicrophoneActivated?.invoke()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing notification", e)
            }
        }
    }
    
    fun initialize() {
        Log.d(TAG, "Initializing GlassesManager")
        
        if (!listenerRegistered) {
            try {
                try {
                    LargeDataHandler.getInstance().removeOutDeviceListener(LISTENER_ID)
                } catch (e: Exception) { }
                
                LargeDataHandler.getInstance().addOutDeviceListener(LISTENER_ID, deviceNotifyListener)
                listenerRegistered = true
                Log.d(TAG, "Notification listener registered")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register listener", e)
            }
        }
    }
    
    fun reRegisterListener() {
        listenerRegistered = false
        initialize()
    }
    
    private fun fetchThumbnailNow() {
        Log.d(TAG, "Fetching thumbnail...")
        
        synchronized(imageDataBuffer) {
            if (isReceivingImage && imageDataBuffer.isNotEmpty()) {
                return
            }
            imageDataBuffer.clear()
            isReceivingImage = true
        }
        
        try {
            LargeDataHandler.getInstance().getPictureThumbnails { cmdType, success, data ->
                if (data != null && data.isNotEmpty()) {
                    synchronized(imageDataBuffer) {
                        imageDataBuffer.addAll(data.toList())
                        
                        if (success) {
                            isReceivingImage = false
                            val fullImageData = imageDataBuffer.toByteArray()
                            imageDataBuffer.clear()
                            
                            Log.d(TAG, "Image complete: ${fullImageData.size} bytes")
                            
                            if (fullImageData.size > 100) {
                                // Find JPEG start marker if offset
                                var jpegStartIndex = -1
                                for (i in 0 until minOf(fullImageData.size - 1, 100)) {
                                    if (fullImageData[i] == 0xFF.toByte() && fullImageData[i + 1] == 0xD8.toByte()) {
                                        jpegStartIndex = i
                                        break
                                    }
                                }
                                
                                val finalImageData = if (jpegStartIndex > 0) {
                                    fullImageData.copyOfRange(jpegStartIndex, fullImageData.size)
                                } else {
                                    fullImageData
                                }
                                
                                if (finalImageData.size > 500) {
                                    onImageReceived?.invoke(finalImageData)
                                } else {
                                    onError?.invoke("Invalid image data")
                                }
                            } else {
                                onError?.invoke("Image too small")
                            }
                        }
                    }
                } else if (success) {
                    synchronized(imageDataBuffer) {
                        if (imageDataBuffer.isNotEmpty()) {
                            isReceivingImage = false
                            val fullImageData = imageDataBuffer.toByteArray()
                            imageDataBuffer.clear()
                            
                            if (fullImageData.size > 100) {
                                onImageReceived?.invoke(fullImageData)
                            } else {
                                onError?.invoke("Image too small")
                            }
                        } else {
                            onError?.invoke("No image data received")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in getPictureThumbnails", e)
            onError?.invoke("Error: ${e.message}")
        }
    }
    
    fun captureAIPhoto(onSuccess: () -> Unit = {}, onFail: (String) -> Unit = {}) {
        Log.d(TAG, "Capturing AI Photo...")
        
        val command = byteArrayOf(
            0x02, 
            0x01, 
            0x06, 
            THUMBNAIL_SIZE, 
            THUMBNAIL_SIZE, 
            0x02
        )
        
        LargeDataHandler.getInstance().glassesControl(command) { cmdType, response ->
            if (response.dataType == 1) {
                when (response.workTypeIng) {
                    1, 6, 7 -> {
                        Log.d(TAG, "Photo command accepted")
                        onSuccess()
                    }
                    2 -> onFail("Glasses recording video")
                    4 -> onFail("Glasses in transfer mode")
                    5 -> onFail("Glasses in OTA mode")
                    8 -> onFail("Glasses recording audio")
                    else -> onSuccess()
                }
            } else {
                onFail("Photo command failed")
            }
        }
    }
    
    fun fetchLatestThumbnail() {
        fetchThumbnailNow()
    }
    
    // Connection management
    fun setScanning(scanning: Boolean) {
        _connectionState.value = if (scanning) ConnectionState.SCANNING else ConnectionState.DISCONNECTED
    }
    
    fun connectToDevice(deviceAddress: String) {
        Log.d(TAG, "Connecting to: $deviceAddress")
        _connectionState.value = ConnectionState.CONNECTING
        DeviceManager.getInstance().deviceAddress = deviceAddress
        BleOperateManager.getInstance().connectDirectly(deviceAddress)
    }
    
    fun onDeviceConnected(deviceName: String?) {
        Log.d(TAG, "Connected to: $deviceName")
        _connectionState.value = ConnectionState.CONNECTED
        _connectedDeviceName.value = deviceName
        
        reRegisterListener()
        syncTime()
        getBatteryStatus()
        
        onConnected?.invoke(deviceName)
    }
    
    fun onDeviceDisconnected() {
        Log.d(TAG, "Disconnected")
        _connectionState.value = ConnectionState.DISCONNECTED
        _connectedDeviceName.value = null
        _batteryLevel.value = 0
        
        onDisconnected?.invoke()
    }
    
    fun disconnect() {
        Log.d(TAG, "Disconnecting...")
        BleOperateManager.getInstance().unBindDevice()
    }
    
    fun isConnected(): Boolean = try {
        BleOperateManager.getInstance().isConnected
    } catch (e: Exception) {
        false
    }
    
    fun syncTime() {
        LargeDataHandler.getInstance().syncTime { _, _ -> 
            Log.d(TAG, "Time synced")
        }
    }
    
    fun getBatteryStatus() {
        LargeDataHandler.getInstance().addBatteryCallBack("openclaw") { _, response ->
            if (response != null) {
                Log.d(TAG, "Battery callback received")
            }
        }
        LargeDataHandler.getInstance().syncBattery()
    }
    
    fun bytesToBitmap(data: ByteArray): Bitmap? {
        return try {
            BitmapFactory.decodeByteArray(data, 0, data.size)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode image", e)
            null
        }
    }
    
    fun cleanup() {
        Log.d(TAG, "Cleanup")
    }
}
