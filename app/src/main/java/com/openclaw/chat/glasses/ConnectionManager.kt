package com.openclaw.chat.glasses

import android.util.Log

/**
 * Singleton to track glasses connection state globally across activities
 */
object ConnectionManager {
    private const val TAG = "ConnectionManager"
    
    var isConnecting = false
        private set
    
    var connectedDeviceName: String? = null
        private set
    
    var connectionListener: ConnectionListener? = null
    
    interface ConnectionListener {
        fun onConnectionSuccess(deviceName: String?)
        fun onConnectionFailed()
    }
    
    fun startConnecting() {
        Log.d(TAG, "startConnecting")
        isConnecting = true
        connectedDeviceName = null
    }
    
    fun onConnected(deviceName: String?) {
        Log.d(TAG, "onConnected: $deviceName")
        isConnecting = false
        connectedDeviceName = deviceName
        connectionListener?.onConnectionSuccess(deviceName)
    }
    
    fun onDisconnected() {
        Log.d(TAG, "onDisconnected")
        val wasConnecting = isConnecting
        isConnecting = false
        connectedDeviceName = null
        
        if (wasConnecting) {
            connectionListener?.onConnectionFailed()
        }
    }
    
    fun reset() {
        isConnecting = false
        connectedDeviceName = null
    }
}
