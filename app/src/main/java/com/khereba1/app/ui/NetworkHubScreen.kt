package com.khereba1.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.khereba1.app.PREFS
import com.khereba1.app.R
import com.khereba1.app.network.NearChatVpnService
import com.khereba1.app.network.NetworkUtils
import com.khereba1.app.network.RouterBridgeState
import com.khereba1.app.network.VoiceCallManager
import com.khereba1.app.network.WifiProxyServer
import com.khereba1.app.network.XrayTunnelManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkHubScreen(
    routerState: RouterBridgeState,
    onStartRouter: () -> Unit,
    onStopRouter: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var selectedTab by remember { mutableIntStateOf(0) }

    var customPass by remember {
        mutableStateOf(prefs.getString("router_pass", "NearChat123") ?: "NearChat123")
    }
    var upstreamSocks by remember {
        mutableStateOf(prefs.getString("upstream_socks", "") ?: "")
    }
    var xrayConfig by remember {
        mutableStateOf(prefs.getString("xray_config", "") ?: "")
    }
    var profileName by remember { mutableStateOf("") }
    var profiles by remember { mutableStateOf(XrayTunnelManager.loadProfiles(context)) }
    var pingResult by remember { mutableStateOf("") }

    var clientHostIp by remember {
        mutableStateOf(prefs.getString("vpn_host_ip", "192.168.49.1") ?: "192.168.49.1")
    }
    var clientPort by remember {
        mutableStateOf(prefs.getString("vpn_host_port", "1080") ?: "1080")
    }
    var peerCallIp by remember {
        mutableStateOf(prefs.getString("call_peer_ip", "192.168.49.1") ?: "192.168.49.1")
    }

    val isVpnRunning by NearChatVpnService.isRunning.collectAsState()
    val xrayState by XrayTunnelManager.state.collectAsState()
    val callState by VoiceCallManager.state.collectAsState()
    val supportsDirectHotspot = remember { WifiProxyServer.supportsDirectWifiHotspot(context) }
    val localIp = remember { NetworkUtils.getLocalIpAddress() ?: "192.168.49.1" }

    val vpnPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val portNum = clientPort.trim().toIntOrNull()?.coerceIn(1, 65535) ?: 1080
            NearChatVpnService.start(context, clientHostIp.ifBlank { "192.168.49.1" }, portNum)
        }
    }

    val callMicLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(context, context.getString(R.string.mic_permission_needed), Toast.LENGTH_SHORT).show()
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
                title = { Text(stringResource(R.string.router_title), fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.tab_router), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.tab_vpn), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text(stringResource(R.string.tab_xray), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
                Tab(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    text = { Text(stringResource(R.string.tab_calls), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (selectedTab) {
                    0 -> {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                            runCatching { context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
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

                        if (!routerState.isRunning) {
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
                            if (isVpnRunning) {
                                Text(
                                    text = stringResource(R.string.stop_vpn_first),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            Button(
                                enabled = !isVpnRunning,
                                onClick = onStartRouter,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Rounded.Router, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.router_start))
                            }
                        } else {
                            val httpAddr = "${routerState.proxyHost}:${routerState.proxyPort}"
                            val socksAddr = "${routerState.proxyHost}:${routerState.socksPort}"
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("HTTP Proxy:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                            Text(httpAddr, fontWeight = FontWeight.SemiBold)
                                        }
                                        IconButton(onClick = { copyToClipboard(context, httpAddr) }) {
                                            Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("SOCKS5:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                            Text(socksAddr, fontWeight = FontWeight.SemiBold)
                                        }
                                        IconButton(onClick = { copyToClipboard(context, socksAddr) }) {
                                            Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                            if (routerState.p2pSsid.isNotEmpty()) {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(stringResource(R.string.router_p2p_label), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                            Text("SSID: ${routerState.p2pSsid}")
                                            Text("Pass: ${routerState.p2pPass}")
                                        }
                                        IconButton(onClick = { copyToClipboard(context, routerState.p2pPass) }) {
                                            Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            } else if (routerState.p2pError.isNotEmpty()) {
                                Text(routerState.p2pError, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            }
                            OutlinedButton(onClick = onStopRouter, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.router_stop))
                            }
                        }
                    }

                    1 -> {
                        Text(stringResource(R.string.vpn_client_section), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
                                enabled = !routerState.isRunning,
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

                    2 -> {
                        Text(stringResource(R.string.xray_section), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            value = xrayConfig,
                            onValueChange = {
                                xrayConfig = it
                                prefs.edit().putString("xray_config", it).apply()
                            },
                            label = { Text(stringResource(R.string.xray_link_label)) },
                            maxLines = 4,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = profileName,
                                onValueChange = { profileName = it },
                                label = { Text(stringResource(R.string.profile_name_label)) },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedButton(
                                onClick = {
                                    if (xrayConfig.isNotBlank()) {
                                        profiles = XrayTunnelManager.saveProfile(context, profileName, xrayConfig)
                                        profileName = ""
                                    }
                                },
                                modifier = Modifier.align(Alignment.CenterVertically)
                            ) {
                                Text(stringResource(R.string.save_profile))
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    pingResult = "Testing…"
                                    scope.launch {
                                        XrayTunnelManager.testPing(context, xrayConfig)
                                            .onSuccess { ms -> pingResult = "Ping: ${ms} ms" }
                                            .onFailure { e -> pingResult = "Ping failed: ${e.message}" }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Rounded.Speed, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.ping_test))
                            }
                            if (!xrayState.isRunning) {
                                Button(
                                    onClick = {
                                        XrayTunnelManager.start(context, xrayConfig)
                                        val localAddr = "127.0.0.1:${XrayTunnelManager.LOCAL_SOCKS_PORT}"
                                        upstreamSocks = localAddr
                                        prefs.edit().putString("upstream_socks", localAddr).apply()
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(stringResource(R.string.xray_start), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { XrayTunnelManager.stop() },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(stringResource(R.string.xray_stop))
                                }
                            }
                        }
                        if (pingResult.isNotEmpty()) {
                            Text(pingResult, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        if (xrayState.status.isNotEmpty()) {
                            Text(
                                text = xrayState.status,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (xrayState.isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }

                        if (profiles.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(stringResource(R.string.saved_profiles), fontWeight = FontWeight.SemiBold)
                            profiles.forEach { p ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            xrayConfig = p.config
                                            prefs.edit().putString("xray_config", p.config).apply()
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(p.name, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                        IconButton(onClick = { profiles = XrayTunnelManager.deleteProfile(context, p.id) }) {
                                            Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    3 -> {
                        Text(
                            text = stringResource(R.string.call_my_ip, localIp),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (!callState.isActive) {
                            OutlinedTextField(
                                value = peerCallIp,
                                onValueChange = {
                                    peerCallIp = it
                                    prefs.edit().putString("call_peer_ip", it).apply()
                                },
                                label = { Text(stringResource(R.string.call_peer_ip)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Button(
                                onClick = {
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                        VoiceCallManager.startCall(context, peerCallIp)
                                    } else {
                                        callMicLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Rounded.Call, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.call_start))
                            }
                            OutlinedButton(
                                onClick = {
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                        VoiceCallManager.startCall(context, null)
                                    } else {
                                        callMicLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.call_listen))
                            }
                        } else {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(callState.status, fontWeight = FontWeight.Bold)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(
                                            onClick = { VoiceCallManager.toggleMute() },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(Icons.Rounded.MicOff, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text(if (callState.isMuted) "Muted" else stringResource(R.string.call_mute))
                                        }
                                        OutlinedButton(
                                            onClick = { VoiceCallManager.toggleSpeaker(context) },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(Icons.Rounded.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text(if (callState.isSpeaker) "On" else stringResource(R.string.call_speaker))
                                        }
                                    }
                                    Button(
                                        onClick = { VoiceCallManager.stopCall(context) },
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Rounded.CallEnd, contentDescription = null)
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.call_end))
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
