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
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.khereba1.app.data.ChatMessage
import com.khereba1.app.data.ChatRoom
import com.khereba1.app.data.MessageType
import com.khereba1.app.data.NearChatDb
import com.khereba1.app.media.AudioPlayerManager
import com.khereba1.app.media.VoiceRecorder
import com.khereba1.app.network.BluetoothChatManager
import com.khereba1.app.network.LanClient
import com.khereba1.app.network.LanDiscovery
import com.khereba1.app.network.LanShareService
import com.khereba1.app.network.WifiProxyServer
import com.khereba1.app.ui.BluetoothChatScreen
import com.khereba1.app.ui.ChatScreen
import com.khereba1.app.ui.HomeScreen
import com.khereba1.app.ui.MediaAttachBottomSheet
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

private data class ForwardRequest(
    val sourceChatId: String,
    val message: ChatMessage
)

private sealed interface NavRoute {
    data object Home : NavRoute
    data class LocalChat(val chatId: String) : NavRoute
    data class CloudChat(val chatId: String) : NavRoute
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
            val prefs = remember { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
            var themeMode by remember { mutableStateOf(prefs.getString("theme", "system") ?: "system") }
            NearChatTheme(themeMode = themeMode) {
                val scope = rememberCoroutineScope()
                val dbVersion by db.changes.collectAsState()
                val lanState by LanShareService.state.collectAsState()
                val routerState by WifiProxyServer.state.collectAsState()
                val discoveredHosts by LanDiscovery.discoveredHosts.collectAsState()
                val btState by btManager.state.collectAsState()

                DisposableEffect(Unit) {
                    LanDiscovery.startListening()
                    onDispose { LanDiscovery.stopListening() }
                }

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
                var showBluetooth by remember { mutableStateOf(false) }
                var showNetworkHub by remember { mutableStateOf(false) }
                var isRecording by remember { mutableStateOf(false) }
                var forwardRequest by remember { mutableStateOf<ForwardRequest?>(null) }
                var showAttachSheet by remember { mutableStateOf(false) }
                var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

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
                    scope.launch(Dispatchers.IO) {
                        WifiProxyServer.start(this@MainActivity)
                    }
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

                val galleryPicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.GetMultipleContents()
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

                val takePhotoLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.TakePicture()
                ) { ok ->
                    val uri = pendingCameraUri
                    val chatId = activeChatId
                    pendingCameraUri = null
                    if (ok && uri != null && chatId != null) {
                        scope.launch(Dispatchers.IO) {
                            db.saveUriToChat(chatId, uri, getString(R.string.me_sender))
                        }
                    }
                }

                val captureVideoLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CaptureVideo()
                ) { ok ->
                    val uri = pendingCameraUri
                    val chatId = activeChatId
                    pendingCameraUri = null
                    if (ok && uri != null && chatId != null) {
                        scope.launch(Dispatchers.IO) {
                            db.saveUriToChat(chatId, uri, getString(R.string.me_sender))
                        }
                    }
                }

                fun createTempCaptureUri(ext: String): Uri {
                    val dir = File(cacheDir, "captures").apply { mkdirs() }
                    val f = File(dir, "capture_${System.currentTimeMillis()}.$ext")
                    return FileProvider.getUriForFile(this@MainActivity, "${packageName}.fileprovider", f)
                }

                fun launchQuickShare(share: PendingShare? = null) {
                    scope.launch {
                        val room = withContext(Dispatchers.IO) {
                            val existingTemp = db.getAllChats().firstOrNull { it.isTemporary }
                            val r = existingTemp ?: db.createChat(
                                title = getString(R.string.quick_share_title),
                                colorIndex = 0,
                                isTemporary = true,
                                isLanShared = true
                            )
                            if (!r.isLanShared) {
                                db.updateLanSharing(r.id, true)
                            }
                            if (share != null) {
                                savePendingShare(db, r.id, share)
                            }
                            r
                        }
                        LanShareService.start(this@MainActivity)
                        if (!WifiProxyServer.state.value.isRunning) {
                            val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_FINE_LOCATION)
                            } else {
                                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                            }
                            routerPermLauncher.launch(perms)
                        }
                        showNetworkHub = false
                        showBluetooth = false
                        activeChatId = room.id
                    }
                }

                fun handleSaveToGallery(file: File, mime: String?) {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            db.exportToGalleryOrDownloads(file, mime)
                        }
                        Toast.makeText(
                            this@MainActivity,
                            getString(if (ok) R.string.saved_to_gallery else R.string.save_gallery_failed),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                LaunchedEffect(Unit) {
                    val sharedCount = withContext(Dispatchers.IO) { db.getSharedChatsCount() }
                    if (sharedCount > 0 && !lanState.isRunning) {
                        LanShareService.start(this@MainActivity)
                    }
                }

                val activeRoomObj = activeChatId?.let { id -> chats.firstOrNull { it.id == id } }
                val currentRoute: NavRoute = when {
                    showNetworkHub -> NavRoute.NetworkHub
                    showBluetooth -> NavRoute.BluetoothChat
                    activeRoomObj != null && activeRoomObj.isCloudLinked -> NavRoute.CloudChat(activeRoomObj.id)
                    activeRoomObj != null -> NavRoute.LocalChat(activeRoomObj.id)
                    else -> NavRoute.Home
                }

                BackHandler(
                    enabled = currentRoute != NavRoute.Home ||
                        showAttachSheet ||
                        forwardRequest != null ||
                        pendingShareState.value != null
                ) {
                    when {
                        showAttachSheet -> showAttachSheet = false
                        forwardRequest != null -> forwardRequest = null
                        pendingShareState.value != null -> pendingShareState.value = null
                        showNetworkHub -> showNetworkHub = false
                        showBluetooth -> {
                            btManager.disconnect()
                            showBluetooth = false
                        }
                        activeChatId != null -> {
                            if (isRecording) {
                                recorder.cancel()
                                isRecording = false
                            }
                            AudioPlayerManager.stop()
                            activeChatId = null
                        }
                    }
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
                                    val keep = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                                        getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("keep_wifi_group", false)
                                    scope.launch(Dispatchers.IO) {
                                        runCatching { WifiProxyServer.stop(keepGroup = keep) }
                                    }
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

                        is NavRoute.CloudChat -> {
                            val cloudRoom = chats.firstOrNull { it.id == route.chatId } ?: return@AnimatedContent
                            var cachedMessages by remember(cloudRoom.id) { mutableStateOf<List<ChatMessage>>(emptyList()) }
                            LaunchedEffect(dbVersion, cloudRoom.id) {
                                cachedMessages = withContext(Dispatchers.IO) { db.getMessages(cloudRoom.id) }
                            }
                            RemoteChatScreen(
                                room = cloudRoom,
                                cachedMessages = cachedMessages,
                                discoveredHosts = discoveredHosts,
                                getMediaFile = { fileName -> db.getMediaFile(cloudRoom.id, fileName) },
                                onUpdateServerUrl = { newUrl ->
                                    scope.launch(Dispatchers.IO) { db.updateCloudServerUrl(cloudRoom.id, newUrl) }
                                },
                                onSyncMessages = { remoteMsgs ->
                                    scope.launch(Dispatchers.IO) { db.syncRemoteMessages(cloudRoom.id, remoteMsgs) }
                                },
                                onSaveToGallery = { f, mime -> handleSaveToGallery(f, mime) },
                                onForwardMessage = { msg -> forwardRequest = ForwardRequest(cloudRoom.id, msg) },
                                onClearCache = {
                                    scope.launch(Dispatchers.IO) { db.clearChatLocalMedia(cloudRoom.id) }
                                    Toast.makeText(this@MainActivity, getString(R.string.cache_cleared_toast), Toast.LENGTH_SHORT).show()
                                },
                                onDeleteChat = {
                                    activeChatId = null
                                    scope.launch(Dispatchers.IO) { db.deleteChat(cloudRoom.id) }
                                },
                                onBack = { activeChatId = null }
                            )
                        }

                        is NavRoute.LocalChat -> {
                            val activeRoom: ChatRoom = chats.firstOrNull { it.id == route.chatId } ?: return@AnimatedContent
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
                                routerState = routerState,
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
                                    showAttachSheet = true
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
                                },
                                onEndQuickShare = {
                                    val roomIdToDelete = activeRoom.id
                                    val keep = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                                        prefs.getBoolean("keep_wifi_group", false)
                                    if (isRecording) {
                                        recorder.cancel()
                                        isRecording = false
                                    }
                                    AudioPlayerManager.stop()
                                    activeChatId = null
                                    scope.launch(Dispatchers.IO) {
                                        runCatching { WifiProxyServer.stop(keepGroup = keep) }
                                        runCatching { db.deleteChat(roomIdToDelete) }
                                        val count = db.getSharedChatsCount()
                                        if (count == 0) {
                                            withContext(Dispatchers.Main) {
                                                runCatching { LanShareService.stop(this@MainActivity) }
                                            }
                                        }
                                    }
                                },
                                onSaveToGallery = { f, mime -> handleSaveToGallery(f, mime) },
                                onForwardMessage = { msg -> forwardRequest = ForwardRequest(activeRoom.id, msg) },
                                onDeleteChat = {
                                    activeChatId = null
                                    scope.launch(Dispatchers.IO) { db.deleteChat(activeRoom.id) }
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
                                themeMode = themeMode,
                                discoveredHosts = discoveredHosts,
                                onStartQuickShare = { launchQuickShare() },
                                getChatCacheBytes = { id -> db.getChatMediaBytes(id) },
                                onClearChatCache = { id ->
                                    scope.launch(Dispatchers.IO) { db.clearChatLocalMedia(id) }
                                    Toast.makeText(this@MainActivity, getString(R.string.cache_cleared_toast), Toast.LENGTH_SHORT).show()
                                },
                                onClearAllCloudCache = {
                                    scope.launch(Dispatchers.IO) {
                                        chats.filter { it.isCloudLinked }.forEach { db.clearChatLocalMedia(it.id) }
                                    }
                                    Toast.makeText(this@MainActivity, getString(R.string.cache_cleared_toast), Toast.LENGTH_SHORT).show()
                                },
                                onChangeTheme = { mode ->
                                    prefs.edit().putString("theme", mode).apply()
                                    themeMode = mode
                                },
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
                                                val cloudRoom = withContext(Dispatchers.IO) {
                                                    db.upsertCloudChat(info, displayName)
                                                }
                                                activeChatId = cloudRoom.id
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

                val incomingShare = pendingShareState.value
                LaunchedEffect(incomingShare, chats) {
                    if (incomingShare != null) {
                        val activeTemp = chats.firstOrNull { it.isTemporary }
                        if (activeTemp != null) {
                            pendingShareState.value = null
                            launchQuickShare(incomingShare)
                            Toast.makeText(
                                this@MainActivity,
                                getString(R.string.saved_to_chat, activeTemp.title),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }

                if (incomingShare != null && chats.none { it.isTemporary }) {
                    val share = incomingShare
                    SharePickerDialog(
                        chats = chats.filter { !it.isTemporary },
                        onQuickShare = {
                            pendingShareState.value = null
                            launchQuickShare(share)
                        },
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

                if (showAttachSheet && activeChatId != null) {
                    val targetId = activeChatId!!
                    MediaAttachBottomSheet(
                        onSendUris = { uris ->
                            scope.launch(Dispatchers.IO) {
                                val me = getString(R.string.me_sender)
                                for (u in uris) {
                                    db.saveUriToChat(targetId, u, me)
                                }
                            }
                        },
                        onOpenSystemGallery = { galleryPicker.launch("*/*") },
                        onOpenCameraPhoto = {
                            runCatching {
                                val uri = createTempCaptureUri("jpg")
                                pendingCameraUri = uri
                                takePhotoLauncher.launch(uri)
                            }
                        },
                        onOpenCameraVideo = {
                            runCatching {
                                val uri = createTempCaptureUri("mp4")
                                pendingCameraUri = uri
                                captureVideoLauncher.launch(uri)
                            }
                        },
                        onOpenFileManager = { attachmentPicker.launch(arrayOf("*/*")) },
                        onDismiss = { showAttachSheet = false }
                    )
                }

                forwardRequest?.let { req ->
                    SharePickerDialog(
                        chats = chats.filter { it.id != req.sourceChatId && !it.isTemporary },
                        titleText = stringResource(R.string.forward_to_title),
                        onPickChat = { target ->
                            forwardRequest = null
                            scope.launch {
                                val me = getString(R.string.me_sender)
                                val ok = withContext(Dispatchers.IO) {
                                    if (target.isCloudLinked) {
                                        val tok = target.remoteToken.orEmpty()
                                        val url = LanDiscovery.resolveToken(tok) ?: target.remoteServerUrl.orEmpty()
                                        if (url.isBlank() || tok.isBlank()) {
                                            false
                                        } else if (req.message.type == MessageType.TEXT || req.message.mediaFileName.isNullOrBlank()) {
                                            LanClient.sendText(url, tok, req.message.text, target.remoteDisplayName).isSuccess
                                        } else {
                                            val f = db.getMediaFile(req.sourceChatId, req.message.mediaFileName)
                                            if (!f.exists()) false else {
                                                f.inputStream().use { inp ->
                                                    LanClient.uploadStream(
                                                        serverUrl = url,
                                                        token = tok,
                                                        fileName = f.name.substringAfter('_', f.name),
                                                        mime = req.message.mediaMime ?: "application/octet-stream",
                                                        sender = target.remoteDisplayName,
                                                        contentLength = f.length(),
                                                        inputStream = inp
                                                    ).isSuccess
                                                }
                                            }
                                        }
                                    } else {
                                        db.forwardMessageToLocalChat(req.sourceChatId, req.message, target.id, me) != null
                                    }
                                }
                                Toast.makeText(
                                    this@MainActivity,
                                    if (ok) getString(R.string.forwarded_ok, target.title)
                                    else getString(R.string.connection_error),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onDismiss = { forwardRequest = null }
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
