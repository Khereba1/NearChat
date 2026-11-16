package com.khereba1.app.ui

import android.content.Context
import android.os.Build
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
                Text(
                    text = stringResource(R.string.router_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (!state.isRunning && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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

                if (state.isRunning) {
                    val proxyAddr = "${state.proxyHost}:${state.proxyPort}"
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.router_proxy_label),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(proxyAddr, fontWeight = FontWeight.SemiBold)
                            }
                            IconButton(onClick = { copyToClipboard(context, proxyAddr) }) {
                                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
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
                    TextButton(
                        onClick = { WifiProxyServer.stop(keepGroup = false) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.router_reset_group))
                    }
                } else {
                    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Router, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.router_start))
                    }
                }

                Text(
                    text = stringResource(R.string.router_hw_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        }
    )
}
