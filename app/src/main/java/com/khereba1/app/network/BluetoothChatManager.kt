package com.khereba1.app.network

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.khereba1.app.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors

data class BtChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: String,
    val text: String,
    val isMine: Boolean,
    val timeMs: Long = System.currentTimeMillis()
)

enum class BtConnectionStatus { IDLE, HOSTING, CONNECTING, CONNECTED, ERROR }

data class BtState(
    val status: BtConnectionStatus = BtConnectionStatus.IDLE,
    val peerName: String = "",
    val messages: List<BtChatMessage> = emptyList()
)

@SuppressLint("MissingPermission")
class BluetoothChatManager(context: Context) {
    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = btManager?.adapter
    private val executor = Executors.newCachedThreadPool()

    private var serverSocket: BluetoothServerSocket? = null
    private var activeSocket: BluetoothSocket? = null
    private var writer: OutputStreamWriter? = null

    private val _state = MutableStateFlow(BtState())
    val state: StateFlow<BtState> = _state

    companion object {
        private val SERVICE_UUID: UUID = UUID.fromString("8a9e6b30-7c1d-4e52-9b18-2c3f4a5b6c7d")
        private const val SERVICE_NAME = "NearChatBT"
    }

    fun isAvailable(): Boolean = adapter != null && adapter.isEnabled

    fun getPairedDevices(): List<BluetoothDevice> {
        return try {
            adapter?.bondedDevices?.toList() ?: emptyList()
        } catch (e: Throwable) {
            AppLog.log("BT", "Failed to list paired devices", e)
            emptyList()
        }
    }

    fun startHosting() {
        disconnect()
        val bt = adapter ?: run {
            _state.value = _state.value.copy(status = BtConnectionStatus.ERROR)
            return
        }
        _state.value = BtState(status = BtConnectionStatus.HOSTING)
        executor.execute {
            try {
                val ss = bt.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
                serverSocket = ss
                val socket = ss.accept()
                ss.close()
                serverSocket = null
                onConnected(socket)
            } catch (e: Throwable) {
                AppLog.log("BT", "Host failed", e)
                if (_state.value.status == BtConnectionStatus.HOSTING) {
                    _state.value = _state.value.copy(status = BtConnectionStatus.ERROR)
                }
            }
        }
    }

    fun connectToDevice(device: BluetoothDevice) {
        disconnect()
        _state.value = BtState(status = BtConnectionStatus.CONNECTING, peerName = device.name ?: device.address)
        executor.execute {
            try {
                adapter?.cancelDiscovery()
                val socket = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
                socket.connect()
                onConnected(socket)
            } catch (e: Throwable) {
                AppLog.log("BT", "Connect failed", e)
                _state.value = _state.value.copy(status = BtConnectionStatus.ERROR)
            }
        }
    }

    private fun onConnected(socket: BluetoothSocket) {
        activeSocket = socket
        val peer = try {
            socket.remoteDevice?.name ?: socket.remoteDevice?.address ?: "Peer"
        } catch (_: Throwable) {
            "Peer"
        }
        writer = OutputStreamWriter(socket.outputStream, StandardCharsets.UTF_8)
        _state.value = _state.value.copy(status = BtConnectionStatus.CONNECTED, peerName = peer)

        try {
            val reader = BufferedReader(InputStreamReader(socket.inputStream, StandardCharsets.UTF_8))
            while (true) {
                val line = reader.readLine() ?: break
                val clean = line.replace("\\n", "\n")
                val msg = BtChatMessage(sender = peer, text = clean, isMine = false)
                _state.value = _state.value.copy(messages = _state.value.messages + msg)
            }
        } catch (e: Throwable) {
            AppLog.log("BT", "Read loop ended", e)
        } finally {
            disconnect()
        }
    }

    fun sendMessage(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        executor.execute {
            try {
                val w = writer ?: return@execute
                w.write(clean.replace("\n", "\\n") + "\n")
                w.flush()
                val msg = BtChatMessage(sender = "Me", text = clean, isMine = true)
                _state.value = _state.value.copy(messages = _state.value.messages + msg)
            } catch (e: Throwable) {
                AppLog.log("BT", "Send failed", e)
                disconnect()
            }
        }
    }

    fun disconnect() {
        try { serverSocket?.close() } catch (_: Throwable) {}
        try { activeSocket?.close() } catch (_: Throwable) {}
        serverSocket = null
        activeSocket = null
        writer = null
        _state.value = _state.value.copy(status = BtConnectionStatus.IDLE)
    }
}
