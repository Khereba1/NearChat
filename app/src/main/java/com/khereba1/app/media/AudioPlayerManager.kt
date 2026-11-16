package com.khereba1.app.media

import android.media.MediaPlayer
import com.khereba1.app.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

data class AudioPlaybackState(
    val activeMessageId: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0
)

object AudioPlayerManager {
    private var player: MediaPlayer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tickerJob: Job? = null
    private val _state = MutableStateFlow(AudioPlaybackState())
    val state: StateFlow<AudioPlaybackState> = _state

    fun toggle(messageId: String, file: File) {
        val current = _state.value
        if (current.activeMessageId == messageId && player != null) {
            try {
                if (current.isPlaying) {
                    player?.pause()
                    tickerJob?.cancel()
                    _state.value = current.copy(isPlaying = false)
                } else {
                    player?.start()
                    _state.value = current.copy(isPlaying = true)
                    startTicker()
                }
            } catch (e: Throwable) {
                AppLog.log("AUDIO", "Toggle failed", e)
                stop()
            }
            return
        }

        stop()
        if (!file.exists()) return
        try {
            val mp = MediaPlayer()
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener {
                stop()
            }
            mp.setOnErrorListener { _, _, _ ->
                stop()
                true
            }
            mp.prepare()
            val dur = mp.duration.coerceAtLeast(1)
            mp.start()
            player = mp
            _state.value = AudioPlaybackState(
                activeMessageId = messageId,
                isPlaying = true,
                positionMs = 0,
                durationMs = dur
            )
            startTicker()
        } catch (e: Throwable) {
            AppLog.log("AUDIO", "Playback failed for ${file.name}", e)
            stop()
        }
    }

    fun seekTo(positionMs: Int) {
        try {
            val mp = player ?: return
            mp.seekTo(positionMs)
            _state.value = _state.value.copy(positionMs = positionMs)
        } catch (_: Throwable) {
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (true) {
                val mp = player ?: break
                try {
                    if (mp.isPlaying) {
                        _state.value = _state.value.copy(
                            positionMs = mp.currentPosition,
                            durationMs = mp.duration.coerceAtLeast(1)
                        )
                    }
                } catch (_: Throwable) {
                    break
                }
                delay(150)
            }
        }
    }

    fun stop() {
        tickerJob?.cancel()
        tickerJob = null
        try {
            player?.stop()
        } catch (_: Throwable) {
        }
        try {
            player?.release()
        } catch (_: Throwable) {
        }
        player = null
        _state.value = AudioPlaybackState()
    }
}
