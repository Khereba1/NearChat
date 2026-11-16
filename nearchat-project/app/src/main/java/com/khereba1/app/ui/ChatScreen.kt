package com.khereba1.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.khereba1.app.AppLog
import com.khereba1.app.R
import com.khereba1.app.data.ChatMessage
import com.khereba1.app.data.ChatRoom
import com.khereba1.app.data.MessageType
import com.khereba1.app.media.AudioPlayerManager
import com.khereba1.app.network.LanServerState
import com.khereba1.app.network.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    room: ChatRoom,
    messages: List<ChatMessage>,
    lanState: LanServerState,
    isRecording: Boolean,
    getMediaFile: (String) -> File,
    onBack: () -> Unit,
    onSendText: (String) -> Unit,
    onPickAttachments: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onEditMessage: (String, String) -> Unit,
    onDeleteMessage: (String) -> Unit,
    onToggleLanShare: (Boolean) -> Unit,
    onRegenerateTokens: () -> Unit
) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var showLanSheet by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<ChatMessage?>(null) }
    val playbackState by AudioPlayerManager.state.collectAsState()
    val listState = rememberLazyListState()

    var recSeconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(isRecording) {
        recSeconds = 0L
        while (isRecording) {
            delay(1000)
            recSeconds++
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
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
                        Text(
                            text = room.title,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = stringResource(R.string.items_count, messages.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showLanSheet = true }) {
                        Icon(
                            Icons.Rounded.WifiTethering,
                            contentDescription = stringResource(R.string.lan_share_title),
                            tint = if (room.isLanShared) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onPickAttachments) {
                        Icon(Icons.Rounded.AttachFile, contentDescription = null)
                    }
                    if (isRecording) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(24.dp))
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${stringResource(R.string.recording_label)} ${recSeconds}s",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        IconButton(
                            onClick = onStopRecording,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.error)
                        ) {
                            Icon(
                                Icons.Rounded.Stop,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onError
                            )
                        }
                    } else {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            placeholder = { Text(stringResource(R.string.message_hint)) },
                            maxLines = 4,
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(6.dp))
                        if (input.isNotBlank()) {
                            IconButton(
                                onClick = {
                                    onSendText(input)
                                    input = ""
                                },
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.Send,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        } else {
                            IconButton(
                                onClick = onStartRecording,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer)
                            ) {
                                Icon(
                                    Icons.Rounded.Mic,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        if (messages.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.empty_chat_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    val file = msg.mediaFileName?.let { getMediaFile(it) }
                    MessageBubble(
                        message = msg,
                        mediaFile = file,
                        isPlayingAudio = playbackState.activeMessageId == msg.id && playbackState.isPlaying,
                        onToggleAudio = {
                            if (file != null) AudioPlayerManager.toggle(msg.id, file)
                        },
                        onOpenMedia = {
                            if (file != null) openFileExternally(context, file, msg.mediaMime)
                        },
                        onCopyText = {
                            copyToClipboard(context, msg.text)
                        },
                        onEditText = { editTarget = msg },
                        onDelete = { onDeleteMessage(msg.id) }
                    )
                }
            }
        }
    }

    editTarget?.let { msg ->
        var updated by remember(msg.id) { mutableStateOf(msg.text) }
        AlertDialog(
            onDismissRequest = { editTarget = null },
            title = { Text(stringResource(R.string.edit_message)) },
            text = {
                OutlinedTextField(
                    value = updated,
                    onValueChange = { updated = it },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    enabled = updated.isNotBlank(),
                    onClick = {
                        onEditMessage(msg.id, updated)
                        editTarget = null
                    }
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { editTarget = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showLanSheet) {
        LanShareDialog(
            room = room,
            lanState = lanState,
            onToggleShare = onToggleLanShare,
            onRegenerate = onRegenerateTokens,
            onDismiss = { showLanSheet = false }
        )
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    mediaFile: File?,
    isPlayingAudio: Boolean,
    onToggleAudio: () -> Unit,
    onOpenMedia: () -> Unit,
    onCopyText: () -> Unit,
    onEditText: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val timeText = remember(message.createdAt) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.createdAt))
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${message.senderName} • $timeText",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (message.text.isNotBlank()) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.copy)) },
                                leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onCopyText()
                                }
                            )
                        }
                        if (message.type == MessageType.TEXT) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.edit)) },
                                leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onEditText()
                                }
                            )
                        }
                        if (mediaFile != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.open)) },
                                leadingIcon = { Icon(Icons.Rounded.InsertDriveFile, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onOpenMedia()
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete)) },
                            leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            }
                        )
                    }
                }
            }

            if (message.text.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            if (mediaFile != null) {
                Spacer(Modifier.height(6.dp))
                when (message.type) {
                    MessageType.IMAGE -> {
                        val bmp by produceState<ImageBitmap?>(initialValue = null, mediaFile.absolutePath) {
                            value = withContext(Dispatchers.IO) {
                                runCatching {
                                    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                                    BitmapFactory.decodeFile(mediaFile.absolutePath, opts)?.asImageBitmap()
                                }.getOrNull()
                            }
                        }
                        if (bmp != null) {
                            Image(
                                bitmap = bmp!!,
                                contentDescription = message.mediaFileName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 240.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable(onClick = onOpenMedia)
                            )
                        } else {
                            FileCard(message, onOpenMedia)
                        }
                    }

                    MessageType.AUDIO -> {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onToggleAudio)
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        if (isPlayingAudio) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = stringResource(R.string.voice_note),
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    val secs = (message.durationMs / 1000L).coerceAtLeast(1L)
                                    Text(
                                        text = "${secs}s • ${formatSize(message.mediaSize)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    MessageType.VIDEO -> {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onOpenMedia)
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Rounded.VideoFile,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(34.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = message.mediaFileName ?: "Video",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = formatSize(message.mediaSize),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    else -> FileCard(message, onOpenMedia)
                }
            }
        }
    }
}

@Composable
private fun FileCard(message: ChatMessage, onOpen: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.InsertDriveFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(30.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = message.mediaFileName ?: "File",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = formatSize(message.mediaSize),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun LanShareDialog(
    room: ChatRoom,
    lanState: LanServerState,
    onToggleShare: (Boolean) -> Unit,
    onRegenerate: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val ip = remember(lanState.isRunning) { NetworkUtils.getLocalIpAddress() }
    val baseUrl = lanState.baseUrl.ifEmpty {
        if (ip != null) "http://$ip:${lanState.port}" else ""
    }
    val viewerLink = if (baseUrl.isNotEmpty()) "$baseUrl/c/${room.viewerToken}" else ""
    val editorLink = if (baseUrl.isNotEmpty()) "$baseUrl/c/${room.editorToken}" else ""

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lan_share_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.lan_share_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.lan_enable),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = room.isLanShared,
                        onCheckedChange = onToggleShare
                    )
                }

                if (room.isLanShared) {
                    if (baseUrl.isEmpty()) {
                        Text(
                            text = stringResource(R.string.lan_no_wifi),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        ShareLinkBox(
                            label = stringResource(R.string.lan_viewer_section),
                            link = viewerLink,
                            code = room.viewerToken,
                            onCopy = { copyToClipboard(context, viewerLink) },
                            onShare = { shareTextExternally(context, viewerLink) }
                        )
                        ShareLinkBox(
                            label = stringResource(R.string.lan_editor_section),
                            link = editorLink,
                            code = room.editorToken,
                            onCopy = { copyToClipboard(context, editorLink) },
                            onShare = { shareTextExternally(context, editorLink) }
                        )
                        OutlinedButton(
                            onClick = onRegenerate,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.lan_regenerate))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        }
    )
}

@Composable
private fun ShareLinkBox(
    label: String,
    link: String,
    code: String,
    onCopy: () -> Unit,
    onShare: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(link, style = MaterialTheme.typography.bodySmall)
            Text("Code: $code", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onCopy) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.copy))
                }
                TextButton(onClick = onShare) {
                    Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.share))
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
    return String.format(Locale.US, "%.2f MB", kb / 1024.0)
}

fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("NearChat", text))
    Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
}

private fun shareTextExternally(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.share)))
}

private fun openFileExternally(context: Context, file: File, mime: String?) {
    try {
        val uri = FileProvider.getUriForFile(context, "com.khereba1.nearchat.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.open)))
    } catch (e: Throwable) {
        AppLog.log("UI", "Failed to open file ${file.name}", e)
    }
}
