package com.khereba1.app.media

import android.media.MediaPlayer
import com.khereba1.app.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

data class AudioPlaybackState(
    val activeMessageId: String? = null,
    val isPlaying: Boolean = false
)

object AudioPlayerManager {
    private var player: MediaPlayer? = null
    private val _state = MutableStateFlow(AudioPlaybackState())
    val state: StateFlow<AudioPlaybackState> = _state

    fun toggle(messageId: String, file: File) {
        val current = _state.value
        if (current.activeMessageId == messageId && player != null) {
            try {
                if (current.isPlaying) {
                    player?.pause()
                    _state.value = AudioPlaybackState(messageId, false)
                } else {
                    player?.start()
                    _state.value = AudioPlaybackState(messageId, true)
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
            mp.start()
            player = mp
            _state.value = AudioPlaybackState(messageId, true)
        } catch (e: Throwable) {
            AppLog.log("AUDIO", "Playback failed for ${file.name}", e)
            stop()
        }
    }

    fun stop() {
        try {
            player?.stop()
        } catch (_: Throwable) {
        }
        try {
            player?.release()
        } catch (_: Throwable) {
        }
        player = null
        _state.value = AudioPlaybackState(null, false)
    }
}
