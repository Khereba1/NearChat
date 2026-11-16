package com.khereba1.app.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import com.khereba1.app.AppLog
import java.io.File

class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var startTimeMs: Long = 0L

    fun start(outputFile: File): Boolean {
        stopSilently()
        return try {
            outputFile.parentFile?.mkdirs()
            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioEncodingBitRate(96_000)
            mr.setAudioSamplingRate(44_100)
            mr.setOutputFile(outputFile.absolutePath)
            mr.prepare()
            mr.start()
            recorder = mr
            currentFile = outputFile
            startTimeMs = System.currentTimeMillis()
            true
        } catch (e: Throwable) {
            AppLog.log("RECORDER", "Failed to start recording", e)
            stopSilently()
            false
        }
    }

    fun stop(): Pair<File, Long>? {
        val file = currentFile ?: return null
        val duration = (System.currentTimeMillis() - startTimeMs).coerceAtLeast(500L)
        return try {
            recorder?.stop()
            recorder?.release()
            recorder = null
            currentFile = null
            if (file.exists() && file.length() > 0) file to duration else null
        } catch (e: Throwable) {
            AppLog.log("RECORDER", "Failed to stop recording", e)
            stopSilently()
            null
        }
    }

    fun cancel() {
        val file = currentFile
        stopSilently()
        try {
            file?.delete()
        } catch (_: Throwable) {
        }
    }

    private fun stopSilently() {
        try {
            recorder?.release()
        } catch (_: Throwable) {
        }
        recorder = null
        currentFile = null
    }
}
