package com.khereba1.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.khereba1.app.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.SecureRandom
import java.util.UUID

class NearChatDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    private val appContext = context.applicationContext
    private val mediaRoot = File(appContext.filesDir, "media").apply { mkdirs() }
    private val dbLock = Any()

    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes

    companion object {
        private const val DB_NAME = "nearchat.db"
        private const val DB_VERSION = 2

        @Volatile
        private var instance: NearChatDb? = null

        fun get(context: Context): NearChatDb {
            return instance ?: synchronized(this) {
                instance ?: NearChatDb(context.applicationContext).also { instance = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE chats (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                color_index INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                viewer_token TEXT NOT NULL,
                editor_token TEXT NOT NULL,
                is_lan_shared INTEGER NOT NULL DEFAULT 0,
                is_temporary INTEGER NOT NULL DEFAULT 0,
                remote_server_url TEXT,
                remote_token TEXT,
                remote_permission TEXT NOT NULL DEFAULT 'EDITOR',
                remote_display_name TEXT NOT NULL DEFAULT 'Guest'
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE messages (
                id TEXT PRIMARY KEY,
                chat_id TEXT NOT NULL,
                type TEXT NOT NULL,
                text TEXT NOT NULL DEFAULT '',
                media_file_name TEXT,
                media_mime TEXT,
                media_size INTEGER NOT NULL DEFAULT 0,
                duration_ms INTEGER NOT NULL DEFAULT 0,
                sender_name TEXT NOT NULL DEFAULT 'Me',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_messages_chat ON messages(chat_id, created_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            runCatching { db.execSQL("ALTER TABLE chats ADD COLUMN is_temporary INTEGER NOT NULL DEFAULT 0") }
            runCatching { db.execSQL("ALTER TABLE chats ADD COLUMN remote_server_url TEXT") }
            runCatching { db.execSQL("ALTER TABLE chats ADD COLUMN remote_token TEXT") }
            runCatching { db.execSQL("ALTER TABLE chats ADD COLUMN remote_permission TEXT NOT NULL DEFAULT 'EDITOR'") }
            runCatching { db.execSQL("ALTER TABLE chats ADD COLUMN remote_display_name TEXT NOT NULL DEFAULT 'Guest'") }
        }
    }

    private fun notifyChanged() {
        _changes.value = System.nanoTime()
    }

    /** Short permanent 6-char code: V + 5 chars for Viewer, E + 5 chars for Editor. */
    private fun generateToken(prefix: String): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val rnd = SecureRandom()
        val sb = StringBuilder(prefix)
        repeat(5) { sb.append(chars[rnd.nextInt(chars.length)]) }
        return sb.toString()
    }

    fun getAllChats(): List<ChatRoom> = synchronized(dbLock) {
        val list = mutableListOf<ChatRoom>()
        val db = readableDatabase
        db.rawQuery(
            """
            SELECT c.id, c.title, c.description, c.color_index, c.created_at, c.updated_at,
                   c.viewer_token, c.editor_token, c.is_lan_shared,
                   c.is_temporary, c.remote_server_url, c.remote_token,
                   c.remote_permission, c.remote_display_name,
                   (SELECT COUNT(*) FROM messages m WHERE m.chat_id = c.id) AS msg_count,
                   (SELECT CASE
                        WHEN m.type = 'TEXT' THEN m.text
                        WHEN m.type = 'AUDIO' THEN 'Voice Note'
                        WHEN m.type = 'IMAGE' THEN COALESCE(m.media_file_name, 'Photo')
                        WHEN m.type = 'VIDEO' THEN COALESCE(m.media_file_name, 'Video')
                        ELSE COALESCE(m.media_file_name, 'File')
                    END
                    FROM messages m WHERE m.chat_id = c.id ORDER BY m.created_at DESC LIMIT 1) AS last_preview
            FROM chats c
            ORDER BY c.is_temporary DESC, c.updated_at DESC
            """.trimIndent(),
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    ChatRoom(
                        id = cursor.getString(0),
                        title = cursor.getString(1),
                        description = cursor.getString(2),
                        colorIndex = cursor.getInt(3),
                        createdAt = cursor.getLong(4),
                        updatedAt = cursor.getLong(5),
                        viewerToken = cursor.getString(6),
                        editorToken = cursor.getString(7),
                        isLanShared = cursor.getInt(8) == 1,
                        isTemporary = cursor.getInt(9) == 1,
                        remoteServerUrl = cursor.getString(10),
                        remoteToken = cursor.getString(11),
                        remotePermission = LanPermission.fromCode(cursor.getString(12)),
                        remoteDisplayName = cursor.getString(13) ?: "Guest",
                        messageCount = cursor.getInt(14),
                        lastPreview = cursor.getString(15) ?: ""
                    )
                )
            }
        }
        list
    }

    fun getChatById(chatId: String): ChatRoom? = synchronized(dbLock) {
        readableDatabase.rawQuery(
            """
            SELECT id, title, description, color_index, created_at, updated_at,
                   viewer_token, editor_token, is_lan_shared,
                   is_temporary, remote_server_url, remote_token,
                   remote_permission, remote_display_name
            FROM chats WHERE id = ?
            """.trimIndent(),
            arrayOf(chatId)
        ).use { c ->
            if (!c.moveToFirst()) return null
            ChatRoom(
                id = c.getString(0),
                title = c.getString(1),
                description = c.getString(2),
                colorIndex = c.getInt(3),
                createdAt = c.getLong(4),
                updatedAt = c.getLong(5),
                viewerToken = c.getString(6),
                editorToken = c.getString(7),
                isLanShared = c.getInt(8) == 1,
                isTemporary = c.getInt(9) == 1,
                remoteServerUrl = c.getString(10),
                remoteToken = c.getString(11),
                remotePermission = LanPermission.fromCode(c.getString(12)),
                remoteDisplayName = c.getString(13) ?: "Guest"
            )
        }
    }

    fun getChatByToken(token: String): Pair<ChatRoom, LanPermission>? = synchronized(dbLock) {
        val clean = token.trim().uppercase()
        if (clean.isEmpty()) return null
        readableDatabase.rawQuery(
            """
            SELECT id, title, description, color_index, created_at, updated_at,
                   viewer_token, editor_token, is_lan_shared,
                   is_temporary, remote_server_url, remote_token,
                   remote_permission, remote_display_name
            FROM chats
            WHERE is_lan_shared = 1 AND (UPPER(viewer_token) = ? OR UPPER(editor_token) = ?)
            LIMIT 1
            """.trimIndent(),
            arrayOf(clean, clean)
        ).use { c ->
            if (!c.moveToFirst()) return null
            val room = ChatRoom(
                id = c.getString(0),
                title = c.getString(1),
                description = c.getString(2),
                colorIndex = c.getInt(3),
                createdAt = c.getLong(4),
                updatedAt = c.getLong(5),
                viewerToken = c.getString(6),
                editorToken = c.getString(7),
                isLanShared = c.getInt(8) == 1,
                isTemporary = c.getInt(9) == 1,
                remoteServerUrl = c.getString(10),
                remoteToken = c.getString(11),
                remotePermission = LanPermission.fromCode(c.getString(12)),
                remoteDisplayName = c.getString(13) ?: "Guest"
            )
            val perm = if (clean == room.editorToken.uppercase()) LanPermission.EDITOR else LanPermission.VIEWER
            room to perm
        }
    }

    fun getSharedChatsCount(): Int = synchronized(dbLock) {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM chats WHERE is_lan_shared = 1", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun getSharedTokens(): List<String> = synchronized(dbLock) {
        val tokens = mutableListOf<String>()
        readableDatabase.rawQuery(
            "SELECT viewer_token, editor_token FROM chats WHERE is_lan_shared = 1",
            null
        ).use { c ->
            while (c.moveToNext()) {
                c.getString(0)?.takeIf { it.isNotBlank() }?.let { tokens.add(it) }
                c.getString(1)?.takeIf { it.isNotBlank() }?.let { tokens.add(it) }
            }
        }
        tokens
    }

    fun createChat(
        title: String,
        description: String = "",
        colorIndex: Int = 0,
        isTemporary: Boolean = false,
        isLanShared: Boolean = false
    ): ChatRoom {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val room = ChatRoom(
            id = id,
            title = title.trim().ifEmpty { "Untitled" },
            description = description.trim(),
            colorIndex = colorIndex,
            createdAt = now,
            updatedAt = now,
            viewerToken = generateToken("V"),
            editorToken = generateToken("E"),
            isLanShared = isLanShared,
            isTemporary = isTemporary
        )
        synchronized(dbLock) {
            val cv = ContentValues().apply {
                put("id", room.id)
                put("title", room.title)
                put("description", room.description)
                put("color_index", room.colorIndex)
                put("created_at", room.createdAt)
                put("updated_at", room.updatedAt)
                put("viewer_token", room.viewerToken)
                put("editor_token", room.editorToken)
                put("is_lan_shared", if (room.isLanShared) 1 else 0)
                put("is_temporary", if (room.isTemporary) 1 else 0)
            }
            writableDatabase.insert("chats", null, cv)
        }
        notifyChanged()
        return room
    }

    /** Creates or updates a persistent linked Cloud Chat stored on a remote Host/Tablet. */
    fun upsertCloudChat(info: RemoteChatInfo, displayName: String): ChatRoom {
        val cleanToken = info.token.trim().uppercase()
        val cleanName = displayName.trim().ifEmpty { "Guest" }
        val now = System.currentTimeMillis()
        synchronized(dbLock) {
            var existingId: String? = null
            readableDatabase.rawQuery(
                "SELECT id FROM chats WHERE UPPER(remote_token) = ? LIMIT 1",
                arrayOf(cleanToken)
            ).use { c ->
                if (c.moveToFirst()) existingId = c.getString(0)
            }

            if (existingId != null) {
                val cv = ContentValues().apply {
                    put("title", info.title.trim().ifEmpty { "Cloud Chat" })
                    put("description", info.description.trim())
                    put("remote_server_url", info.serverUrl)
                    put("remote_permission", info.permission.code)
                    put("remote_display_name", cleanName)
                    put("updated_at", now)
                }
                writableDatabase.update("chats", cv, "id = ?", arrayOf(existingId))
                notifyChanged()
                return getChatById(existingId!!)!!
            }

            val id = "cloud_" + UUID.randomUUID().toString()
            val cv = ContentValues().apply {
                put("id", id)
                put("title", info.title.trim().ifEmpty { "Cloud Chat" })
                put("description", info.description.trim())
                put("color_index", 1)
                put("created_at", now)
                put("updated_at", now)
                put("viewer_token", cleanToken)
                put("editor_token", cleanToken)
                put("is_lan_shared", 0)
                put("is_temporary", 0)
                put("remote_server_url", info.serverUrl)
                put("remote_token", cleanToken)
                put("remote_permission", info.permission.code)
                put("remote_display_name", cleanName)
            }
            writableDatabase.insert("chats", null, cv)
            notifyChanged()
            return getChatById(id)!!
        }
    }

    fun updateCloudServerUrl(chatId: String, serverUrl: String) {
        synchronized(dbLock) {
            val cv = ContentValues().apply { put("remote_server_url", serverUrl) }
            writableDatabase.update("chats", cv, "id = ?", arrayOf(chatId))
        }
    }

    /** Syncs remote message metadata into local SQLite cache while keeping downloaded files on disk. */
    fun syncRemoteMessages(chatId: String, remoteMessages: List<ChatMessage>) {
        synchronized(dbLock) {
            val db = writableDatabase
            db.beginTransaction()
            try {
                db.delete("messages", "chat_id = ?", arrayOf(chatId))
                var latest = 0L
                for (m in remoteMessages) {
                    val cv = ContentValues().apply {
                        put("id", m.id)
                        put("chat_id", chatId)
                        put("type", m.type.code)
                        put("text", m.text)
                        put("media_file_name", m.mediaFileName)
                        put("media_mime", m.mediaMime)
                        put("media_size", m.mediaSize)
                        put("duration_ms", m.durationMs)
                        put("sender_name", m.senderName)
                        put("created_at", m.createdAt)
                        put("updated_at", m.updatedAt)
                    }
                    db.insertWithOnConflict("messages", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                    if (m.createdAt > latest) latest = m.createdAt
                }
                if (latest > 0L) {
                    val chatCv = ContentValues().apply { put("updated_at", latest) }
                    db.update("chats", chatCv, "id = ?", arrayOf(chatId))
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        notifyChanged()
    }

    fun renameChat(chatId: String, newTitle: String) {
        val clean = newTitle.trim()
        if (clean.isEmpty()) return
        synchronized(dbLock) {
            val cv = ContentValues().apply {
                put("title", clean)
                put("updated_at", System.currentTimeMillis())
            }
            writableDatabase.update("chats", cv, "id = ?", arrayOf(chatId))
        }
        notifyChanged()
    }

    fun updateLanSharing(chatId: String, enabled: Boolean) {
        synchronized(dbLock) {
            val cv = ContentValues().apply {
                put("is_lan_shared", if (enabled) 1 else 0)
            }
            writableDatabase.update("chats", cv, "id = ?", arrayOf(chatId))
        }
        notifyChanged()
    }

    fun regenerateTokens(chatId: String) {
        synchronized(dbLock) {
            val cv = ContentValues().apply {
                put("viewer_token", generateToken("V"))
                put("editor_token", generateToken("E"))
            }
            writableDatabase.update("chats", cv, "id = ?", arrayOf(chatId))
        }
        notifyChanged()
    }

    fun deleteChat(chatId: String) {
        synchronized(dbLock) {
            writableDatabase.delete("messages", "chat_id = ?", arrayOf(chatId))
            writableDatabase.delete("chats", "id = ?", arrayOf(chatId))
        }
        try {
            File(mediaRoot, chatId).deleteRecursively()
        } catch (e: Throwable) {
            AppLog.log("DB", "Failed to delete media dir for $chatId", e)
        }
        notifyChanged()
    }

    fun clearChatLocalMedia(chatId: String) {
        try {
            File(mediaRoot, chatId).deleteRecursively()
        } catch (e: Throwable) {
            AppLog.log("DB", "Failed to clear media dir for $chatId", e)
        }
        notifyChanged()
    }

    fun getChatMediaBytes(chatId: String): Long {
        return try {
            val dir = File(mediaRoot, chatId)
            if (!dir.exists()) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } catch (_: Throwable) {
            0L
        }
    }

    fun getMessages(chatId: String): List<ChatMessage> = synchronized(dbLock) {
        val list = mutableListOf<ChatMessage>()
        readableDatabase.rawQuery(
            """
            SELECT id, chat_id, type, text, media_file_name, media_mime, media_size,
                   duration_ms, sender_name, created_at, updated_at
            FROM messages
            WHERE chat_id = ?
            ORDER BY created_at ASC
            """.trimIndent(),
            arrayOf(chatId)
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    ChatMessage(
                        id = c.getString(0),
                        chatId = c.getString(1),
                        type = MessageType.fromCode(c.getString(2)),
                        text = c.getString(3),
                        mediaFileName = c.getString(4),
                        mediaMime = c.getString(5),
                        mediaSize = c.getLong(6),
                        durationMs = c.getLong(7),
                        senderName = c.getString(8),
                        createdAt = c.getLong(9),
                        updatedAt = c.getLong(10)
                    )
                )
            }
        }
        list
    }

    fun insertTextMessage(chatId: String, text: String, senderName: String = "Me"): ChatMessage {
        val now = System.currentTimeMillis()
        val msg = ChatMessage(
            id = UUID.randomUUID().toString(),
            chatId = chatId,
            type = MessageType.TEXT,
            text = text.trim(),
            mediaFileName = null,
            mediaMime = null,
            mediaSize = 0L,
            durationMs = 0L,
            senderName = senderName.trim().ifEmpty { "Me" },
            createdAt = now,
            updatedAt = now
        )
        insertMessageRow(msg)
        return msg
    }

    fun insertMediaMessage(
        chatId: String,
        type: MessageType,
        fileName: String,
        mime: String?,
        size: Long,
        durationMs: Long = 0L,
        caption: String = "",
        senderName: String = "Me"
    ): ChatMessage {
        val now = System.currentTimeMillis()
        val msg = ChatMessage(
            id = UUID.randomUUID().toString(),
            chatId = chatId,
            type = type,
            text = caption.trim(),
            mediaFileName = fileName,
            mediaMime = mime,
            mediaSize = size,
            durationMs = durationMs,
            senderName = senderName.trim().ifEmpty { "Me" },
            createdAt = now,
            updatedAt = now
        )
        insertMessageRow(msg)
        return msg
    }

    private fun insertMessageRow(msg: ChatMessage) {
        synchronized(dbLock) {
            val cv = ContentValues().apply {
                put("id", msg.id)
                put("chat_id", msg.chatId)
                put("type", msg.type.code)
                put("text", msg.text)
                put("media_file_name", msg.mediaFileName)
                put("media_mime", msg.mediaMime)
                put("media_size", msg.mediaSize)
                put("duration_ms", msg.durationMs)
                put("sender_name", msg.senderName)
                put("created_at", msg.createdAt)
                put("updated_at", msg.updatedAt)
            }
            writableDatabase.insert("messages", null, cv)
            val chatCv = ContentValues().apply { put("updated_at", msg.createdAt) }
            writableDatabase.update("chats", chatCv, "id = ?", arrayOf(msg.chatId))
        }
        notifyChanged()
    }

    fun updateMessageText(messageId: String, newText: String) {
        val clean = newText.trim()
        synchronized(dbLock) {
            val cv = ContentValues().apply {
                put("text", clean)
                put("updated_at", System.currentTimeMillis())
            }
            writableDatabase.update("messages", cv, "id = ?", arrayOf(messageId))
        }
        notifyChanged()
    }

    fun deleteMessage(messageId: String) {
        var chatId: String? = null
        var fileName: String? = null
        synchronized(dbLock) {
            readableDatabase.rawQuery(
                "SELECT chat_id, media_file_name FROM messages WHERE id = ?",
                arrayOf(messageId)
            ).use { c ->
                if (c.moveToFirst()) {
                    chatId = c.getString(0)
                    fileName = c.getString(1)
                }
            }
            writableDatabase.delete("messages", "id = ?", arrayOf(messageId))
        }
        if (chatId != null && fileName != null) {
            try {
                File(getChatMediaDir(chatId!!), fileName!!).delete()
            } catch (_: Throwable) {
            }
        }
        notifyChanged()
    }

    /** Copies a message (text or local media file) into another local chat. */
    fun forwardMessageToLocalChat(sourceChatId: String, msg: ChatMessage, targetChatId: String, senderName: String): ChatMessage? {
        if (msg.type == MessageType.TEXT || msg.mediaFileName.isNullOrBlank()) {
            if (msg.text.isBlank()) return null
            return insertTextMessage(targetChatId, msg.text, senderName)
        }
        val srcFile = getMediaFile(sourceChatId, msg.mediaFileName)
        if (!srcFile.exists()) return null
        val uniqueName = "${System.currentTimeMillis()}_${srcFile.name.substringAfter('_', srcFile.name)}"
        val dest = File(getChatMediaDir(targetChatId), uniqueName)
        return try {
            srcFile.inputStream().use { inp ->
                FileOutputStream(dest).use { out -> inp.copyTo(out, 262_144) }
            }
            insertMediaMessage(
                chatId = targetChatId,
                type = msg.type,
                fileName = uniqueName,
                mime = msg.mediaMime,
                size = dest.length(),
                durationMs = msg.durationMs,
                caption = msg.text,
                senderName = senderName
            )
        } catch (e: Throwable) {
            AppLog.log("DB", "Forward failed", e)
            null
        }
    }

    fun getChatMediaDir(chatId: String): File {
        val dir = File(mediaRoot, chatId)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getMediaFile(chatId: String, fileName: String): File {
        val safeName = File(fileName).name
        return File(getChatMediaDir(chatId), safeName)
    }

    fun saveUriToChat(chatId: String, uri: Uri, senderName: String = "Me"): ChatMessage? {
        return try {
            val cr = appContext.contentResolver
            val mime = cr.getType(uri) ?: "application/octet-stream"
            var displayName: String? = null
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    displayName = c.getString(0)
                }
            }
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            val baseName = (displayName ?: "item_${System.currentTimeMillis()}${if (ext != null) ".$ext" else ""}")
                .replace(Regex("[^A-Za-z0-9._\-\u0600-\u06FF]"), "_")
            val uniqueName = "${System.currentTimeMillis()}_$baseName"
            val dest = File(getChatMediaDir(chatId), uniqueName)

            cr.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output, 262_144)
                }
            } ?: return null

            val type = detectMessageType(mime, uniqueName)
            insertMediaMessage(
                chatId = chatId,
                type = type,
                fileName = uniqueName,
                mime = mime,
                size = dest.length(),
                senderName = senderName
            )
        } catch (e: Throwable) {
            AppLog.log("DB", "Failed to save shared URI: $uri", e)
            null
        }
    }

    /** Streams incoming HTTP upload directly to disk in 256 KB chunks with zero file-size limit. */
    fun saveStreamToChat(
        chatId: String,
        originalName: String,
        mime: String?,
        input: InputStream,
        contentLength: Long,
        senderName: String
    ): ChatMessage {
        val safeOriginal = File(originalName).name.replace(Regex("[^A-Za-z0-9._\-\u0600-\u06FF]"), "_")
        val uniqueName = "${System.currentTimeMillis()}_$safeOriginal"
        val dest = File(getChatMediaDir(chatId), uniqueName)
        FileOutputStream(dest).use { out ->
            val buf = ByteArray(262_144)
            var remaining = contentLength
            while (remaining > 0L) {
                val toRead = minOf(buf.size.toLong(), remaining).toInt()
                val read = input.read(buf, 0, toRead)
                if (read == -1) break
                out.write(buf, 0, read)
                remaining -= read
            }
        }
        val resolvedMime = mime?.takeIf { it.isNotBlank() } ?: guessMime(uniqueName)
        val type = detectMessageType(resolvedMime, uniqueName)
        return insertMediaMessage(
            chatId = chatId,
            type = type,
            fileName = uniqueName,
            mime = resolvedMime,
            size = dest.length(),
            senderName = senderName
        )
    }

    fun saveBytesToChat(
        chatId: String,
        originalName: String,
        mime: String?,
        bytes: ByteArray,
        senderName: String
    ): ChatMessage {
        val safeOriginal = File(originalName).name.replace(Regex("[^A-Za-z0-9._\-\u0600-\u06FF]"), "_")
        val uniqueName = "${System.currentTimeMillis()}_$safeOriginal"
        val dest = File(getChatMediaDir(chatId), uniqueName)
        dest.writeBytes(bytes)
        val resolvedMime = mime?.takeIf { it.isNotBlank() } ?: guessMime(uniqueName)
        val type = detectMessageType(resolvedMime, uniqueName)
        return insertMediaMessage(
            chatId = chatId,
            type = type,
            fileName = uniqueName,
            mime = resolvedMime,
            size = dest.length(),
            senderName = senderName
        )
    }

    /** Saves a local file to the Android Gallery (Pictures/Movies) or Downloads/NearChat. */
    fun exportToGalleryOrDownloads(file: File, mime: String?): Boolean {
        if (!file.exists()) return false
        val resolvedMime = mime?.takeIf { it.isNotBlank() } ?: guessMime(file.name)
        val displayName = file.name.substringAfter('_', file.name)
        val type = detectMessageType(resolvedMime, file.name)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val (collection, relPath) = when (type) {
                    MessageType.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to "${Environment.DIRECTORY_PICTURES}/NearChat"
                    MessageType.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "${Environment.DIRECTORY_MOVIES}/NearChat"
                    MessageType.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to "${Environment.DIRECTORY_MUSIC}/NearChat"
                    else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI to "${Environment.DIRECTORY_DOWNLOADS}/NearChat"
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, resolvedMime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                }
                val cr = appContext.contentResolver
                val uri = cr.insert(collection, values) ?: return false
                cr.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { inp -> inp.copyTo(out, 262_144) }
                } ?: return false
                true
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "NearChat").apply { mkdirs() }
                val outFile = File(dir, displayName)
                file.inputStream().use { inp -> FileOutputStream(outFile).use { out -> inp.copyTo(out, 262_144) } }
                true
            }
        } catch (e: Throwable) {
            AppLog.log("DB", "Export to gallery failed", e)
            false
        }
    }

    fun getTotalStorageBytes(): Long {
        return try {
            mediaRoot.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } catch (_: Throwable) {
            0L
        }
    }

    private fun guessMime(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    fun detectMessageType(mime: String?, fileName: String): MessageType {
        val m = (mime ?: "").lowercase()
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when {
            m.startsWith("image/") || ext in listOf("jpg", "jpeg", "png", "webp", "gif", "heic") -> MessageType.IMAGE
            m.startsWith("video/") || ext in listOf("mp4", "mkv", "webm", "mov", "3gp") -> MessageType.VIDEO
            m.startsWith("audio/") || ext in listOf("m4a", "mp3", "wav", "ogg", "aac", "opus") -> MessageType.AUDIO
            else -> MessageType.FILE
        }
    }
}
