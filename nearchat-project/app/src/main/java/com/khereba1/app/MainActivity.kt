package com.khereba1.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.khereba1.app.data.ChatRoom
import com.khereba1.app.data.MessageType
import com.khereba1.app.data.NearChatDb
import com.khereba1.app.data.RemoteChatInfo
import com.khereba1.app.media.AudioPlayerManager
import com.khereba1.app.media.VoiceRecorder
import com.khereba1.app.network.LanClient
import com.khereba1.app.network.LanShareService
import com.khereba1.app.ui.ChatScreen
import com.khereba1.app.ui.HomeScreen
import com.khereba1.app.ui.RemoteChatScreen
import com.khereba1.app.ui.SharePickerDialog
import com.khereba1.app.ui.theme.NearChatTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class PendingShare(
    val text: String? = null,
    val uris: List<Uri> = emptyList()
)

class MainActivity : ComponentActivity() {
    private val pendingShareState = mutableStateOf<PendingShare?>(null)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        extractShareIntent(intent)

        val db = NearChatDb.get(this)
        val recorder = VoiceRecorder(this)

        setContent {
            NearChatTheme {
                val scope = rememberCoroutineScope()
                val dbVersion by db.changes.collectAsState()
                val lanState by LanShareService.state.collectAsState()

                val chats = remember(dbVersion) { db.getAllChats() }
                val storageBytes = remember(dbVersion) { db.getTotalStorageBytes() }

                var activeChatId by remember { mutableStateOf<String?>(null) }
                var remoteSession by remember { mutableStateOf<Pair<RemoteChatInfo, String>?>(null) }
                var isRecording by remember { mutableStateOf(false) }

                val prefs = remember { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
                var currentLang by remember { mutableStateOf(prefs.getString("lang", "system") ?: "system") }

                val micLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    if (granted && activeChatId != null) {
                        val outFile = File(db.getChatMediaDir(activeChatId!!), "${System.currentTimeMillis()}_voice.m4a")
                        if (recorder.start(outFile)) {
                            isRecording = true
                        }
                    } else if (!granted) {
                        Toast.makeText(this, getString(R.string.mic_permission_needed), Toast.LENGTH_SHORT).show()
                    }
                }

                val notifLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { _ -> }

                val attachmentPicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenMultipleDocuments()
                ) { uris ->
                    val chatId = activeChatId ?: return@rememberLauncherForActivityResult
                    if (uris.isEmpty()) return@rememberLauncherForActivityResult
                    scope.launch(Dispatchers.IO) {
                        val me = getString(R.string.me_sender)
                        for (u in uris) {
                            db.saveUriToChat(chatId, u, me)
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    if (db.getSharedChatsCount() > 0 && !lanState.isRunning) {
                        LanShareService.start(this@MainActivity)
                    }
                }

                val activeRoom: ChatRoom? = remember(chats, activeChatId) {
                    activeChatId?.let { id -> chats.firstOrNull { it.id == id } }
                }

                when {
                    remoteSession != null -> {
                        val (info, name) = remoteSession!!
                        RemoteChatScreen(
                            info = info,
                            displayName = name,
                            onBack = { remoteSession = null }
                        )
                    }

                    activeRoom != null -> {
                        val messages = remember(dbVersion, activeRoom.id) { db.getMessages(activeRoom.id) }
                        ChatScreen(
                            room = activeRoom,
                            messages = messages,
                            lanState = lanState,
                            isRecording = isRecording,
                            getMediaFile = { fileName -> db.getMediaFile(activeRoom.id, fileName) },
                            onBack = {
                                if (isRecording) {
                                    recorder.cancel()
                                    isRecording = false
                                }
                                AudioPlayerManager.stop()
                                activeChatId = null
                            },
                            onSendText = { txt ->
                                db.insertTextMessage(activeRoom.id, txt, getString(R.string.me_sender))
                            },
                            onPickAttachments = {
                                attachmentPicker.launch(arrayOf("*/*"))
                            },
                            onStartRecording = {
                                val hasPerm = ContextCompat.checkSelfPermission(
                                    this@MainActivity,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (hasPerm) {
                                    val outFile = File(
                                        db.getChatMediaDir(activeRoom.id),
                                        "${System.currentTimeMillis()}_voice.m4a"
                                    )
                                    if (recorder.start(outFile)) {
                                        isRecording = true
                                    }
                                } else {
                                    micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            onStopRecording = {
                                isRecording = false
                                val res = recorder.stop()
                                if (res != null) {
                                    val (file, dur) = res
                                    db.insertMediaMessage(
                                        chatId = activeRoom.id,
                                        type = MessageType.AUDIO,
                                        fileName = file.name,
                                        mime = "audio/mp4",
                                        size = file.length(),
                                        durationMs = dur,
                                        senderName = getString(R.string.me_sender)
                                    )
                                }
                            },
                            onEditMessage = { msgId, newText ->
                                db.updateMessageText(msgId, newText)
                            },
                            onDeleteMessage = { msgId ->
                                db.deleteMessage(msgId)
                            },
                            onToggleLanShare = { enabled ->
                                if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    if (ContextCompat.checkSelfPermission(
                                            this@MainActivity,
                                            Manifest.permission.POST_NOTIFICATIONS
                                        ) != PackageManager.PERMISSION_GRANTED
                                    ) {
                                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                }
                                db.updateLanSharing(activeRoom.id, enabled)
                                if (db.getSharedChatsCount() > 0) {
                                    LanShareService.start(this@MainActivity)
                                } else {
                                    LanShareService.stop(this@MainActivity)
                                }
                            },
                            onRegenerateTokens = {
                                db.regenerateTokens(activeRoom.id)
                            }
                        )
                    }

                    else -> {
                        HomeScreen(
                            chats = chats,
                            lanState = lanState,
                            storageBytes = storageBytes,
                            currentLang = currentLang,
                            onOpenChat = { room -> activeChatId = room.id },
                            onCreateChat = { title, desc, colorIdx ->
                                val created = db.createChat(title, desc, colorIdx)
                                activeChatId = created.id
                            },
                            onRenameChat = { id, newTitle -> db.renameChat(id, newTitle) },
                            onDeleteChat = { id ->
                                db.deleteChat(id)
                                if (db.getSharedChatsCount() == 0) {
                                    LanShareService.stop(this@MainActivity)
                                }
                            },
                            onJoinLan = { urlOrLink, code, displayName ->
                                val parsed = LanClient.parseLinkOrInputs(urlOrLink, code)
                                if (parsed == null) {
                                    Toast.makeText(this, getString(R.string.connection_error), Toast.LENGTH_SHORT).show()
                                } else {
                                    scope.launch {
                                        val res = LanClient.fetchInfo(parsed.first, parsed.second)
                                        res.onSuccess { info ->
                                            remoteSession = info to displayName
                                        }.onFailure {
                                            Toast.makeText(
                                                this@MainActivity,
                                                getString(R.string.connection_error),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                }
                            },
                            onChangeLanguage = { lang ->
                                prefs.edit().putString("lang", lang).apply()
                                currentLang = lang
                                recreate()
                            }
                        )
                    }
                }

                pendingShareState.value?.let { share ->
                    SharePickerDialog(
                        chats = chats,
                        onPickChat = { room ->
                            pendingShareState.value = null
                            scope.launch {
                                savePendingShare(db, room.id, share)
                                activeChatId = room.id
                                Toast.makeText(
                                    this@MainActivity,
                                    getString(R.string.saved_to_chat, room.title),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onCreateNewAndSave = { title ->
                            pendingShareState.value = null
                            scope.launch {
                                val room = db.createChat(title)
                                savePendingShare(db, room.id, share)
                                activeChatId = room.id
                                Toast.makeText(
                                    this@MainActivity,
                                    getString(R.string.saved_to_chat, room.title),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onDismiss = { pendingShareState.value = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractShareIntent(intent)
    }

    private fun extractShareIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                @Suppress("DEPRECATION")
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (!text.isNullOrBlank() || uri != null) {
                    pendingShareState.value = PendingShare(
                        text = text,
                        uris = if (uri != null) listOf(uri) else emptyList()
                    )
                }
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                @Suppress("DEPRECATION")
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
                if (!text.isNullOrBlank() || uris.isNotEmpty()) {
                    pendingShareState.value = PendingShare(text = text, uris = uris)
                }
            }
        }
    }

    private suspend fun savePendingShare(db: NearChatDb, chatId: String, share: PendingShare) {
        withContext(Dispatchers.IO) {
            val me = getString(R.string.me_sender)
            if (!share.text.isNullOrBlank()) {
                db.insertTextMessage(chatId, share.text, me)
            }
            for (u in share.uris) {
                db.saveUriToChat(chatId, u, me)
            }
        }
    }
}
