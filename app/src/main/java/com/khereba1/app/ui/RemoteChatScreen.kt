package com.khereba1.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Forward
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.khereba1.app.R
import com.khereba1.app.data.ChatMessage
import com.khereba1.app.data.ChatRoom
import com.khereba1.app.data.LanPermission
import com.khereba1.app.data.MessageType
import com.khereba1.app.network.LanClient
import com.khereba1.app.network.LanDiscovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteChatScreen(
    room: ChatRoom,
    cachedMessages: List<ChatMessage>,
    discoveredHosts: Map<String, String>,
    getMediaFile: (String) -> File,
    onUpdateServerUrl: (String) -> Unit,
    onSyncMessages: (List<ChatMessage>) -> Unit,
    onSaveToGallery: (File, String?) -> Unit,
    onForwardMessage: (ChatMessage) -> Unit,
    onClearCache: () -> Unit = {},
    onDeleteChat: () -> Unit = {},
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val token = room.remoteToken.orEmpty()
    val discoveredUrl = LanDiscovery.resolveFromMap(discoveredHosts, token)
    val activeServerUrl = discoveredUrl ?: room.remoteServerUrl.orEmpty()
    val isEditor = room.remotePermission == LanPermission.EDITOR

    var isOnline by remember { mutableStateOf(discoveredUrl != null) }
    var draft by remember { mutableStateOf("") }
    var transferLabel by remember { mutableStateOf("") }
    var transferProgress by remember { mutableFloatStateOf(-1f) }
    var editTarget by remember { mutableStateOf<ChatMessage?>(null) }
    var viewingImageFile by remember { mutableStateOf<File?>(null) }
    var viewingVideoFile by remember { mutableStateOf<File?>(null) }
    var streamingVideoUrl by remember { mutableStateOf<String?>(null) }
    var viewingPdfFile by remember { mutableStateOf<File?>(null) }
    var viewingTextFile by remember { mutableStateOf<File?>(null) }
    var topMenuOpen by remember { mutableStateOf(false) }
    var downloadBump by remember { mutableStateOf(0) }

    LaunchedEffect(discoveredUrl) {
        if (!discoveredUrl.isNullOrBlank() && discoveredUrl != room.remoteServerUrl) {
            onUpdateServerUrl(discoveredUrl)
        }
    }

    suspend fun refreshFromHost() {
        if (activeServerUrl.isBlank() || token.isBlank()) {
            isOnline = false
            return
        }
        LanClient.fetchMessages(activeServerUrl, token)
            .onSuccess { msgs ->
                isOnline = true
                onSyncMessages(msgs)
            }
            .onFailure {
                isOnline = false
            }
    }

    LaunchedEffect(activeServerUrl, token) {
        while (true) {
            refreshFromHost()
            delay(3500)
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty() || !isOnline || activeServerUrl.isBlank()) return@rememberLauncherForActivityResult
        scope.launch {
            for (uri in uris) {
                transferProgress = 0f
                transferLabel = context.getString(R.string.uploading_progress, 0)
                withContext(Dispatchers.IO) {
                    val cr = context.contentResolver
                    val mime = cr.getType(uri) ?: "application/octet-stream"
                    var name = "file_${System.currentTimeMillis()}"
                    var size = -1L
                    cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            name = c.getString(0) ?: name
                            if (!c.isNull(1)) size = c.getLong(1)
                        }
                    }
                    cr.openInputStream(uri)?.use { inp ->
                        LanClient.uploadStream(
                            serverUrl = activeServerUrl,
                            token = token,
                            fileName = name,
                            mime = mime,
                            sender = room.remoteDisplayName,
                            contentLength = size,
                            inputStream = inp,
                            onProgress = { p ->
                                transferProgress = p
                                transferLabel = context.getString(R.string.uploading_progress, (p * 100).toInt())
                            }
                        )
                    }
                }
                refreshFromHost()
            }
            transferProgress = -1f
            transferLabel = ""
        }
    }

    fun openExternalStream(url: String, mime: String?) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(url), mime ?: "*/*")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.action_open_with)))
        }.onFailure {
            Toast.makeText(context, context.getString(R.string.cannot_open_file), Toast.LENGTH_SHORT).show()
        }
    }

    fun openExternalLocalFile(file: File, mime: String?) {
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.action_open_with)))
        }.onFailure {
            Toast.makeText(context, context.getString(R.string.cannot_open_file), Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                    }
                },
                title = {
                    Column {
                        Text(room.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (isOnline) Color(0xFF22C55E) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                            )
                            Text(
                                text = "${stringResource(if (isOnline) R.string.status_online else R.string.status_offline)} • $token",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { scope.launch { refreshFromHost() } }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null)
                    }
                    Box {
                        IconButton(onClick = { topMenuOpen = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = null)
                        }
                        DropdownMenu(expanded = topMenuOpen, onDismissRequest = { topMenuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.clear_chat_cache)) },
                                onClick = {
                                    topMenuOpen = false
                                    onClearCache()
                                    downloadBump++
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete_chat_action), color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    topMenuOpen = false
                                    onDeleteChat()
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        bottomBar = {
            if (isEditor && isOnline) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                    modifier = Modifier.navigationBarsPadding().imePadding()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Rounded.AttachFile, contentDescription = null)
                        }
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            placeholder = { Text(stringResource(R.string.message_hint)) },
                            shape = RoundedCornerShape(22.dp),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .clickable {
                                    val txt = draft.trim()
                                    if (txt.isNotEmpty()) {
                                        draft = ""
                                        scope.launch {
                                            LanClient.sendText(activeServerUrl, token, txt, room.remoteDisplayName)
                                            refreshFromHost()
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.AutoMirrored.Rounded.Send,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (!isOnline) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Rounded.CloudOff,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.cloud_offline_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (transferProgress >= 0f) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
                    Text(transferLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { transferProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(cachedMessages, key = { "${it.id}_$downloadBump" }) { m ->
                    val localFile = m.mediaFileName?.let { getMediaFile(it) }
                    val isDownloaded = localFile != null && localFile.exists() && localFile.length() > 0L
                    val timeText = remember(m.createdAt) {
                        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(m.createdAt))
                    }
                    val cleanName = m.mediaFileName?.substringAfter('_', m.mediaFileName) ?: ""

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 1.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${m.senderName} • $timeText",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Row {
                                    if (isDownloaded || m.type == MessageType.TEXT) {
                                        IconButton(
                                            onClick = { onForwardMessage(m) },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(Icons.AutoMirrored.Rounded.Forward, contentDescription = null, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                    if (isEditor && isOnline && m.type == MessageType.TEXT) {
                                        IconButton(
                                            onClick = { editTarget = m },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                    if (isEditor && isOnline) {
                                        IconButton(
                                            onClick = {
                                                scope.launch {
                                                    LanClient.deleteMessage(activeServerUrl, token, m.id)
                                                    refreshFromHost()
                                                }
                                            },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }

                            if (m.text.isNotEmpty()) {
                                Text(m.text, style = MaterialTheme.typography.bodyLarge)
                            }

                            if (m.mediaFileName != null) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                if (isDownloaded) Icons.Rounded.DownloadDone else Icons.Rounded.CloudDone,
                                                contentDescription = null,
                                                tint = if (isDownloaded) Color(0xFF22C55E) else MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(cleanName, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text(
                                                    text = "${formatCloudSize(m.mediaSize)} • ${
                                                        stringResource(
                                                            if (isDownloaded) R.string.cloud_file_downloaded
                                                            else R.string.cloud_file_remote
                                                        )
                                                    }",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }

                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            // Play / Stream button
                                            if (isDownloaded && localFile != null) {
                                                Button(
                                                    onClick = {
                                                        when {
                                                            m.type == MessageType.IMAGE -> viewingImageFile = localFile
                                                            m.type == MessageType.VIDEO -> viewingVideoFile = localFile
                                                            isPdfFile(localFile.name) -> viewingPdfFile = localFile
                                                            isTextOrCodeFile(localFile.name) -> viewingTextFile = localFile
                                                            else -> openExternalLocalFile(localFile, m.mediaMime)
                                                        }
                                                    },
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                                    Spacer(Modifier.width(4.dp))
                                                    Text(stringResource(R.string.open_file), maxLines = 1)
                                                }
                                                OutlinedButton(
                                                    onClick = { onSaveToGallery(localFile, m.mediaMime) }
                                                ) {
                                                    Icon(Icons.Rounded.SaveAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                                }
                                            } else if (isOnline && activeServerUrl.isNotBlank()) {
                                                val streamUrl = LanClient.mediaUrl(activeServerUrl, token, m.mediaFileName)
                                                if (m.type == MessageType.VIDEO || m.type == MessageType.AUDIO) {
                                                    Button(
                                                        onClick = {
                                                            if (m.type == MessageType.VIDEO) {
                                                                streamingVideoUrl = streamUrl
                                                            } else {
                                                                openExternalStream(streamUrl, m.mediaMime)
                                                            }
                                                        },
                                                        modifier = Modifier.weight(1f)
                                                    ) {
                                                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                                        Spacer(Modifier.width(4.dp))
                                                        Text(stringResource(R.string.action_stream), maxLines = 1)
                                                    }
                                                }
                                                OutlinedButton(
                                                    onClick = {
                                                        val dest = getMediaFile(m.mediaFileName)
                                                        scope.launch {
                                                            transferProgress = 0f
                                                            transferLabel = context.getString(R.string.downloading_progress, 0)
                                                            LanClient.downloadMediaToFile(
                                                                serverUrl = activeServerUrl,
                                                                token = token,
                                                                fileName = m.mediaFileName,
                                                                destFile = dest,
                                                                onProgress = { p ->
                                                                    transferProgress = p
                                                                    transferLabel = context.getString(R.string.downloading_progress, (p * 100).toInt())
                                                                }
                                                            )
                                                            transferProgress = -1f
                                                            transferLabel = ""
                                                            downloadBump++
                                                        }
                                                    },
                                                    modifier = if (m.type == MessageType.VIDEO || m.type == MessageType.AUDIO) Modifier else Modifier.weight(1f)
                                                ) {
                                                    Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                                    Spacer(Modifier.width(4.dp))
                                                    Text(stringResource(R.string.action_download), maxLines = 1)
                                                }
                                                IconButton(onClick = { openExternalStream(streamUrl, m.mediaMime) }) {
                                                    Icon(Icons.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    viewingImageFile?.let { f ->
        InAppImageViewerDialog(file = f, onDismiss = { viewingImageFile = null })
    }
    viewingVideoFile?.let { f ->
        InAppVideoPlayerDialog(
            file = f,
            onOpenExternal = { openExternalLocalFile(f, "video/*") },
            onDismiss = { viewingVideoFile = null }
        )
    }
    streamingVideoUrl?.let { url ->
        InAppStreamVideoPlayerDialog(
            streamUrl = url,
            onOpenExternal = { openExternalStream(url, "video/*") },
            onDismiss = { streamingVideoUrl = null }
        )
    }
    viewingPdfFile?.let { f ->
        InAppPdfViewerDialog(
            file = f,
            onOpenExternal = { openExternalLocalFile(f, "application/pdf") },
            onSaveToGallery = { onSaveToGallery(f, "application/pdf") },
            onDismiss = { viewingPdfFile = null }
        )
    }
    viewingTextFile?.let { f ->
        InAppTextViewerDialog(
            file = f,
            onOpenExternal = { openExternalLocalFile(f, "text/plain") },
            onSaveToGallery = { onSaveToGallery(f, "text/plain") },
            onDismiss = { viewingTextFile = null }
        )
    }

    editTarget?.let { msg ->
        var text by remember(msg.id) { mutableStateOf(msg.text) }
        AlertDialog(
            onDismissRequest = { editTarget = null },
            title = { Text(stringResource(R.string.edit_message)) },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }) },
            confirmButton = {
                Button(onClick = {
                    val trimmed = text.trim()
                    if (trimmed.isNotEmpty()) {
                        scope.launch {
                            LanClient.editMessage(activeServerUrl, token, msg.id, trimmed)
                            refreshFromHost()
                        }
                    }
                    editTarget = null
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { editTarget = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

private fun formatCloudSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}
