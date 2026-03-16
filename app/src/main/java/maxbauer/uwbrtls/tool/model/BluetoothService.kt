package maxbauer.uwbrtls.tool.model

import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

const val GET_LOCATION_CHARACTERISTIC = "003BBDF2-C634-4B3D-AB56-7EC889B89A37"
private const val GET_PROXY_POSITIONS_CHARACTERISTIC = "F4A67D7D-379D-4183-9C03-4B6EA5103291"
private const val SET_LOCATION_MODE_CHARACTERISTIC = "A02B947E-DF97-4516-996A-1882521E0EAD"
private const val DESCRIPTOR = "00002902-0000-1000-8000-00805F9B34FB"

// Custom service UUID from your TAG
const val CUSTOM_SERVICE_UUID = "680c21d9-c946-4c1f-9c11-baa1c21329e7"

// Target tag MAC address: DWM1001-DEV (DWC3A8 - Red)
private const val TAG_MAC = "C5:72:82:1D:6F:59"
private val POSITION_MODE = byteArrayOf(0x00)
private const val TAG = "BluetoothService"

// Scan timeout in milliseconds
private const val SCAN_TIMEOUT_MS = 15000L // 15 seconds

// Position data byte array size (X, Y, Z as floats = 4 bytes each = 12 bytes + 2 bytes?)
private const val POSITION_BYTE_ARRAY_SIZE = 14

class BluetoothService(private val model: ModelImpl) {

    private var tagConnection: BluetoothGatt? = null
    private var tagIsConnected = true
    private var isScanning = false
    private var customService: BluetoothGattService? = null

    private val handler = Handler(Looper.getMainLooper())
    private var scanTimeoutRunnable: Runnable? = null

    // Queue for serializing BLE write operations
    private val bleOperationQueue = ConcurrentLinkedQueue<BleOperation>()
    private var isProcessingOperation = false

    // Coroutine scope for background operations
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val bluetoothAdapter: BluetoothAdapter? by lazy(LazyThreadSafetyMode.NONE) {
        val bluetoothManager = model.context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    private val BluetoothAdapter.isDisabled: Boolean
        get() = !isEnabled

    // ===== HELPER: Convert bytes to hex string =====
    fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString(" ") { String.format("%02X", it) }
    }

    // ===== HELPER: Dump all GATT services, characteristics, and descriptors =====
    fun dumpGatt() {
        val gatt = tagConnection ?: run {
            Log.w(TAG, "dumpGatt: tagConnection is null")
            return
        }

        Log.d(TAG, "========== DUMP GATT SERVICES ==========")
        gatt.services.forEachIndexed { serviceIndex, service ->
            Log.d(TAG, "[Service $serviceIndex] UUID: ${service.uuid}")
            service.characteristics.forEachIndexed { charIndex, characteristic ->
                val props = buildString {
                    append(if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ > 0) "READ " else "")
                    append(if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE > 0) "WRITE " else "")
                    append(if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE > 0) "WRITE_NO_RESP " else "")
                    append(if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY > 0) "NOTIFY " else "")
                    append(if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE > 0) "INDICATE " else "")
                }
                Log.d(TAG, "  [Char $charIndex] UUID: ${characteristic.uuid} | Props: $props")
                characteristic.descriptors.forEachIndexed { descIndex, descriptor ->
                    Log.d(TAG, "    [Desc $descIndex] UUID: ${descriptor.uuid}")
                }
            }
        }
        Log.d(TAG, "========== END DUMP GATT ==========")
    }

    // ===== HELPER: Log raw payload for debugging =====
    private fun logCharacteristicPayload(characteristic: BluetoothGattCharacteristic, prefix: String = "RECV") {
        val payload = characteristic.value
        if (payload != null && payload.isNotEmpty()) {
            val hex = bytesToHex(payload)
            val len = payload.size
            Log.d(TAG, "$prefix | UUID: ${characteristic.uuid} | Len: $len | Hex: $hex")
        }
    }

    // ===== BLE Operation Queue for serialization =====
    private sealed class BleOperation {
        data class WriteDescriptor(val descriptor: BluetoothGattDescriptor, val value: ByteArray) : BleOperation()
        object ProcessQueue : BleOperation()
    }

    private fun queueBleWriteOperation(descriptor: BluetoothGattDescriptor, value: ByteArray) {
        bleOperationQueue.add(BleOperation.WriteDescriptor(descriptor, value))
        processNextBleOperation()
    }

    private fun processNextBleOperation() {
        if (isProcessingOperation) return

        val operation = bleOperationQueue.poll() ?: return
        isProcessingOperation = true

        when (operation) {
            is BleOperation.WriteDescriptor -> {
                operation.descriptor.value = operation.value
                val success = tagConnection?.writeDescriptor(operation.descriptor) ?: false
                if (!success) {
                    Log.w(TAG, "writeDescriptor failed for ${operation.descriptor.uuid}")
                }
                // Next operation will be triggered in onDescriptorWrite
            }
            is BleOperation.ProcessQueue -> {
                isProcessingOperation = false
                processNextBleOperation()
            }
        }
    }

    fun initialize(){
        try {
            // Ensures Bluetooth is available on the device and it is enabled.
            bluetoothAdapter?.takeIf { it.isDisabled }?.apply {
                model.onBluetoothNotEnabled()
                bluetoothAdapter?.enable()
                return
            }
            scanLeDevice()
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException during Bluetooth initialization", e)
            model.onConnectionSuccess(false)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during Bluetooth initialization", e)
            model.onConnectionSuccess(false)
        }
    }

    fun terminate(): Boolean{
        // Stop scanning if active
        stopScan()

        if (tagIsConnected){
            disableLocationDataNotifications(GET_PROXY_POSITIONS_CHARACTERISTIC)
            tagConnection?.disconnect()
            tagConnection?.close()
            tagConnection = null
            tagIsConnected = false
            return true
        }
        return false
    }

    private fun stopScan() {
        try {
            if (isScanning) {
                val scanner = bluetoothAdapter?.bluetoothLeScanner
                scanner?.stopScan(scanCallback)
                isScanning = false
                Log.d(TAG, "BLE scan stopped")
            }
            // Cancel timeout handler
            scanTimeoutRunnable?.let { handler.removeCallbacks(it) }
            scanTimeoutRunnable = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping scan", e)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            val device = result?.device
            val deviceName = device?.name ?: "Unknown"
            val deviceAddress = device?.address ?: "Unknown"
            val rssi = result?.rssi ?: -999

            Log.d(TAG, "onScanResult: name=$deviceName, address=$deviceAddress, rssi=$rssi")

            // Check if this is our target tag
            if (device?.address?.equals(TAG_MAC, ignoreCase = true) == true) {
                Log.i(TAG, ">>> Target tag found! MAC=$TAG_MAC, name=$deviceName, rssi=$rssi")
                // Stop scanning before connecting
                stopScan()
                // Connect to the device
                device.connectGatt(model.context, false, gattCallback)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error code: $errorCode")
            isScanning = false
            model.onConnectionSuccess(false)
        }
    }

    private fun scanLeDevice() {
        // Prevent multiple simultaneous scans
        if (isScanning) {
            Log.w(TAG, "Already scanning, skipping new scan request")
            return
        }

        try {
            val scanner = bluetoothAdapter?.bluetoothLeScanner
            if (scanner == null) {
                Log.e(TAG, "BluetoothLeScanner is null")
                model.onConnectionSuccess(false)
                return
            }

            isScanning = true
            Log.d(TAG, "Starting BLE scan for target: $TAG_MAC")

            scanner.startScan(scanCallback)

            // Set timeout to stop scan after SCAN_TIMEOUT_MS
            scanTimeoutRunnable = Runnable {
                Log.d(TAG, "Scan timeout reached, stopping scan")
                stopScan()
                if (!tagIsConnected) {
                    Log.w(TAG, "Target tag not found within timeout")
                    model.onConnectionSuccess(false)
                }
            }
            handler.postDelayed(scanTimeoutRunnable!!, SCAN_TIMEOUT_MS)

        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException during BLE scan", e)
            isScanning = false
            model.onConnectionSuccess(false)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during BLE scan", e)
            isScanning = false
            model.onConnectionSuccess(false)
        }
    }

    // Various callback methods defined by the BLE API.
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, ">>> STATE_CONNECTED: tag=$TAG_MAC, status=$status")
                    tagConnection = gatt
                    tagIsConnected = true
                    tagConnection!!.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, ">>> STATE_DISCONNECTED: status=$status")
                    tagIsConnected = false
                    tagConnection = null
                    model.onDisconnectionSuccess(true)
                }
            }
        }

        // New services discovered
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d(TAG, ">>> onServicesDiscovered: status=$status")
            when (status) {
                BluetoothGatt.GATT_SUCCESS -> {
                    // Find custom service by UUID instead of hardcoded index
                    val customServiceUuid = UUID.fromString(CUSTOM_SERVICE_UUID)
                    val service = gatt.getService(customServiceUuid)
                    
                    if (service != null) {
                        customService = service
                        Log.d(TAG, ">>> Found custom service: $CUSTOM_SERVICE_UUID")
                        
                        // Dump GATT for debugging
                        dumpGatt()
                        
                        // Set location mode to 0 (Position only mode)
                        val setLocationModeCharacteristic = service.getCharacteristic(UUID.fromString(
                            SET_LOCATION_MODE_CHARACTERISTIC
                        ))
                        if (setLocationModeCharacteristic != null) {
                            setLocationModeCharacteristic.value = POSITION_MODE
                            val success = gatt.writeCharacteristic(setLocationModeCharacteristic)
                            if (!success) {
                                model.onConnectionSuccess(false)
                            }
                        } else {
                            Log.w(TAG, "SET_LOCATION_MODE_CHARACTERISTIC not found, proceeding to connect anyway")
                            // Still enable notifications for proxy to prevent disconnection
                            enableCharacteristicNotifications(GET_PROXY_POSITIONS_CHARACTERISTIC)
                            model.onConnectionSuccess(true)
                        }
                    } else {
                        Log.e(TAG, "Custom service $CUSTOM_SERVICE_UUID not found!")
                        model.onConnectionSuccess(false)
                    }
                }
                else -> {
                    // Unsuccessful service discovery
                    Log.e(TAG, "Service discovery failed with status: $status")
                    model.onConnectionSuccess(false)
                }
            }
        }

        // Check if the position mode set in 'onServicesDiscovered' was successful
        // If yes subscribe to PROXY_POSITIONS in order to disable automatic disconnection from tag
        // If no inform UI about failed connection attempt
        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int){
            if (characteristic?.uuid == UUID.fromString(SET_LOCATION_MODE_CHARACTERISTIC) && status == BluetoothGatt.GATT_SUCCESS){
                if (characteristic?.value!!.contentEquals(POSITION_MODE)) {
                    // Subscribing to changes of PROXY_POSITIONS characteristic
                    // We're not interested in this characteristic but subscribing to it prevents us
                    // from automatically being disconnected from tag while inactive
                    enableCharacteristicNotifications(GET_PROXY_POSITIONS_CHARACTERISTIC)
                    model.onConnectionSuccess(true)
                } else {
                    model.onConnectionSuccess(false)
                }
            } else {
                model.onConnectionSuccess(false)
            }
        }

        // Remote characteristic changes handling - DEBUG ALL notify characteristics
        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            val payload = characteristic?.value
            val len = payload?.size ?: 0
            val hex = if (payload != null) bytesToHex(payload) else "null"

            // Log ALL incoming notifications for debugging
            Log.d(TAG, "NOTIFY | UUID: ${characteristic?.uuid} | Len: $len | Hex: $hex")

            // Only forward position data (14 bytes) to model for processing
            // Filter out other notifications that don't match position data format
            if (len == POSITION_BYTE_ARRAY_SIZE) {
                Log.d(TAG, ">>> Position data received: $hex")
                model.onCharacteristicChange(payload!!)
            } else {
                Log.d(TAG, ">>> Non-position notification (len=$len), ignoring")
            }
        }

        // Handle descriptor write completion for queue processing
        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Descriptor write success: ${descriptor?.uuid}")
            } else {
                Log.w(TAG, "Descriptor write failed: ${descriptor?.uuid}, status=$status")
            }
            // Process next operation in queue
            isProcessingOperation = false
            processNextBleOperation()
        }
    }

    /**
     * Enable notifications for a characteristic (properly serialized)
     * @param characteristicString UUID of characteristic to enable
     * @param useQueue If true, use operation queue (recommended for sequential operations)
     */
    fun enableCharacteristicNotifications(characteristicString: String, useQueue: Boolean = true) {
        if (tagIsConnected && customService != null) {
            val service = customService!!
            val characteristic = service.getCharacteristic(UUID.fromString(characteristicString))
            if (characteristic == null) {
                Log.w(TAG, "Characteristic $characteristicString not found in custom service")
                return
            }
            tagConnection?.setCharacteristicNotification(characteristic, true)

            val descriptor = characteristic.getDescriptor(UUID.fromString(DESCRIPTOR))
            if (descriptor != null) {
                if (useQueue) {
                    queueBleWriteOperation(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    tagConnection?.writeDescriptor(descriptor)
                }
            } else {
                Log.w(TAG, "Descriptor $DESCRIPTOR not found for characteristic $characteristicString")
            }
        } else {
            Log.w(TAG, "Cannot enable notifications: not connected or service not found")
            initialize()
        }
    }

    /**
     * Disable notifications for a characteristic
     * @param characteristicString UUID of characteristic to disable
     * @param useQueue If true, use operation queue (recommended for sequential operations)
     */
    fun disableLocationDataNotifications(characteristicString: String, useQueue: Boolean = true) {
        if (tagIsConnected && customService != null) {
            val service = customService!!
            val characteristic = service.getCharacteristic(UUID.fromString(characteristicString))
            if (characteristic == null) {
                Log.w(TAG, "Characteristic $characteristicString not found in custom service")
                return
            }
            tagConnection?.setCharacteristicNotification(characteristic, false)

            val descriptor = characteristic.getDescriptor(UUID.fromString(DESCRIPTOR))
            if (descriptor != null) {
                if (useQueue) {
                    queueBleWriteOperation(descriptor, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
                } else {
                    descriptor.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                    tagConnection?.writeDescriptor(descriptor)
                }
            }
        }
    }

    /**
     * DEBUG: Enable notifications on ALL characteristics to find position data
     * Call this after onServicesDiscovered to find which characteristic has position data
     */
    fun debugEnableAllNotifications() {
        if (!tagIsConnected) return

        val gatt = tagConnection ?: return
        Log.d(TAG, "========== DEBUG: Enabling all notify characteristics ==========")

        gatt.services.forEach { service ->
            service.characteristics.forEach { characteristic ->
                if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY > 0) {
                    val descriptor = characteristic.getDescriptor(UUID.fromString(DESCRIPTOR))
                    if (descriptor != null) {
                        Log.d(TAG, "Enabling notify for: ${characteristic.uuid}")
                        tagConnection?.setCharacteristicNotification(characteristic, true)
                        queueBleWriteOperation(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    }
                }
            }
        }
    }
}
