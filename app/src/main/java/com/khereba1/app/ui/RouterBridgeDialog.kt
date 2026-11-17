package com.khereba1.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.khereba1.app.PREFS
import com.khereba1.app.R
import com.khereba1.app.network.NearChatVpnService
import com.khereba1.app.network.RouterBridgeState
import com.khereba1.app.network.WifiProxyServer

@Composable
fun RouterBridgeDialog(
    state: RouterBridgeState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var customPass by remember {
        mutableStateOf(prefs.getString("router_pass", "NearChat123") ?: "NearChat123")
    }
    var upstreamSocks by remember {
        mutableStateOf(prefs.getString("upstream_socks", "") ?: "")
    }
    var clientHostIp by remember {
        mutableStateOf(prefs.getString("vpn_host_ip", "192.168.49.1") ?: "192.168.49.1")
    }
    var clientPort by remember {
        mutableStateOf(prefs.getString("vpn_host_port", "1080") ?: "1080")
    }
    val isVpnRunning by NearChatVpnService.isRunning.collectAsState()
    val supportsDirectHotspot = remember { WifiProxyServer.supportsDirectWifiHotspot(context) }

    val vpnPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val portNum = clientPort.trim().toIntOrNull()?.coerceIn(1, 65535) ?: 1080
            NearChatVpnService.start(context, clientHostIp.ifBlank { "192.168.49.1" }, portNum)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Rounded.Router,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text(stringResource(R.string.router_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = stringResource(
                                if (supportsDirectHotspot) R.string.hw_direct_supported
                                else R.string.hw_p2p_recommended
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                        if (supportsDirectHotspot) {
                            OutlinedButton(
                                onClick = {
                                    runCatching {
                                        context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Rounded.WifiTethering, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.open_hotspot_settings))
                            }
                        }
                    }
                }

                if (!state.isRunning) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        OutlinedTextField(
                            value = customPass,
                            onValueChange = {
                                customPass = it
                                prefs.edit().putString("router_pass", it).apply()
                            },
                            label = { Text(stringResource(R.string.router_custom_pass)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    OutlinedTextField(
                        value = upstreamSocks,
                        onValueChange = {
                            upstreamSocks = it
                            prefs.edit().putString("upstream_socks", it).apply()
                        },
                        label = { Text(stringResource(R.string.upstream_socks_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (state.isRunning) {
                    val httpAddr = "${state.proxyHost}:${state.proxyPort}"
                    val socksAddr = "${state.proxyHost}:${state.socksPort}"
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "HTTP Proxy:",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(httpAddr, fontWeight = FontWeight.SemiBold)
                                }
                                IconButton(onClick = { copyToClipboard(context, httpAddr) }) {
                                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "SOCKS5 (Telegram / VPN):",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(socksAddr, fontWeight = FontWeight.SemiBold)
                                }
                                IconButton(onClick = { copyToClipboard(context, socksAddr) }) {
                                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }

                    if (state.p2pSsid.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.router_p2p_label),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text("SSID: ${state.p2pSsid}")
                                    Text("Pass: ${state.p2pPass}")
                                }
                                IconButton(onClick = { copyToClipboard(context, state.p2pPass) }) {
                                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    } else if (state.p2pError.isNotEmpty()) {
                        Text(
                            text = state.p2pError,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.router_stop))
                    }
                } else {
                    if (isVpnRunning) {
                        Text(
                            text = stringResource(R.string.stop_vpn_first),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Button(
                        enabled = !isVpnRunning,
                        onClick = onStart,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.Router, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.router_start))
                    }
                }

                HorizontalDivider()

                Text(
                    text = stringResource(R.string.vpn_client_section),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                if (!isVpnRunning) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = clientHostIp,
                            onValueChange = {
                                clientHostIp = it
                                prefs.edit().putString("vpn_host_ip", it).apply()
                            },
                            label = { Text(stringResource(R.string.vpn_host_ip)) },
                            singleLine = true,
                            modifier = Modifier.weight(2f)
                        )
                        OutlinedTextField(
                            value = clientPort,
                            onValueChange = {
                                clientPort = it.filter { ch -> ch.isDigit() }
                                prefs.edit().putString("vpn_host_port", clientPort).apply()
                            },
                            label = { Text(stringResource(R.string.vpn_port_label)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Button(
                        enabled = !state.isRunning,
                        onClick = {
                            val prep = VpnService.prepare(context)
                            if (prep != null) {
                                vpnPermLauncher.launch(prep)
                            } else {
                                val portNum = clientPort.trim().toIntOrNull()?.coerceIn(1, 65535) ?: 1080
                                NearChatVpnService.start(context, clientHostIp.ifBlank { "192.168.49.1" }, portNum)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.VpnKey, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.vpn_connect))
                    }
                } else {
                    OutlinedButton(
                        onClick = { NearChatVpnService.stop(context) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.vpn_disconnect))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        }
    )
}
