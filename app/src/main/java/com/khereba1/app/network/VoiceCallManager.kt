package com.khereba1.app.network

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.khereba1.app.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class VoiceCallState(
    val isActive: Boolean = false,
    val status: String = "",
    val isMuted: Boolean = false,
    val isSpeaker: Boolean = false
)

object VoiceCallManager {
    private const val CALL_PORT = 8766
    private const val SAMPLE_RATE = 16000
    private val active = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    private val _state = MutableStateFlow(VoiceCallState())
    val state: StateFlow<VoiceCallState> = _state

    @SuppressLint("MissingPermission")
    fun startCall(context: Context, peerIp: String?) {
        stopCall(context)
        val cleanIp = peerIp?.trim()?.takeIf { it.isNotEmpty() }
        try {
            val ds = DatagramSocket(CALL_PORT)
            ds.soTimeout = 1000
            socket = ds
            active.set(true)

            val minRecBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(3200)

            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minRecBuf
            )
            audioRecord = rec

            val minPlayBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(3200)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minPlayBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            audioTrack = track

            rec.startRecording()
            track.play()

            val remoteAddr = AtomicReference<InetAddress?>(if (cleanIp != null) InetAddress.getByName(cleanIp) else null)

            _state.value = VoiceCallState(
                isActive = true,
                status = if (cleanIp != null) "Calling $cleanIp…" else "Listening on port $CALL_PORT…"
            )

            // Receive thread
            Thread {
                val buf = ByteArray(1600)
                while (active.get()) {
                    try {
                        val pkt = DatagramPacket(buf, buf.size)
                        ds.receive(pkt)
                        if (remoteAddr.get() == null) {
                            remoteAddr.set(pkt.address)
                            _state.value = _state.value.copy(status = "Connected: ${pkt.address.hostAddress}")
                        }
                        if (pkt.length > 0) {
                            track.write(pkt.data, 0, pkt.length)
                        }
                    } catch (_: Throwable) {
                    }
                }
            }.start()

            // Send thread
            Thread {
                val buf = ByteArray(1280)
                while (active.get()) {
                    try {
                        val read = rec.read(buf, 0, buf.size)
                        val target = remoteAddr.get()
                        if (read > 0 && target != null && !_state.value.isMuted) {
                            ds.send(DatagramPacket(buf, read, target, CALL_PORT))
                        }
                    } catch (_: Throwable) {
                    }
                }
            }.start()
        } catch (e: Throwable) {
            AppLog.log("CALL", "Failed to start voice call", e)
            stopCall(context)
        }
    }

    fun toggleMute() {
        _state.value = _state.value.copy(isMuted = !_state.value.isMuted)
    }

    fun toggleSpeaker(context: Context) {
        val next = !_state.value.isSpeaker
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = next
        } catch (_: Throwable) {
        }
        _state.value = _state.value.copy(isSpeaker = next)
    }

    fun stopCall(context: Context) {
        active.set(false)
        try { audioRecord?.stop() } catch (_: Throwable) {}
        try { audioRecord?.release() } catch (_: Throwable) {}
        audioRecord = null
        try { audioTrack?.stop() } catch (_: Throwable) {}
        try { audioTrack?.release() } catch (_: Throwable) {}
        audioTrack = null
        try { socket?.close() } catch (_: Throwable) {}
        socket = null
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = false
        } catch (_: Throwable) {}
        _state.value = VoiceCallState()
    }
}
