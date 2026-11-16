package com.khereba1.app.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.khereba1.app.R
import com.khereba1.app.network.BtConnectionStatus
import com.khereba1.app.network.BtState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@SuppressLint("MissingPermission")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BluetoothChatScreen(
    btState: BtState,
    pairedDevices: List<BluetoothDevice>,
    onBack: () -> Unit,
    onHost: () -> Unit,
    onConnect: (BluetoothDevice) -> Unit,
    onSend: (String) -> Unit,
    onDisconnect: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    var showPairedList by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(btState.messages.size) {
        if (btState.messages.isNotEmpty()) {
            listState.animateScrollToItem(btState.messages.size - 1)
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
                        Text(stringResource(R.string.bt_chat_title), fontWeight = FontWeight.Bold)
                        val subtitle = when (btState.status) {
                            BtConnectionStatus.CONNECTED -> stringResource(R.string.bt_connected, btState.peerName)
                            BtConnectionStatus.HOSTING, BtConnectionStatus.CONNECTING -> stringResource(R.string.bt_waiting)
                            else -> "Bluetooth RFCOMM"
                        }
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                actions = {
                    if (btState.status == BtConnectionStatus.CONNECTED || btState.status == BtConnectionStatus.HOSTING) {
                        IconButton(onClick = onDisconnect) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.bt_disconnect))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        bottomBar = {
            if (btState.status == BtConnectionStatus.CONNECTED) {
                Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 4.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            placeholder = { Text(stringResource(R.string.message_hint)) },
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        IconButton(
                            enabled = input.isNotBlank(),
                            onClick = {
                                onSend(input)
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
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp)
        ) {
            if (btState.status != BtConnectionStatus.CONNECTED) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.Bluetooth,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.bt_tip),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (btState.status == BtConnectionStatus.HOSTING || btState.status == BtConnectionStatus.CONNECTING) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Text(stringResource(R.string.bt_waiting))
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = onHost, modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.bt_host_btn))
                                }
                                OutlinedButton(
                                    onClick = { showPairedList = !showPairedList },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(stringResource(R.string.bt_connect_btn))
                                }
                            }
                        }

                        if (showPairedList && btState.status == BtConnectionStatus.IDLE) {
                            if (pairedDevices.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.bt_no_paired),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            } else {
                                pairedDevices.forEach { dev ->
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                showPairedList = false
                                                onConnect(dev)
                                            }
                                    ) {
                                        Text(
                                            text = "${dev.name ?: "Device"} (${dev.address})",
                                            modifier = Modifier.padding(12.dp),
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(btState.messages, key = { it.id }) { m ->
                    val time = remember(m.timeMs) {
                        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(m.timeMs))
                    }
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = if (m.isMine) Alignment.CenterEnd else Alignment.CenterStart
                    ) {
                        Surface(
                            shape = RoundedCornerShape(
                                topStart = 18.dp,
                                topEnd = 18.dp,
                                bottomStart = if (m.isMine) 18.dp else 4.dp,
                                bottomEnd = if (m.isMine) 4.dp else 18.dp
                            ),
                            color = if (m.isMine) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface,
                            modifier = Modifier.widthIn(max = 290.dp)
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text(
                                    text = m.text,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = time,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.align(Alignment.End)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
