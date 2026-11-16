package com.khereba1.app.network

import com.khereba1.app.data.ChatMessage
import com.khereba1.app.data.LanPermission
import com.khereba1.app.data.MessageType
import com.khereba1.app.data.RemoteChatInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object LanClient {

    fun parseLinkOrInputs(rawUrlOrLink: String, rawCode: String): Pair<String, String>? {
        val trimmed = rawUrlOrLink.trim().trimEnd('/')
        val cMarker = "/c/"
        val idx = trimmed.indexOf(cMarker)
        return if (idx > 0) {
            val base = trimmed.substring(0, idx)
            val token = trimmed.substring(idx + cMarker.length).substringBefore("/").substringBefore("?").trim()
            if (token.isEmpty()) null else base to token
        } else {
            val code = rawCode.trim()
            val base = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
            if (base.isEmpty() || code.isEmpty()) null else base to code
        }
    }

    suspend fun fetchInfo(serverUrl: String, token: String): Result<RemoteChatInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL("$serverUrl/api/$token/info").openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText() ?: ""
            if (code !in 200..299) error("HTTP $code: $text")
            val json = JSONObject(text)
            RemoteChatInfo(
                serverUrl = serverUrl,
                token = token,
                chatId = json.getString("chatId"),
                title = json.getString("title"),
                description = json.optString("description", ""),
                permission = if (json.optString("permission") == "EDITOR") LanPermission.EDITOR else LanPermission.VIEWER
            )
        }
    }

    suspend fun fetchMessages(serverUrl: String, token: String): Result<List<ChatMessage>> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL("$serverUrl/api/$token/messages").openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 8000
            }
            val code = conn.responseCode
            val text = conn.inputStream.bufferedReader().readText()
            if (code !in 200..299) error("HTTP $code")
            val arr = JSONObject(text).getJSONArray("messages")
            val list = mutableListOf<ChatMessage>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    ChatMessage(
                        id = o.getString("id"),
                        chatId = o.getString("chatId"),
                        type = MessageType.fromCode(o.getString("type")),
                        text = o.optString("text", ""),
                        mediaFileName = if (o.isNull("mediaFileName")) null else o.optString("mediaFileName"),
                        mediaMime = if (o.isNull("mediaMime")) null else o.optString("mediaMime"),
                        mediaSize = o.optLong("mediaSize", 0L),
                        durationMs = o.optLong("durationMs", 0L),
                        senderName = o.optString("senderName", "Host"),
                        createdAt = o.optLong("createdAt", 0L),
                        updatedAt = o.optLong("updatedAt", 0L)
                    )
                )
            }
            list
        }
    }

    suspend fun sendText(serverUrl: String, token: String, text: String, sender: String): Result<Unit> =
        postJson("$serverUrl/api/$token/text", JSONObject().put("text", text).put("sender", sender))

    suspend fun editMessage(serverUrl: String, token: String, messageId: String, newText: String): Result<Unit> =
        postJson("$serverUrl/api/$token/edit", JSONObject().put("messageId", messageId).put("text", newText))

    suspend fun deleteMessage(serverUrl: String, token: String, messageId: String): Result<Unit> =
        postJson("$serverUrl/api/$token/delete", JSONObject().put("messageId", messageId))

    suspend fun uploadBytes(
        serverUrl: String,
        token: String,
        fileName: String,
        mime: String,
        sender: String,
        bytes: ByteArray
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL("$serverUrl/api/$token/upload").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 8000
                readTimeout = 30_000
                setRequestProperty("X-Filename", URLEncoder.encode(fileName, "UTF-8"))
                setRequestProperty("X-Mime", mime)
                setRequestProperty("X-Sender", URLEncoder.encode(sender, "UTF-8"))
                setFixedLengthStreamingMode(bytes.size)
            }
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            if (code !in 200..299) error("Upload failed: HTTP $code")
        }
    }

    private suspend fun postJson(url: String, body: JSONObject): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 5000
                readTimeout = 8000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use {
                it.write(body.toString())
            }
            val code = conn.responseCode
            if (code !in 200..299) error("HTTP $code")
        }
    }
}
