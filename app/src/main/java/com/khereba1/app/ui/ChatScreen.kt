package com.khereba1.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.khereba1.app.media.AudioPlaybackState
import com.khereba1.app.media.AudioPlayerManager
import com.khereba1.app.network.LanServerState
import com.khereba1.app.network.NetworkUtils
import com.khereba1.app.network.QrGenerator
import com.khereba1.app.ui.theme.ChatAccentColors
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
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var showLanSheet by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<ChatMessage?>(null) }
    var viewImageFile by remember { mutableStateOf<File?>(null) }
    var playVideoFile by remember { mutableStateOf<File?>(null) }

    val playbackState by AudioPlayerManager.state.collectAsState()
    val listState = rememberLazyListState()
    val meLabel = stringResource(R.string.me_sender)

    val showScrollDown by remember {
        derivedStateOf {
            val total = listState.layoutInfo.totalItemsCount
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            total > 0 && lastVisible < total - 2
        }
    }

    var recSeconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(isRecording) {
        recSeconds = 0L
        while (isRecording) {
            delay(1000)
            recSeconds++
        }
    }

    // Reliable auto-scroll whenever a new message arrives or is sent
    LaunchedEffect(messages.size, messages.lastOrNull()?.id) {
        if (messages.isNotEmpty()) {
            delay(60)
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    val accent = ChatAccentColors[room.colorIndex.mod(ChatAccentColors.size)]

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(accent),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = room.title.take(1).uppercase(),
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.width(10.dp))
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
                    }
                },
                actions = {
                    IconButton(onClick = { showLanSheet = true }) {
                        Icon(
                            if (room.isLanShared) Icons.Rounded.WifiTethering else Icons.Rounded.QrCode2,
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
                tonalElevation = 6.dp,
                modifier = Modifier.navigationBarsPadding().imePadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onPickAttachments) {
                        Icon(
                            Icons.Rounded.AttachFile,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (isRecording) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(24.dp))
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.error)
                            )
                            Spacer(Modifier.width(8.dp))
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
                                .size(46.dp)
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
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant,
                                focusedBorderColor = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(6.dp))
                        AnimatedContent(targetState = input.isNotBlank(), label = "send_mic") { hasText ->
                            if (hasText) {
                                IconButton(
                                    onClick = {
                                        onSendText(input)
                                        input = ""
                                    },
                                    modifier = Modifier
                                        .size(46.dp)
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
                                        .size(46.dp)
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
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            MaterialTheme.colorScheme.background
                        )
                    )
                )
        ) {
            if (messages.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                    ) {
                        Text(
                            text = stringResource(R.string.empty_chat_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(messages, key = { it.id }) { msg ->
                        val file = msg.mediaFileName?.let { getMediaFile(it) }
                        val isMine = msg.senderName == "Me" || msg.senderName == meLabel
                        MessageBubble(
                            modifier = Modifier.animateItem(),
                            message = msg,
                            isMine = isMine,
                            mediaFile = file,
                            playbackState = playbackState,
                            onToggleAudio = {
                                if (file != null) AudioPlayerManager.toggle(msg.id, file)
                            },
                            onSeekAudio = { pos -> AudioPlayerManager.seekTo(pos) },
                            onOpenMedia = {
                                if (file != null) {
                                    when (msg.type) {
                                        MessageType.IMAGE -> viewImageFile = file
                                        MessageType.VIDEO -> playVideoFile = file
                                        else -> openFileExternally(context, file, msg.mediaMime)
                                    }
                                }
                            },
                            onCopyText = { copyToClipboard(context, msg.text) },
                            onEditText = { editTarget = msg },
                            onDelete = { onDeleteMessage(msg.id) }
                        )
                    }
                }
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = showScrollDown,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(14.dp)
            ) {
                SmallFloatingActionButton(
                    onClick = {
                        scope.launch {
                            if (messages.isNotEmpty()) {
                                listState.animateScrollToItem(messages.lastIndex)
                            }
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null)
                }
            }
        }
    }

    viewImageFile?.let { f ->
        InAppImageViewerDialog(file = f, onDismiss = { viewImageFile = null })
    }

    playVideoFile?.let { f ->
        InAppVideoPlayerDialog(file = f, onDismiss = { playVideoFile = null })
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
    modifier: Modifier = Modifier,
    message: ChatMessage,
    isMine: Boolean,
    mediaFile: File?,
    playbackState: AudioPlaybackState,
    onToggleAudio: () -> Unit,
    onSeekAudio: (Int) -> Unit,
    onOpenMedia: () -> Unit,
    onCopyText: () -> Unit,
    onEditText: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val timeText = remember(message.createdAt) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.createdAt))
    }
    val isActiveAudio = playbackState.activeMessageId == message.id
    val isPlayingAudio = isActiveAudio && playbackState.isPlaying

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = if (isMine) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isMine) 18.dp else 4.dp,
                bottomEnd = if (isMine) 4.dp else 18.dp
            ),
            color = if (isMine) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            shadowElevation = 1.dp,
            modifier = Modifier.widthIn(min = 120.dp, max = 310.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!isMine) {
                        Text(
                            text = message.senderName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(22.dp)) {
                            Icon(
                                Icons.Rounded.MoreVert,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
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
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                if (mediaFile != null) {
                    Spacer(Modifier.height(4.dp))
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
                                        .heightIn(min = 140.dp, max = 240.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable(onClick = onOpenMedia)
                                )
                            } else {
                                FileCard(message, onOpenMedia)
                            }
                        }

                        MessageType.VIDEO -> {
                            val thumb by produceState<ImageBitmap?>(initialValue = null, mediaFile.absolutePath) {
                                value = withContext(Dispatchers.IO) {
                                    extractVideoThumb(mediaFile)?.asImageBitmap()
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(170.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.Black)
                                    .clickable(onClick = onOpenMedia),
                                contentAlignment = Alignment.Center
                            ) {
                                if (thumb != null) {
                                    Image(
                                        bitmap = thumb!!,
                                        contentDescription = message.mediaFileName,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .size(52.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.6f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Rounded.PlayArrow,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                                Surface(
                                    color = Color.Black.copy(alpha = 0.6f),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(6.dp)
                                ) {
                                    Text(
                                        text = formatSize(message.mediaSize),
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        MessageType.AUDIO -> {
                            val totalMs = if (isActiveAudio && playbackState.durationMs > 0) {
                                playbackState.durationMs
                            } else {
                                message.durationMs.toInt().coerceAtLeast(1000)
                            }
                            val currentPos = if (isActiveAudio) playbackState.positionMs else 0

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary)
                                        .clickable(onClick = onToggleAudio),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        if (isPlayingAudio) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Slider(
                                        value = (currentPos.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f),
                                        onValueChange = { frac ->
                                            if (isActiveAudio) onSeekAudio((frac * totalMs).toInt())
                                        },
                                        modifier = Modifier.height(24.dp)
                                    )
                                    Text(
                                        text = "${formatSecs(currentPos / 1000)} / ${formatSecs(totalMs / 1000)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        else -> FileCard(message, onOpenMedia)
                    }
                }

                Spacer(Modifier.height(2.dp))
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.End)
                )
            }
        }
    }
}

@Composable
private fun FileCard(message: ChatMessage, onOpen: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
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
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = message.mediaFileName ?: "File",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.bodyMedium
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
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Viewer, 1 = Editor

    val activeLink = if (selectedTab == 0) viewerLink else editorLink
    val activeCode = if (selectedTab == 0) room.viewerToken else room.editorToken
    val qrBitmap = remember(activeLink) { QrGenerator.generate(activeLink, 420) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lan_share_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
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
                    Switch(checked = room.isLanShared, onCheckedChange = onToggleShare)
                }

                if (room.isLanShared) {
                    if (baseUrl.isEmpty()) {
                        Text(
                            text = stringResource(R.string.lan_no_wifi),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                label = { Text(stringResource(R.string.viewer_role)) }
                            )
                            FilterChip(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                label = { Text(stringResource(R.string.editor_role)) }
                            )
                        }

                        if (qrBitmap != null) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = Color.White,
                                modifier = Modifier.padding(4.dp)
                            ) {
                                Image(
                                    bitmap = qrBitmap,
                                    contentDescription = "QR Code",
                                    modifier = Modifier
                                        .size(190.dp)
                                        .padding(10.dp)
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = if (selectedTab == 0) stringResource(R.string.lan_viewer_section)
                                    else stringResource(R.string.lan_editor_section),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(activeLink, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    text = "Code: $activeCode",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { copyToClipboard(context, activeLink) }) {
                                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(R.string.copy))
                                    }
                                    TextButton(onClick = { shareTextExternally(context, activeLink) }) {
                                        Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(R.string.share))
                                    }
                                }
                            }
                        }

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

private fun extractVideoThumb(file: File): Bitmap? {
    val mmr = MediaMetadataRetriever()
    return try {
        mmr.setDataSource(file.absolutePath)
        mmr.getFrameAtTime(0)
    } catch (_: Throwable) {
        null
    } finally {
        try { mmr.release() } catch (_: Throwable) {}
    }
}

private fun formatSecs(secs: Int): String {
    val m = secs / 60
    val s = secs % 60
    return String.format(Locale.US, "%d:%02d", m, s)
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
