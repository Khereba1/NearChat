package com.khereba1.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.khereba1.app.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
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
        private const val DB_VERSION = 1

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
                is_lan_shared INTEGER NOT NULL DEFAULT 0
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
        // Initial version
    }

    private fun notifyChanged() {
        _changes.value = System.nanoTime()
    }

    private fun generateToken(prefix: String): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val rnd = SecureRandom()
        val sb = StringBuilder(prefix)
        repeat(6) { sb.append(chars[rnd.nextInt(chars.length)]) }
        return sb.toString()
    }

    fun getAllChats(): List<ChatRoom> = synchronized(dbLock) {
        val list = mutableListOf<ChatRoom>()
        val db = readableDatabase
        db.rawQuery(
            """
            SELECT c.id, c.title, c.description, c.color_index, c.created_at, c.updated_at,
                   c.viewer_token, c.editor_token, c.is_lan_shared,
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
            ORDER BY c.updated_at DESC
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
                        messageCount = cursor.getInt(9),
                        lastPreview = cursor.getString(10) ?: ""
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
                   viewer_token, editor_token, is_lan_shared
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
                isLanShared = c.getInt(8) == 1
            )
        }
    }

    fun getChatByToken(token: String): Pair<ChatRoom, LanPermission>? = synchronized(dbLock) {
        val clean = token.trim()
        if (clean.isEmpty()) return null
        readableDatabase.rawQuery(
            """
            SELECT id, title, description, color_index, created_at, updated_at,
                   viewer_token, editor_token, is_lan_shared
            FROM chats
            WHERE is_lan_shared = 1 AND (viewer_token = ? OR editor_token = ?)
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
                isLanShared = c.getInt(8) == 1
            )
            val perm = if (clean == room.editorToken) LanPermission.EDITOR else LanPermission.VIEWER
            room to perm
        }
    }

    fun getSharedChatsCount(): Int = synchronized(dbLock) {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM chats WHERE is_lan_shared = 1", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun createChat(title: String, description: String = "", colorIndex: Int = 0): ChatRoom {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val room = ChatRoom(
            id = id,
            title = title.trim().ifEmpty { "Untitled" },
            description = description.trim(),
            colorIndex = colorIndex,
            createdAt = now,
            updatedAt = now,
            viewerToken = generateToken("V-"),
            editorToken = generateToken("E-"),
            isLanShared = false
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
                put("is_lan_shared", 0)
            }
            writableDatabase.insert("chats", null, cv)
        }
        notifyChanged()
        return room
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
                put("viewer_token", generateToken("V-"))
                put("editor_token", generateToken("E-"))
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
                .replace(Regex("[^A-Za-z0-9._\\-\\u0600-\\u06FF]"), "_")
            val uniqueName = "${System.currentTimeMillis()}_$baseName"
            val dest = File(getChatMediaDir(chatId), uniqueName)

            cr.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output)
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

    fun saveBytesToChat(
        chatId: String,
        originalName: String,
        mime: String?,
        bytes: ByteArray,
        senderName: String
    ): ChatMessage {
        val safeOriginal = File(originalName).name.replace(Regex("[^A-Za-z0-9._\\-\\u0600-\\u06FF]"), "_")
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
