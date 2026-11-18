package com.khereba1.app.data

enum class MessageType(val code: String) {
    TEXT("TEXT"),
    IMAGE("IMAGE"),
    VIDEO("VIDEO"),
    AUDIO("AUDIO"),
    FILE("FILE");

    companion object {
        fun fromCode(code: String?): MessageType =
            entries.firstOrNull { it.code == code } ?: TEXT
    }
}

enum class LanPermission(val code: String) {
    VIEWER("VIEWER"),
    EDITOR("EDITOR");

    companion object {
        fun fromCode(code: String?): LanPermission =
            if (code == "VIEWER") VIEWER else EDITOR
    }
}

data class ChatRoom(
    val id: String,
    val title: String,
    val description: String,
    val colorIndex: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val viewerToken: String,
    val editorToken: String,
    val isLanShared: Boolean,
    val lastPreview: String = "",
    val messageCount: Int = 0,
    val isTemporary: Boolean = false,
    val remoteServerUrl: String? = null,
    val remoteToken: String? = null,
    val remotePermission: LanPermission = LanPermission.EDITOR,
    val remoteDisplayName: String = "Guest"
) {
    val isCloudLinked: Boolean get() = !remoteToken.isNullOrBlank()
}

data class ChatMessage(
    val id: String,
    val chatId: String,
    val type: MessageType,
    val text: String,
    val mediaFileName: String?,
    val mediaMime: String?,
    val mediaSize: Long,
    val durationMs: Long,
    val senderName: String,
    val createdAt: Long,
    val updatedAt: Long
)

data class RemoteChatInfo(
    val serverUrl: String,
    val token: String,
    val chatId: String,
    val title: String,
    val description: String,
    val permission: LanPermission
)
