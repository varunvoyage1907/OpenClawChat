package com.openclaw.chat.ui

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.openclaw.chat.R
import com.openclaw.chat.databinding.ActivityGlassesScanBinding
import com.openclaw.chat.glasses.BluetoothEvent
import com.openclaw.chat.glasses.ConnectionManager
import com.openclaw.chat.glasses.GlassesBluetoothReceiver
import com.openclaw.chat.glasses.GlassesManager
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.DeviceManager
import com.oudmon.ble.base.scan.BleScannerHelper
import com.oudmon.ble.base.scan.ScanRecord
import com.oudmon.ble.base.scan.ScanWrapperCallback
import android.bluetooth.le.ScanResult
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class GlassesScanActivity : AppCompatActivity(), GlassesBluetoothReceiver.ConnectionCallback {
    
    companion object {
        private const val TAG = "GlassesScanActivity"
        private const val SCAN_TIMEOUT_MS = 15000L
        private const val CONNECT_TIMEOUT_MS = 20000L
        private const val PERMISSION_REQUEST_CODE = 100
    }
    
    private lateinit var binding: ActivityGlassesScanBinding
    private lateinit var glassesManager: GlassesManager
    private val deviceList = mutableListOf<ScannedDevice>()
    private lateinit var adapter: DeviceAdapter
    private val handler = Handler(Looper.getMainLooper())
    private var isScanning = false
    private var isConnecting = false
    private var connectingDeviceName: String? = null
    
    private val bleScanCallback = object : ScanWrapperCallback {
        override fun onStart() {
            Log.d(TAG, "BLE scan started")
        }
        
        override fun onStop() {
            Log.d(TAG, "BLE scan stopped")
            runOnUiThread {
                isScanning = false
                binding.btnScan.text = "Start Scanning"
                if (!isConnecting) {
                    binding.progressBar.visibility = View.GONE
                }
            }
        }
        
        override fun onLeScan(device: BluetoothDevice?, rssi: Int, scanRecord: ByteArray?) {
            device?.let {
                val name = it.name ?: return
                
                Log.d(TAG, "Found: $name (${it.address}) rssi: $rssi")
                
                val scannedDevice = ScannedDevice(name, it.address, rssi)
                
                if (!deviceList.any { d -> d.address == it.address }) {
                    runOnUiThread {
                        deviceList.add(scannedDevice)
                        deviceList.sortByDescending { d -> d.rssi }
                        adapter.notifyDataSetChanged()
                        updateEmptyState()
                    }
                }
            }
        }
        
        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            runOnUiThread {
                Toast.makeText(this@GlassesScanActivity, "Scan failed: $errorCode", Toast.LENGTH_SHORT).show()
                isScanning = false
                binding.btnScan.text = "Start Scanning"
                binding.progressBar.visibility = View.GONE
            }
        }
        
        override fun onParsedData(device: BluetoothDevice?, scanRecord: ScanRecord?) {}
        override fun onBatchScanResults(results: MutableList<ScanResult>?) {}
    }
    
    private val stopScanRunnable = Runnable { stopScan() }
    
    private val connectTimeoutRunnable = Runnable {
        if (isConnecting) {
            Log.e(TAG, "Connection timeout")
            isConnecting = false
            ConnectionManager.onDisconnected()
            runOnUiThread {
                Toast.makeText(this, "Connection timeout. Please try again.", Toast.LENGTH_LONG).show()
                binding.progressBar.visibility = View.GONE
                binding.btnScan.isEnabled = true
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGlassesScanBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        glassesManager = GlassesManager.getInstance(this)
        glassesManager.initialize()
        
        GlassesBluetoothReceiver.connectionCallback = this
        
        try {
            if (!EventBus.getDefault().isRegistered(this)) {
                EventBus.getDefault().register(this)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register EventBus", e)
        }
        
        setupUI()
        setupRecyclerView()
        checkPermissions()
    }
    
    override fun onConnected(deviceName: String?) {
        Log.d(TAG, "onConnected: $deviceName")
        if (isConnecting) {
            handler.removeCallbacks(connectTimeoutRunnable)
            isConnecting = false
            
            runOnUiThread {
                Toast.makeText(this, "Connected to ${deviceName ?: connectingDeviceName ?: "glasses"}!", Toast.LENGTH_SHORT).show()
                glassesManager.onDeviceConnected(deviceName ?: connectingDeviceName)
                
                setResult(RESULT_OK)
                finish()
            }
        }
    }
    
    override fun onDisconnected() {
        Log.d(TAG, "onDisconnected")
        if (isConnecting) {
            handler.removeCallbacks(connectTimeoutRunnable)
            isConnecting = false
            
            runOnUiThread {
                Toast.makeText(this, "Connection failed", Toast.LENGTH_SHORT).show()
                binding.progressBar.visibility = View.GONE
                binding.btnScan.isEnabled = true
            }
        }
    }
    
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onBluetoothEvent(event: BluetoothEvent) {
        Log.d(TAG, "BluetoothEvent: connected=${event.connected}")
        if (event.connected) {
            onConnected(null)
        } else {
            onDisconnected()
        }
    }
    
    private fun setupUI() {
        binding.btnBack.setOnClickListener { finish() }
        
        binding.btnScan.setOnClickListener {
            if (isScanning) {
                stopScan()
            } else {
                if (checkPermissions()) {
                    startScan()
                }
            }
        }
    }
    
    private fun setupRecyclerView() {
        adapter = DeviceAdapter(deviceList) { device ->
            stopScan()
            connectToDevice(device)
        }
        
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        
        updateEmptyState()
    }
    
    private fun checkPermissions(): Boolean {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        
        val needed = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSION_REQUEST_CODE)
            return false
        }
        
        return true
    }
    
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startScan()
            } else {
                Toast.makeText(this, "Bluetooth permissions required", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun startScan() {
        Log.d(TAG, "Starting BLE scan")
        deviceList.clear()
        adapter.notifyDataSetChanged()
        updateEmptyState()
        
        isScanning = true
        binding.btnScan.text = "Stop Scanning"
        binding.progressBar.visibility = View.VISIBLE
        binding.tvInstructions.text = "Scanning for nearby glasses..."
        
        BleScannerHelper.getInstance().reSetCallback()
        BleScannerHelper.getInstance().scanDevice(this, null, bleScanCallback)
        
        handler.removeCallbacks(stopScanRunnable)
        handler.postDelayed(stopScanRunnable, SCAN_TIMEOUT_MS)
    }
    
    private fun stopScan() {
        Log.d(TAG, "Stopping BLE scan")
        handler.removeCallbacks(stopScanRunnable)
        BleScannerHelper.getInstance().stopScan(this)
        isScanning = false
        binding.btnScan.text = "Start Scanning"
        if (!isConnecting) {
            binding.progressBar.visibility = View.GONE
        }
        binding.tvInstructions.text = if (deviceList.isEmpty()) {
            "No devices found. Tap Start Scanning to try again."
        } else {
            "Tap a device to connect"
        }
    }
    
    private fun connectToDevice(device: ScannedDevice) {
        Log.d(TAG, "Connecting to: ${device.name} (${device.address})")
        
        isConnecting = true
        connectingDeviceName = device.name
        
        binding.progressBar.visibility = View.VISIBLE
        binding.btnScan.isEnabled = false
        binding.tvInstructions.text = "Connecting to ${device.name}..."
        
        Toast.makeText(this, "Connecting to ${device.name}...", Toast.LENGTH_SHORT).show()
        
        DeviceManager.getInstance().deviceAddress = device.address
        ConnectionManager.startConnecting()
        BleOperateManager.getInstance().connectDirectly(device.address)
        
        handler.removeCallbacks(connectTimeoutRunnable)
        handler.postDelayed(connectTimeoutRunnable, CONNECT_TIMEOUT_MS)
    }
    
    private fun updateEmptyState() {
        binding.tvEmpty.visibility = if (deviceList.isEmpty()) View.VISIBLE else View.GONE
    }
    
    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(stopScanRunnable)
        handler.removeCallbacks(connectTimeoutRunnable)
        stopScan()
        
        GlassesBluetoothReceiver.connectionCallback = null
        try {
            if (EventBus.getDefault().isRegistered(this)) {
                EventBus.getDefault().unregister(this)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister EventBus", e)
        }
    }
    
    data class ScannedDevice(
        val name: String,
        val address: String,
        val rssi: Int
    )
    
    inner class DeviceAdapter(
        private val devices: List<ScannedDevice>,
        private val onItemClick: (ScannedDevice) -> Unit
    ) : RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {
        
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvDeviceName)
            val tvAddress: TextView = view.findViewById(R.id.tvDeviceAddress)
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_glasses_device, parent, false)
            return ViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val device = devices[position]
            holder.tvName.text = "${device.name} (${device.rssi} dBm)"
            holder.tvAddress.text = device.address
            holder.itemView.setOnClickListener { onItemClick(device) }
        }
        
        override fun getItemCount() = devices.size
    }
}
