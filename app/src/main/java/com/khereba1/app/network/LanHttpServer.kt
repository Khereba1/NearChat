package com.khereba1.app.network

import android.content.Context
import com.khereba1.app.AppLog
import com.khereba1.app.data.ChatMessage
import com.khereba1.app.data.ChatRoom
import com.khereba1.app.data.LanPermission
import com.khereba1.app.data.NearChatDb
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class LanHttpServer(private val context: Context, preferredPort: Int = 8765) {
    private val db = NearChatDb.get(context)
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null
    var port: Int = preferredPort
        private set

    fun start(): Boolean {
        if (running.get()) return true
        return try {
            val ss = try {
                ServerSocket(port)
            } catch (_: Throwable) {
                ServerSocket(0)
            }
            port = ss.localPort
            serverSocket = ss
            running.set(true)
            val pool = Executors.newCachedThreadPool()
            executor = pool
            pool.execute {
                while (running.get()) {
                    try {
                        val client = ss.accept()
                        pool.execute { handleClient(client) }
                    } catch (e: Throwable) {
                        if (running.get()) {
                            AppLog.log("LAN", "Accept error", e)
                        }
                    }
                }
            }
            AppLog.log("LAN", "Server started on port $port")
            true
        } catch (e: Throwable) {
            AppLog.log("LAN", "Failed to start LAN server", e)
            stop()
            false
        }
    }

    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        serverSocket = null
        try {
            executor?.shutdownNow()
        } catch (_: Throwable) {
        }
        executor = null
        AppLog.log("LAN", "Server stopped")
    }

    private fun handleClient(socket: Socket) {
        socket.use { sock ->
            try {
                sock.soTimeout = 30_000
                val input = sock.getInputStream()
                val output = sock.getOutputStream()

                val requestLine = readLineAscii(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val rawPath = parts[1].substringBefore("?")

                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = readLineAscii(input) ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        headers[line.substring(0, idx).trim().lowercase()] =
                            line.substring(idx + 1).trim()
                    }
                }

                val contentLength = headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val bodyBytes = if (contentLength > 0 && contentLength <= 100 * 1024 * 1024) {
                    readExactBytes(input, contentLength)
                } else {
                    ByteArray(0)
                }

                routeRequest(method, rawPath, headers, bodyBytes, output)
            } catch (e: Throwable) {
                AppLog.log("LAN", "Client handling error", e)
            }
        }
    }

    private fun routeRequest(
        method: String,
        path: String,
        headers: Map<String, String>,
        body: ByteArray,
        out: OutputStream
    ) {
        if (path == "/" || path.isEmpty()) {
            sendHtml(out, 200, "<html><body style='font-family:sans-serif;padding:24px;background:#0D1518;color:#E2E8F0'><h2>NearChat Local Server</h2><p>Open a shared chat link provided by the host.</p></body></html>")
            return
        }

        val segments = path.trim('/').split('/')
        if (segments.size >= 2 && segments[0] == "c") {
            val token = segments[1]
            val match = db.getChatByToken(token)
            if (match == null) {
                sendHtml(out, 404, "<html><body style='font-family:sans-serif;padding:24px;background:#0D1518;color:#F87171'><h3>Chat not found or sharing is disabled.</h3></body></html>")
                return
            }
            sendHtml(out, 200, buildWebUiHtml(token, match.first, match.second))
            return
        }

        if (segments.size >= 3 && segments[0] == "api") {
            val token = segments[1]
            val match = db.getChatByToken(token)
            if (match == null) {
                sendJson(out, 404, JSONObject().put("error", "Chat not shared or invalid code"))
                return
            }
            val (room, perm) = match
            val action = segments[2]

            when {
                method == "GET" && action == "info" -> {
                    val json = JSONObject()
                        .put("chatId", room.id)
                        .put("title", room.title)
                        .put("description", room.description)
                        .put("permission", perm.code)
                    sendJson(out, 200, json)
                }

                method == "GET" && action == "messages" -> {
                    val messages = db.getMessages(room.id)
                    val arr = JSONArray()
                    for (m in messages) {
                        arr.put(messageToJson(m))
                    }
                    sendJson(out, 200, JSONObject().put("messages", arr))
                }

                method == "GET" && action == "media" && segments.size >= 4 -> {
                    val fileName = URLDecoder.decode(segments[3], "UTF-8")
                    val file = db.getMediaFile(room.id, fileName)
                    if (!file.exists() || !file.isFile) {
                        sendText(out, 404, "Not found")
                        return
                    }
                    val mime = guessMimeFromFile(file.name)
                    sendFileWithRange(out, file, mime, headers["range"])
                }

                method == "POST" && action == "text" -> {
                    if (perm != LanPermission.EDITOR) {
                        sendJson(out, 403, JSONObject().put("error", "Viewer mode cannot post"))
                        return
                    }
                    val req = JSONObject(String(body, StandardCharsets.UTF_8))
                    val text = req.optString("text", "").trim()
                    val sender = req.optString("sender", "Guest").trim().ifEmpty { "Guest" }
                    if (text.isEmpty()) {
                        sendJson(out, 400, JSONObject().put("error", "Empty text"))
                        return
                    }
                    val msg = db.insertTextMessage(room.id, text, sender)
                    sendJson(out, 200, JSONObject().put("message", messageToJson(msg)))
                }

                method == "POST" && action == "upload" -> {
                    if (perm != LanPermission.EDITOR) {
                        sendJson(out, 403, JSONObject().put("error", "Viewer mode cannot upload"))
                        return
                    }
                    if (body.isEmpty()) {
                        sendJson(out, 400, JSONObject().put("error", "Empty file"))
                        return
                    }
                    val rawName = headers["x-filename"] ?: "file_${System.currentTimeMillis()}"
                    val fileName = URLDecoder.decode(rawName, "UTF-8")
                    val mime = headers["x-mime"]
                    val sender = URLDecoder.decode(headers["x-sender"] ?: "Guest", "UTF-8")
                        .trim().ifEmpty { "Guest" }
                    val msg = db.saveBytesToChat(room.id, fileName, mime, body, sender)
                    sendJson(out, 200, JSONObject().put("message", messageToJson(msg)))
                }

                method == "POST" && action == "edit" -> {
                    if (perm != LanPermission.EDITOR) {
                        sendJson(out, 403, JSONObject().put("error", "Viewer mode cannot edit"))
                        return
                    }
                    val req = JSONObject(String(body, StandardCharsets.UTF_8))
                    val msgId = req.optString("messageId", "")
                    val newText = req.optString("text", "").trim()
                    if (msgId.isEmpty() || newText.isEmpty()) {
                        sendJson(out, 400, JSONObject().put("error", "Invalid input"))
                        return
                    }
                    db.updateMessageText(msgId, newText)
                    sendJson(out, 200, JSONObject().put("ok", true))
                }

                method == "POST" && action == "delete" -> {
                    if (perm != LanPermission.EDITOR) {
                        sendJson(out, 403, JSONObject().put("error", "Viewer mode cannot delete"))
                        return
                    }
                    val req = JSONObject(String(body, StandardCharsets.UTF_8))
                    val msgId = req.optString("messageId", "")
                    if (msgId.isEmpty()) {
                        sendJson(out, 400, JSONObject().put("error", "Invalid input"))
                        return
                    }
                    db.deleteMessage(msgId)
                    sendJson(out, 200, JSONObject().put("ok", true))
                }

                else -> sendJson(out, 404, JSONObject().put("error", "Unknown endpoint"))
            }
            return
        }

        sendText(out, 404, "Not found")
    }

    private fun messageToJson(m: ChatMessage): JSONObject {
        return JSONObject()
            .put("id", m.id)
            .put("chatId", m.chatId)
            .put("type", m.type.code)
            .put("text", m.text)
            .put("mediaFileName", m.mediaFileName ?: JSONObject.NULL)
            .put("mediaMime", m.mediaMime ?: JSONObject.NULL)
            .put("mediaSize", m.mediaSize)
            .put("durationMs", m.durationMs)
            .put("senderName", m.senderName)
            .put("createdAt", m.createdAt)
            .put("updatedAt", m.updatedAt)
    }

    private fun guessMimeFromFile(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "m4a" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }
    }

    private fun sendFileWithRange(out: OutputStream, file: File, mime: String, rangeHeader: String?) {
        val total = file.length()
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val spec = rangeHeader.removePrefix("bytes=").split("-")
            val start = spec.getOrNull(0)?.toLongOrNull() ?: 0L
            val end = (spec.getOrNull(1)?.toLongOrNull() ?: (total - 1)).coerceAtMost(total - 1)
            if (start in 0..end && end < total) {
                val len = end - start + 1
                val hdr = "HTTP/1.1 206 Partial Content\r\n" +
                    "Content-Type: $mime\r\n" +
                    "Accept-Ranges: bytes\r\n" +
                    "Content-Range: bytes $start-$end/$total\r\n" +
                    "Content-Length: $len\r\n" +
                    "Connection: close\r\n\r\n"
                out.write(hdr.toByteArray(StandardCharsets.UTF_8))
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(start)
                    val buf = ByteArray(16384)
                    var remaining = len
                    while (remaining > 0) {
                        val read = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                        if (read <= 0) break
                        out.write(buf, 0, read)
                        remaining -= read
                    }
                }
                out.flush()
                return
            }
        }

        val hdr = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: $mime\r\n" +
            "Accept-Ranges: bytes\r\n" +
            "Content-Length: $total\r\n" +
            "Connection: close\r\n\r\n"
        out.write(hdr.toByteArray(StandardCharsets.UTF_8))
        file.inputStream().use { it.copyTo(out) }
        out.flush()
    }

    private fun sendJson(out: OutputStream, code: Int, json: JSONObject) {
        val bytes = json.toString().toByteArray(StandardCharsets.UTF_8)
        val hdr = "HTTP/1.1 $code OK\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Cache-Control: no-store\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(hdr.toByteArray(StandardCharsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun sendHtml(out: OutputStream, code: Int, html: String) {
        val bytes = html.toByteArray(StandardCharsets.UTF_8)
        val hdr = "HTTP/1.1 $code OK\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Cache-Control: no-store\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(hdr.toByteArray(StandardCharsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun sendText(out: OutputStream, code: Int, text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        val hdr = "HTTP/1.1 $code OK\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(hdr.toByteArray(StandardCharsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun readLineAscii(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (buf.size() == 0) null else String(buf.toByteArray(), StandardCharsets.UTF_8)
            }
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
        }
        return String(buf.toByteArray(), StandardCharsets.UTF_8)
    }

    private fun readExactBytes(input: InputStream, length: Int): ByteArray {
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(result, offset, length - offset)
            if (read == -1) break
            offset += read
        }
        return if (offset == length) result else result.copyOf(offset)
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun buildWebUiHtml(token: String, room: ChatRoom, perm: LanPermission): String {
        val isEditor = perm == LanPermission.EDITOR
        val safeTitle = escapeHtml(room.title)
        val roleText = if (isEditor) "Editor" else "Viewer"
        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>$safeTitle — NearChat</title>
<style>
  :root { --bg:#0D1518; --card:#162226; --accent:#14B8A6; --text:#E2E8F0; --muted:#94A3B8; --border:#24353B; }
  * { box-sizing:border-box; }
  body { margin:0; font-family:system-ui,-apple-system,sans-serif; background:var(--bg); color:var(--text); display:flex; flex-direction:column; height:100vh; }
  header { padding:12px 16px; background:var(--card); border-bottom:1px solid var(--border); display:flex; align-items:center; justify-content:space-between; gap:8px; }
  .title { font-weight:700; font-size:17px; }
  .badge { font-size:12px; padding:4px 10px; border-radius:99px; background:${if (isEditor) "#0F766E" else "#334155"}; color:#fff; }
  #list { flex:1; overflow-y:auto; padding:14px; display:flex; flex-direction:column; gap:10px; }
  .msg { background:var(--card); border:1px solid var(--border); border-radius:14px; padding:10px 12px; max-width:92%; }
  .meta { font-size:11px; color:var(--muted); margin-bottom:4px; display:flex; justify-content:space-between; gap:10px; }
  .txt { white-space:pre-wrap; word-break:break-word; font-size:15px; line-height:1.45; }
  img.media { max-width:100%; max-height:280px; border-radius:10px; margin-top:6px; display:block; }
  video.media { max-width:100%; max-height:280px; border-radius:10px; margin-top:6px; display:block; }
  audio.media { width:100%; margin-top:6px; }
  .filelink { display:inline-block; margin-top:6px; color:var(--accent); text-decoration:none; font-weight:600; }
  .actions { margin-top:6px; display:flex; gap:8px; }
  .actions button { background:#24353B; color:var(--text); border:none; border-radius:8px; padding:4px 8px; font-size:12px; cursor:pointer; }
  footer { padding:10px; background:var(--card); border-top:1px solid var(--border); display:flex; gap:8px; align-items:center; flex-wrap:wrap; }
  input[type=text] { flex:1; min-width:140px; padding:10px 12px; border-radius:10px; border:1px solid var(--border); background:#0D1518; color:var(--text); font-size:15px; }
  .btn { background:var(--accent); color:#042F2E; border:none; border-radius:10px; padding:10px 14px; font-weight:700; cursor:pointer; }
  .btn-sec { background:#24353B; color:var(--text); }
</style>
</head>
<body>
<header>
  <div>
    <div class="title">$safeTitle</div>
    <div style="font-size:12px;color:var(--muted)">NearChat LAN</div>
  </div>
  <div style="display:flex;gap:8px;align-items:center;">
    <span class="badge">$roleText</span>
    <button class="btn btn-sec" style="padding:6px 10px;font-size:12px" onclick="loadMsgs()">↻</button>
  </div>
</header>
<div id="list"></div>
${if (isEditor) """
<footer>
  <input id="sender" type="text" placeholder="Name" style="max-width:95px" value="Guest">
  <input id="msgInput" type="text" placeholder="Message...">
  <input id="fileInput" type="file" style="display:none" onchange="uploadFile(this.files[0])">
  <button class="btn btn-sec" onclick="document.getElementById('fileInput').click()">+</button>
  <button class="btn" onclick="sendText()">Send</button>
</footer>
""" else ""}
<script>
const TOKEN = "$token";
const IS_EDITOR = $isEditor;
let lastHash = "";

async function loadMsgs() {
  try {
    const res = await fetch('/api/' + TOKEN + '/messages');
    if (!res.ok) return;
    const data = await res.json();
    const raw = JSON.stringify(data.messages);
    if (raw === lastHash) return;
    lastHash = raw;
    render(data.messages || []);
  } catch (e) {}
}

function render(msgs) {
  const list = document.getElementById('list');
  const atBottom = list.scrollHeight - list.scrollTop - list.clientHeight < 80;
  list.innerHTML = '';
  for (const m of msgs) {
    const card = document.createElement('div');
    card.className = 'msg';
    const meta = document.createElement('div');
    meta.className = 'meta';
    const s = document.createElement('span');
    s.textContent = m.senderName || 'Host';
    const t = document.createElement('span');
    t.textContent = new Date(m.createdAt).toLocaleTimeString([], {hour:'2-digit', minute:'2-digit'});
    meta.appendChild(s); meta.appendChild(t);
    card.appendChild(meta);

    if (m.text) {
      const p = document.createElement('div');
      p.className = 'txt';
      p.textContent = m.text;
      card.appendChild(p);
    }
    if (m.mediaFileName) {
      const url = '/api/' + TOKEN + '/media/' + encodeURIComponent(m.mediaFileName);
      if (m.type === 'IMAGE') {
        const img = document.createElement('img'); img.className = 'media'; img.src = url;
        card.appendChild(img);
      } else if (m.type === 'VIDEO') {
        const v = document.createElement('video'); v.className = 'media'; v.src = url; v.controls = true;
        card.appendChild(v);
      } else if (m.type === 'AUDIO') {
        const a = document.createElement('audio'); a.className = 'media'; a.src = url; a.controls = true;
        card.appendChild(a);
      }
      const dl = document.createElement('a');
      dl.className = 'filelink'; dl.href = url; dl.download = m.mediaFileName;
      dl.textContent = '⬇ ' + m.mediaFileName;
      card.appendChild(dl);
    }
    if (IS_EDITOR) {
      const acts = document.createElement('div'); acts.className = 'actions';
      if (m.type === 'TEXT') {
        const eb = document.createElement('button'); eb.textContent = 'Edit';
        eb.onclick = () => editMsg(m.id, m.text);
        acts.appendChild(eb);
      }
      const db = document.createElement('button'); db.textContent = 'Delete';
      db.onclick = () => delMsg(m.id);
      acts.appendChild(db);
      card.appendChild(acts);
    }
    list.appendChild(card);
  }
  if (atBottom) list.scrollTop = list.scrollHeight;
}

async function sendText() {
  const inp = document.getElementById('msgInput');
  const txt = inp.value.trim();
  if (!txt) return;
  const sender = (document.getElementById('sender').value || 'Guest').trim();
  inp.value = '';
  await fetch('/api/' + TOKEN + '/text', {
    method:'POST',
    headers:{'Content-Type':'application/json'},
    body: JSON.stringify({text: txt, sender})
  });
  loadMsgs();
}

async function uploadFile(file) {
  if (!file) return;
  const sender = (document.getElementById('sender').value || 'Guest').trim();
  await fetch('/api/' + TOKEN + '/upload', {
    method:'POST',
    headers:{
      'X-Filename': encodeURIComponent(file.name),
      'X-Mime': file.type || 'application/octet-stream',
      'X-Sender': encodeURIComponent(sender)
    },
    body: file
  });
  document.getElementById('fileInput').value = '';
  loadMsgs();
}

async function editMsg(id, oldText) {
  const next = prompt('Edit message:', oldText);
  if (!next || !next.trim()) return;
  await fetch('/api/' + TOKEN + '/edit', {
    method:'POST',
    headers:{'Content-Type':'application/json'},
    body: JSON.stringify({messageId: id, text: next.trim()})
  });
  loadMsgs();
}

async function delMsg(id) {
  if (!confirm('Delete this item?')) return;
  await fetch('/api/' + TOKEN + '/delete', {
    method:'POST',
    headers:{'Content-Type':'application/json'},
    body: JSON.stringify({messageId: id})
  });
  loadMsgs();
}

loadMsgs();
setInterval(loadMsgs, 3000);
</script>
</body>
</html>
        """.trimIndent()
    }
}
