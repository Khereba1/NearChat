package com.khereba1.app.network

import com.khereba1.app.data.ChatMessage
import com.khereba1.app.data.LanPermission
import com.khereba1.app.data.MessageType
import com.khereba1.app.data.RemoteChatInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
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
        if (idx > 0) {
            val base = trimmed.substring(0, idx)
            val token = trimmed.substring(idx + cMarker.length).substringBefore("/").substringBefore("?").trim()
            return if (token.isEmpty()) null else base to token
        }
        val code = rawCode.trim().ifEmpty {
            // Allow entering ONLY the 6-char code into the first field when Auto-Discovery found it
            if (!trimmed.contains(".") && !trimmed.contains(":") && trimmed.length in 5..12) trimmed else ""
        }
        if (code.isEmpty()) return null
        val autoUrl = LanDiscovery.resolveToken(code)
        val rawHost = if (trimmed.equals(code, ignoreCase = true)) "" else trimmed
        val base = when {
            rawHost.startsWith("http://") || rawHost.startsWith("https://") -> rawHost
            rawHost.isNotEmpty() -> "http://$rawHost"
            !autoUrl.isNullOrBlank() -> autoUrl
            else -> ""
        }
        return if (base.isEmpty()) null else base to code
    }

    fun mediaUrl(serverUrl: String, token: String, fileName: String): String {
        val enc = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
        return "${serverUrl.trimEnd('/')}/api/$token/media/$enc"
    }

    suspend fun fetchInfo(serverUrl: String, token: String): Result<RemoteChatInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL("${serverUrl.trimEnd('/')}/api/$token/info").openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText() ?: ""
            if (code !in 200..299) error("HTTP $code: $text")
            val json = JSONObject(text)
            RemoteChatInfo(
                serverUrl = serverUrl.trimEnd('/'),
                token = token.trim().uppercase(),
                chatId = json.getString("chatId"),
                title = json.getString("title"),
                description = json.optString("description", ""),
                permission = if (json.optString("permission") == "EDITOR") LanPermission.EDITOR else LanPermission.VIEWER
            )
        }
    }

    suspend fun fetchMessages(serverUrl: String, token: String): Result<List<ChatMessage>> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL("${serverUrl.trimEnd('/')}/api/$token/messages").openConnection() as HttpURLConnection).apply {
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
        postJson("${serverUrl.trimEnd('/')}/api/$token/text", JSONObject().put("text", text).put("sender", sender))

    suspend fun editMessage(serverUrl: String, token: String, messageId: String, newText: String): Result<Unit> =
        postJson("${serverUrl.trimEnd('/')}/api/$token/edit", JSONObject().put("messageId", messageId).put("text", newText))

    suspend fun deleteMessage(serverUrl: String, token: String, messageId: String): Result<Unit> =
        postJson("${serverUrl.trimEnd('/')}/api/$token/delete", JSONObject().put("messageId", messageId))

    /** Streams a file of ANY size directly to the Host without loading it into RAM. */
    suspend fun uploadStream(
        serverUrl: String,
        token: String,
        fileName: String,
        mime: String,
        sender: String,
        contentLength: Long,
        inputStream: InputStream,
        onProgress: (Float) -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL("${serverUrl.trimEnd('/')}/api/$token/upload").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 120_000
                setRequestProperty("X-Filename", URLEncoder.encode(fileName, "UTF-8"))
                setRequestProperty("X-Mime", mime)
                setRequestProperty("X-Sender", URLEncoder.encode(sender, "UTF-8"))
                if (contentLength > 0L) {
                    setFixedLengthStreamingMode(contentLength)
                } else {
                    setChunkedStreamingMode(262_144)
                }
            }
            conn.outputStream.use { out ->
                val buf = ByteArray(262_144)
                var sent = 0L
                while (true) {
                    val n = inputStream.read(buf)
                    if (n == -1) break
                    out.write(buf, 0, n)
                    sent += n
                    if (contentLength > 0L) {
                        onProgress((sent.toFloat() / contentLength.toFloat()).coerceIn(0f, 1f))
                    }
                }
                out.flush()
            }
            val code = conn.responseCode
            if (code !in 200..299) error("Upload failed: HTTP $code")
        }
    }

    /** Downloads a remote media file directly to local cache for offline viewing. */
    suspend fun downloadMediaToFile(
        serverUrl: String,
        token: String,
        fileName: String,
        destFile: File,
        onProgress: (Float) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val url = mediaUrl(serverUrl, token, fileName)
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 60_000
            }
            val code = conn.responseCode
            if (code !in 200..299) error("Download failed: HTTP $code")
            val total = conn.contentLengthLong
            destFile.parentFile?.mkdirs()
            val tmp = File(destFile.parentFile, "${destFile.name}.part")
            conn.inputStream.use { inp ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(262_144)
                    var received = 0L
                    while (true) {
                        val n = inp.read(buf)
                        if (n == -1) break
                        out.write(buf, 0, n)
                        received += n
                        if (total > 0L) {
                            onProgress((received.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                        }
                    }
                    out.flush()
                }
            }
            if (destFile.exists()) destFile.delete()
            tmp.renameTo(destFile)
            destFile
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
