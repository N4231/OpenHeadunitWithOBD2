package com.andrerinas.openheadunit.aap

import android.Manifest
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.andrerinas.openheadunit.R

/**
 * ELM327 (OBD2 Bluetooth Classic & BLE) と通信して車速を取得するサービス
 */
@SuppressLint("MissingPermission")
class Elm327Service : Service() {

    private var obdConnection: ObdConnection? = null
    private var serviceJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "Elm327Service"
        private const val CHANNEL_ID = "Elm327ServiceChannel"
        private const val NOTIFICATION_ID = 2

        val currentVehicleSpeed = MutableStateFlow(0)
        val connectionState = MutableStateFlow(false)
        val connectedDeviceName = MutableStateFlow<String?>(null)
        val updateFrequency = MutableStateFlow(0f)
        val isServiceRunning = MutableStateFlow(false)
        val errorMessage = MutableStateFlow<String?>(null)

        const val EXTRA_DEVICE_ADDRESS = "device_address"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isServiceRunning.value = true
        startForegroundService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val deviceAddress = intent?.getStringExtra(EXTRA_DEVICE_ADDRESS)
        if (deviceAddress != null) {
            errorMessage.value = null
            connectToDevice(deviceAddress)
        } else {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundService() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "OBD2 Connection", NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OBD2 Connection Service")
            .setContentText("ELM327 Connection is running")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun connectToDevice(address: String) {
        if (address.isEmpty() || address == "00:00:00:00:00:00") {
            Log.w(TAG, "未設定のMACアドレス: $address")
            errorMessage.value = "未設定 (設定画面からデバイスを選択してください)"
            return
        }

        serviceJob?.cancel()
        serviceJob = serviceScope.launch {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val hasPermission = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    if (!hasPermission) {
                        errorMessage.value = "Bluetooth接続権限がありません"
                        return@launch
                    }
                }

                val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
                val adapter = bluetoothManager.adapter

                try {
                    if (adapter.isDiscovering) adapter.cancelDiscovery()
                } catch (e: Exception) {}

                val device = adapter.getRemoteDevice(address)

                // First try Classic Bluetooth, then fallback to BLE
                try {
                    Log.d(TAG, "Trying Classic Bluetooth...")
                    obdConnection = ClassicObdConnection(device)
                    obdConnection?.connect()
                } catch (e: Exception) {
                    Log.d(TAG, "Classic Bluetooth failed (${e.message}), trying BLE...")
                    obdConnection?.close()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                        obdConnection = BleObdConnection(this@Elm327Service, device)
                        obdConnection?.connect()
                    } else {
                        throw IOException("BLE not supported on this OS version")
                    }
                }

                connectionState.value = true
                connectedDeviceName.value = try { device.name } catch (e: Exception) { null } ?: "OBD2 Device"

                // 1. 初期化コマンド
                // AT L0: ラインフィードオフ, AT S0: スペースオフで応答速度を最大化
                val initCommands = listOf("AT Z", "AT E0", "AT L0", "AT S0", "AT H0", "AT AT2", "AT ST 10")
                for (cmd in initCommands) {
                    obdConnection?.write(cmd)
                    val response = obdConnection?.readUntilPrompt()
                    Log.d(TAG, "CMD: $cmd RESP: $response")
                    delay(50)
                }

                var updateCount = 0
                var frequencyTimer = System.currentTimeMillis()

                // 2. 車速取得ループ
                while (isActive) {
                    obdConnection?.write("01 0D")
                    val response = obdConnection?.readUntilPrompt() ?: ""
                    if (parseSpeedResponse(response)) {
                        updateCount++
                        val now = System.currentTimeMillis()
                        if (now - frequencyTimer >= 1000) {
                            updateFrequency.value = updateCount.toFloat()
                            updateCount = 0
                            frequencyTimer = now
                        }
                    }
                    delay(100) // Android Autoが要求する10Hz(100ms間隔)に最適化
                }
            } catch (e: Exception) {
                Log.e(TAG, "OBD2 Connection error", e)
                errorMessage.value = "接続エラー: ${e.message}"
                connectionState.value = false
                obdConnection?.close()
            }
        }
    }

    private fun parseSpeedResponse(response: String): Boolean {
        // "41 0D XX" (XX is hex speed)
        val cleaned = response.replace("\\s+".toRegex(), "").uppercase(Locale.ROOT)
        val idx = cleaned.indexOf("410D")
        if (idx != -1 && cleaned.length >= idx + 6) {
            val hexStr = cleaned.substring(idx + 4, idx + 6)
            try {
                val speed = hexStr.toInt(16)
                currentVehicleSpeed.value = speed
                return true
            } catch (e: NumberFormatException) {
                // Ignore
            }
        }
        return false
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning.value = false
        connectionState.value = false
        serviceJob?.cancel()
        obdConnection?.close()
    }
}

interface ObdConnection {
    suspend fun connect()
    suspend fun write(command: String)
    suspend fun readUntilPrompt(): String
    fun close()
}

@SuppressLint("MissingPermission")
class ClassicObdConnection(private val device: BluetoothDevice) : ObdConnection {
    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    override suspend fun connect() = withContext(Dispatchers.IO) {
        val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        socket = device.createRfcommSocketToServiceRecord(uuid)
        socket?.connect()
        inputStream = socket?.inputStream
        outputStream = socket?.outputStream
    }

    override suspend fun write(command: String) {
        withContext(Dispatchers.IO) {
            outputStream?.write((command + "\r").toByteArray())
            outputStream?.flush()
        }
    }

    override suspend fun readUntilPrompt(): String = withContext(Dispatchers.IO) {
        val buffer = StringBuilder()
        while (true) {
            val b = inputStream?.read() ?: -1
            if (b == -1) break
            val c = b.toChar()
            if (c == '>') break
            buffer.append(c)
        }
        buffer.toString().trim()
    }

    override fun close() {
        try { socket?.close() } catch (e: Exception) {}
    }
}

@RequiresApi(18)
@SuppressLint("MissingPermission")
class BleObdConnection(private val context: Context, private val device: BluetoothDevice) : ObdConnection {
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val rxChannel = Channel<Char>(Channel.UNLIMITED)
    private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private var connected = false

    override suspend fun connect() = suspendCancellableCoroutine<Unit> { cont ->
        gatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    rxChannel.close()
                    if (cont.isActive) cont.resumeWithException(IOException("BLE Disconnected"))
                    if (connected) g.close()
                }
            }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val service = g.services.find {
                    val uuid = it.uuid.toString().lowercase(Locale.ROOT)
                    uuid.startsWith("0000ffe0") || uuid.startsWith("0000fff0") || uuid.startsWith("000018f0")
                }
                if (service != null) {
                    val notifyChar = service.characteristics.find { (it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 }
                    writeChar = service.characteristics.find {
                        (it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                        (it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                    }
                    if (notifyChar != null && writeChar != null) {
                        g.setCharacteristicNotification(notifyChar, true)
                        val desc = notifyChar.getDescriptor(CCCD)
                        if (desc != null) {
                            desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            g.writeDescriptor(desc)
                        }
                        connected = true
                        if (cont.isActive) cont.resume(Unit)
                    } else {
                        if (cont.isActive) cont.resumeWithException(IOException("BLE Characteristics not found"))
                    }
                } else {
                    if (cont.isActive) cont.resumeWithException(IOException("BLE SPP Service not found"))
                }
            }
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                c.value?.forEach { rxChannel.trySend(it.toInt().toChar()) }
            }
        })
        cont.invokeOnCancellation {
            gatt?.close()
        }
    }

    override suspend fun write(command: String) {
        val bytes = (command + "\r").toByteArray()
        writeChar?.value = bytes
        gatt?.writeCharacteristic(writeChar)
    }

    override suspend fun readUntilPrompt(): String {
        val sb = StringBuilder()
        while (true) {
            val c = rxChannel.receive()
            if (c == '>') break
            sb.append(c)
        }
        return sb.toString().trim()
    }

    override fun close() {
        try { gatt?.disconnect() } catch (e: Exception) {}
        try { gatt?.close() } catch (e: Exception) {}
        rxChannel.close()
    }
}
