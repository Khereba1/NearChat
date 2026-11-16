package com.khereba1.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.khereba1.app.R
import com.khereba1.app.data.ChatMessage
import com.khereba1.app.data.LanPermission
import com.khereba1.app.data.MessageType
import com.khereba1.app.data.RemoteChatInfo
import com.khereba1.app.network.LanClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteChatScreen(
    info: RemoteChatInfo,
    displayName: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<ChatMessage?>(null) }

    suspend fun reload() {
        LanClient.fetchMessages(info.serverUrl, info.token).onSuccess {
            messages = it
        }
    }

    LaunchedEffect(info.token) {
        while (true) {
            reload()
            delay(3000)
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            withContext(Dispatchers.IO) {
                val cr = context.contentResolver
                val mime = cr.getType(uri) ?: "application/octet-stream"
                var fileName = "file_${System.currentTimeMillis()}"
                cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) fileName = c.getString(0) ?: fileName
                }
                val bytes = cr.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null) {
                    LanClient.uploadBytes(info.serverUrl, info.token, fileName, mime, displayName, bytes)
                }
            }
            reload()
            busy = false
        }
    }

    val isEditor = info.permission == LanPermission.EDITOR

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
                        Text(info.title, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        Badge {
                            Text(
                                text = stringResource(
                                    if (isEditor) R.string.editor_role else R.string.viewer_role
                                ),
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { scope.launch { reload() } }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                    IconButton(onClick = {
                        val url = "${info.serverUrl}/c/${info.token}"
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }) {
                        Icon(Icons.Rounded.OpenInBrowser, contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        bottomBar = {
            if (isEditor) {
                Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 4.dp) {
                    Column {
                        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                                Icon(Icons.Rounded.AttachFile, contentDescription = null)
                            }
                            OutlinedTextField(
                                value = input,
                                onValueChange = { input = it },
                                placeholder = { Text(stringResource(R.string.message_hint)) },
                                shape = RoundedCornerShape(24.dp),
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(6.dp))
                            IconButton(
                                enabled = input.isNotBlank(),
                                onClick = {
                                    val txt = input.trim()
                                    input = ""
                                    scope.launch {
                                        LanClient.sendText(info.serverUrl, info.token, txt, displayName)
                                        reload()
                                    }
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
                        }
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages, key = { it.id }) { m ->
                val timeText = remember(m.createdAt) {
                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(m.createdAt))
                }
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
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
                            if (isEditor) {
                                Row {
                                    if (m.type == MessageType.TEXT) {
                                        IconButton(
                                            onClick = { editTarget = m },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                LanClient.deleteMessage(info.serverUrl, info.token, m.id)
                                                reload()
                                            }
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                        if (m.text.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(m.text, style = MaterialTheme.typography.bodyLarge)
                        }
                        if (m.mediaFileName != null) {
                            Spacer(Modifier.height(6.dp))
                            val mediaUrl = "${info.serverUrl}/api/${info.token}/media/" +
                                URLEncoder.encode(m.mediaFileName, "UTF-8")
                            Text(
                                text = "📎 ${m.mediaFileName}",
                                color = MaterialTheme.colorScheme.secondary,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.clickable {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(mediaUrl)))
                                }
                            )
                        }
                    }
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
                        val txt = updated.trim()
                        editTarget = null
                        scope.launch {
                            LanClient.editMessage(info.serverUrl, info.token, msg.id, txt)
                            reload()
                        }
                    }
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { editTarget = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}
