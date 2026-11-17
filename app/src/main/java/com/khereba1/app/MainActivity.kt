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
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.khereba1.app.data.ChatMessage
import com.khereba1.app.data.ChatRoom
import com.khereba1.app.data.MessageType
import com.khereba1.app.data.NearChatDb
import com.khereba1.app.data.RemoteChatInfo
import com.khereba1.app.media.AudioPlayerManager
import com.khereba1.app.media.VoiceRecorder
import com.khereba1.app.network.BluetoothChatManager
import com.khereba1.app.network.LanClient
import com.khereba1.app.network.LanShareService
import com.khereba1.app.network.WifiProxyServer
import com.khereba1.app.ui.BluetoothChatScreen
import com.khereba1.app.ui.ChatScreen
import com.khereba1.app.ui.HomeScreen
import com.khereba1.app.ui.NetworkHubScreen
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

private sealed interface NavRoute {
    data object Home : NavRoute
    data class LocalChat(val chatId: String) : NavRoute
    data class RemoteChat(val info: RemoteChatInfo, val displayName: String) : NavRoute
    data object BluetoothChat : NavRoute
    data object NetworkHub : NavRoute
}

class MainActivity : ComponentActivity() {
    private val pendingShareState = mutableStateOf<PendingShare?>(null)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        extractShareIntent(intent)

        val db = NearChatDb.get(this)
        val recorder = VoiceRecorder(this)
        val btManager = BluetoothChatManager(this)

        setContent {
            NearChatTheme {
                val scope = rememberCoroutineScope()
                val dbVersion by db.changes.collectAsState()
                val lanState by LanShareService.state.collectAsState()
                val routerState by WifiProxyServer.state.collectAsState()
                val btState by btManager.state.collectAsState()

                var chats by remember { mutableStateOf<List<ChatRoom>>(emptyList()) }
                var storageBytes by remember { mutableLongStateOf(0L) }
                LaunchedEffect(dbVersion) {
                    val (loadedChats, loadedBytes) = withContext(Dispatchers.IO) {
                        db.getAllChats() to db.getTotalStorageBytes()
                    }
                    chats = loadedChats
                    storageBytes = loadedBytes
                }

                var activeChatId by remember { mutableStateOf<String?>(null) }
                var remoteSession by remember { mutableStateOf<Pair<RemoteChatInfo, String>?>(null) }
                var showBluetooth by remember { mutableStateOf(false) }
                var showNetworkHub by remember { mutableStateOf(false) }
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

                val routerPermLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { _ ->
                    WifiProxyServer.start(this@MainActivity)
                }

                val btPermLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { results ->
                    if (results.values.all { it }) {
                        showBluetooth = true
                    } else {
                        Toast.makeText(this, getString(R.string.bt_permission_needed), Toast.LENGTH_SHORT).show()
                    }
                }

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
                    val sharedCount = withContext(Dispatchers.IO) { db.getSharedChatsCount() }
                    if (sharedCount > 0 && !lanState.isRunning) {
                        LanShareService.start(this@MainActivity)
                    }
                }

                val currentRoute: NavRoute = when {
                    showNetworkHub -> NavRoute.NetworkHub
                    showBluetooth -> NavRoute.BluetoothChat
                    remoteSession != null -> NavRoute.RemoteChat(remoteSession!!.first, remoteSession!!.second)
                    activeChatId != null && chats.any { it.id == activeChatId } -> NavRoute.LocalChat(activeChatId!!)
                    else -> NavRoute.Home
                }

                AnimatedContent(
                    targetState = currentRoute,
                    transitionSpec = {
                        if (targetState != NavRoute.Home) {
                            (slideInHorizontally(tween(220)) { it / 3 } + fadeIn(tween(180))) togetherWith
                                (slideOutHorizontally(tween(220)) { -it / 4 } + fadeOut(tween(140)))
                        } else {
                            (slideInHorizontally(tween(220)) { -it / 3 } + fadeIn(tween(180))) togetherWith
                                (slideOutHorizontally(tween(220)) { it / 3 } + fadeOut(tween(140)))
                        }
                    },
                    label = "screen_nav"
                ) { route ->
                    when (route) {
                        is NavRoute.NetworkHub -> {
                            NetworkHubScreen(
                                routerState = routerState,
                                onStartRouter = {
                                    val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_FINE_LOCATION)
                                    } else {
                                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                                    }
                                    routerPermLauncher.launch(perms)
                                },
                                onStopRouter = {
                                    val keep = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q &&
                                        getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("keep_wifi_group", false)
                                    WifiProxyServer.stop(keepGroup = keep)
                                },
                                onBack = { showNetworkHub = false }
                            )
                        }

                        is NavRoute.BluetoothChat -> {
                            val paired = remember(btState.status) { btManager.getPairedDevices() }
                            BluetoothChatScreen(
                                btState = btState,
                                pairedDevices = paired,
                                onBack = {
                                    btManager.disconnect()
                                    showBluetooth = false
                                },
                                onHost = { btManager.startHosting() },
                                onConnect = { dev -> btManager.connectToDevice(dev) },
                                onSend = { txt -> btManager.sendMessage(txt) },
                                onDisconnect = { btManager.disconnect() }
                            )
                        }

                        is NavRoute.RemoteChat -> {
                            RemoteChatScreen(
                                info = route.info,
                                displayName = route.displayName,
                                onBack = { remoteSession = null }
                            )
                        }

                        is NavRoute.LocalChat -> {
                            val activeRoom: ChatRoom = chats.first { it.id == route.chatId }
                            var messages by remember(activeRoom.id) { mutableStateOf<List<ChatMessage>>(emptyList()) }
                            LaunchedEffect(dbVersion, activeRoom.id) {
                                messages = withContext(Dispatchers.IO) {
                                    db.getMessages(activeRoom.id)
                                }
                            }
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
                                    scope.launch(Dispatchers.IO) {
                                        db.insertTextMessage(activeRoom.id, txt, getString(R.string.me_sender))
                                    }
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
                                        scope.launch(Dispatchers.IO) {
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
                                    }
                                },
                                onEditMessage = { msgId, newText ->
                                    scope.launch(Dispatchers.IO) {
                                        db.updateMessageText(msgId, newText)
                                    }
                                },
                                onDeleteMessage = { msgId ->
                                    scope.launch(Dispatchers.IO) {
                                        db.deleteMessage(msgId)
                                    }
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
                                    scope.launch(Dispatchers.IO) {
                                        db.updateLanSharing(activeRoom.id, enabled)
                                        val count = db.getSharedChatsCount()
                                        withContext(Dispatchers.Main) {
                                            if (count > 0) {
                                                LanShareService.start(this@MainActivity)
                                            } else if (!WifiProxyServer.state.value.isRunning) {
                                                LanShareService.stop(this@MainActivity)
                                            }
                                        }
                                    }
                                },
                                onRegenerateTokens = {
                                    scope.launch(Dispatchers.IO) {
                                        db.regenerateTokens(activeRoom.id)
                                    }
                                }
                            )
                        }

                        is NavRoute.Home -> {
                            HomeScreen(
                                chats = chats,
                                lanState = lanState,
                                routerState = routerState,
                                storageBytes = storageBytes,
                                currentLang = currentLang,
                                onOpenChat = { room -> activeChatId = room.id },
                                onCreateChat = { title, desc, colorIdx ->
                                    scope.launch {
                                        val created = withContext(Dispatchers.IO) {
                                            db.createChat(title, desc, colorIdx)
                                        }
                                        activeChatId = created.id
                                    }
                                },
                                onRenameChat = { id, newTitle ->
                                    scope.launch(Dispatchers.IO) { db.renameChat(id, newTitle) }
                                },
                                onDeleteChat = { id ->
                                    scope.launch(Dispatchers.IO) {
                                        db.deleteChat(id)
                                        val count = db.getSharedChatsCount()
                                        if (count == 0 && !WifiProxyServer.state.value.isRunning) {
                                            withContext(Dispatchers.Main) {
                                                LanShareService.stop(this@MainActivity)
                                            }
                                        }
                                    }
                                },
                                onJoinLan = { urlOrLink, code, displayName ->
                                    val parsed = LanClient.parseLinkOrInputs(urlOrLink, code)
                                    if (parsed == null) {
                                        Toast.makeText(this@MainActivity, getString(R.string.connection_error), Toast.LENGTH_SHORT).show()
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
                                onOpenBluetoothChat = {
                                    if (!btManager.isAvailable()) {
                                        Toast.makeText(this@MainActivity, getString(R.string.bt_not_supported), Toast.LENGTH_SHORT).show()
                                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        val needed = arrayOf(
                                            Manifest.permission.BLUETOOTH_CONNECT,
                                            Manifest.permission.BLUETOOTH_SCAN
                                        )
                                        val allGranted = needed.all {
                                            ContextCompat.checkSelfPermission(this@MainActivity, it) == PackageManager.PERMISSION_GRANTED
                                        }
                                        if (allGranted) showBluetooth = true else btPermLauncher.launch(needed)
                                    } else {
                                        showBluetooth = true
                                    }
                                },
                                onOpenNetworkHub = { showNetworkHub = true },
                                onChangeLanguage = { lang ->
                                    prefs.edit().putString("lang", lang).apply()
                                    currentLang = lang
                                    recreate()
                                }
                            )
                        }
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
                                val room = withContext(Dispatchers.IO) { db.createChat(title) }
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
