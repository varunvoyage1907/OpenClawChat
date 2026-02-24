package com.openclaw.chat.glasses

import android.bluetooth.BluetoothDevice
import android.util.Log
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.DeviceManager
import com.oudmon.ble.base.bluetooth.QCBluetoothCallbackCloneReceiver
import com.oudmon.ble.base.communication.LargeDataHandler
import org.greenrobot.eventbus.EventBus

/**
 * Bluetooth callback receiver for glasses connection events.
 * Based on Oudmon SDK's QCBluetoothCallbackCloneReceiver
 */
class GlassesBluetoothReceiver : QCBluetoothCallbackCloneReceiver() {
    
    companion object {
        private const val TAG = "GlassesBluetoothReceiver"
        
        var connectionCallback: ConnectionCallback? = null
    }
    
    interface ConnectionCallback {
        fun onConnected(deviceName: String?)
        fun onDisconnected()
    }
    
    override fun connectStatue(device: BluetoothDevice?, connected: Boolean) {
        Log.d(TAG, "connectStatue: device=${device?.name}, connected=$connected")
        
        if (device != null && connected) {
            if (device.name != null) {
                DeviceManager.getInstance().deviceName = device.name
            }
        } else {
            Log.d(TAG, "Device disconnected")
            EventBus.getDefault().post(BluetoothEvent(false))
            connectionCallback?.onDisconnected()
            ConnectionManager.onDisconnected()
        }
    }
    
    override fun onServiceDiscovered() {
        Log.d(TAG, "BLE services discovered - connection ready")
        
        try {
            LargeDataHandler.getInstance().initEnable()
            Log.d(TAG, "LargeDataHandler initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init LargeDataHandler", e)
        }
        
        BleOperateManager.getInstance().isReady = true
        
        val deviceName = DeviceManager.getInstance().deviceName
        Log.d(TAG, "Connection complete! Device: $deviceName")
        
        EventBus.getDefault().post(BluetoothEvent(true))
        connectionCallback?.onConnected(deviceName)
        ConnectionManager.onConnected(deviceName)
    }
    
    override fun onCharacteristicChange(address: String?, uuid: String?, data: ByteArray?) {
        // Handled by LargeDataHandler
    }
    
    override fun onCharacteristicRead(uuid: String?, data: ByteArray?) {
        if (uuid != null && data != null) {
            Log.d(TAG, "onCharacteristicRead: uuid=$uuid")
        }
    }
}
