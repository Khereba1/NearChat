package com.khereba1.app.ui

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.khereba1.app.PREFS
import com.khereba1.app.R
import com.khereba1.app.network.HotspotAutoVpnManager
import com.khereba1.app.network.LanShareService
import com.khereba1.app.network.NearChatVpnService
import com.khereba1.app.network.StreamerKeepAlive
import com.khereba1.app.network.QrGenerator
import com.khereba1.app.network.RootHelper
import com.khereba1.app.network.RootStatus
import com.khereba1.app.network.RouterBridgeState
import com.khereba1.app.network.ConnectedClientInfo
import com.khereba1.app.network.WifiProxyServer
import com.khereba1.app.network.XrayOverrides
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
    val appContext = context.applicationContext
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
    var hostOverride by remember {
        mutableStateOf(prefs.getString("xray_host_override", "") ?: "")
    }
    var sniOverride by remember {
        mutableStateOf(prefs.getString("xray_sni_override", "") ?: "")
    }
    var allowInsecure by remember {
        mutableStateOf(prefs.getBoolean("xray_allow_insecure", false))
    }
    var autoStartVpn by remember {
        mutableStateOf(prefs.getBoolean("xray_auto_vpn", true))
    }
    var allowLanBind by remember {
        mutableStateOf(prefs.getBoolean("xray_allow_lan", false))
    }
    var autoVpnOnHotspot by remember {
        mutableStateOf(prefs.getBoolean("auto_vpn_on_hotspot", false))
    }
    var autoVpnBgWatcher by remember {
        mutableStateOf(prefs.getBoolean("auto_vpn_bg_watcher", false))
    }
    var streamerWakeLock by remember {
        mutableStateOf(prefs.getBoolean("streamer_wakelock", false))
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

    var keepWifiGroup by remember {
        mutableStateOf(prefs.getBoolean("keep_wifi_group", false))
    }
    var rootStatus by remember { mutableStateOf(RootStatus()) }
    var rootActionMsg by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        rootStatus = RootHelper.checkRoot()
    }

    val isVpnRunning by NearChatVpnService.isRunning.collectAsState()
    val connectedClients by WifiProxyServer.clients.collectAsState()
    var limitTarget by remember { mutableStateOf<ConnectedClientInfo?>(null) }
    val xrayState by XrayTunnelManager.state.collectAsState()
    val supportsDirectHotspot = remember { WifiProxyServer.supportsDirectWifiHotspot(context) }

    val currentOverrides = XrayOverrides(
        hostOverride = hostOverride,
        sniOverride = sniOverride,
        allowInsecure = allowInsecure,
        allowLan = allowLanBind
    )

    var pendingVpnHost by remember { mutableStateOf("192.168.49.1") }
    var pendingVpnPort by remember { mutableIntStateOf(1080) }

    val vpnPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            NearChatVpnService.start(appContext, pendingVpnHost, pendingVpnPort)
        }
    }

    val startVpnWithTarget: (String, Int) -> Unit = { targetHost, targetPort ->
        pendingVpnHost = targetHost.ifBlank { "192.168.49.1" }
        pendingVpnPort = targetPort.coerceIn(1, 65535)
        val prep = VpnService.prepare(context)
        if (prep != null) {
            vpnPermLauncher.launch(prep)
        } else {
            NearChatVpnService.start(appContext, pendingVpnHost, pendingVpnPort)
        }
    }

    val launchSingBoxAndVpn: () -> Unit = {
        val localAddr = "127.0.0.1:${XrayTunnelManager.LOCAL_SOCKS_PORT}"
        upstreamSocks = localAddr
        prefs.edit().putString("upstream_socks", localAddr).apply()
        val shouldStartVpn = autoStartVpn && !routerState.isRunning
        if (shouldStartVpn) {
            clientHostIp = "127.0.0.1"
            clientPort = XrayTunnelManager.LOCAL_SOCKS_PORT.toString()
            prefs.edit()
                .putString("vpn_host_ip", clientHostIp)
                .putString("vpn_host_port", clientPort)
                .apply()
        }
        XrayTunnelManager.start(appContext, xrayConfig, currentOverrides, vpnMode = shouldStartVpn)
    }

    val xrayVpnPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            launchSingBoxAndVpn()
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
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = {
                        Icon(
                            if (routerState.isRunning) Icons.Rounded.CheckCircle else Icons.Rounded.Router,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (routerState.isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    text = { Text(stringResource(R.string.tab_router), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = {
                        Icon(
                            if (isVpnRunning) Icons.Rounded.CheckCircle else Icons.Rounded.VpnKey,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (isVpnRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    text = { Text(stringResource(R.string.tab_vpn), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = {
                        Icon(
                            if (xrayState.isRunning) Icons.Rounded.CheckCircle else Icons.Rounded.Security,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (xrayState.isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    text = { Text(stringResource(R.string.tab_xray), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                when (selectedTab) {
                    0 -> {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                if (rootStatus.checked) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = stringResource(
                                                if (rootStatus.rootGranted) R.string.root_verified
                                                else if (rootStatus.hasSuBinary) R.string.root_detected
                                                else R.string.root_not_detected
                                            ),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (rootStatus.hasSuBinary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (rootStatus.hasSuBinary && !rootStatus.rootGranted) {
                                            OutlinedButton(
                                                onClick = {
                                                    scope.launch {
                                                        rootStatus = RootHelper.requestRootAccess()
                                                        rootActionMsg = rootStatus.detail
                                                    }
                                                }
                                            ) {
                                                Text(stringResource(R.string.root_verify), style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                    if (rootStatus.rootGranted) {
                                        OutlinedButton(
                                            onClick = {
                                                scope.launch {
                                                    RootHelper.enableIpForwarding()
                                                        .onSuccess { rootActionMsg = "IP Forwarding: $it" }
                                                        .onFailure { rootActionMsg = "Root error: ${it.message}" }
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(stringResource(R.string.root_ip_forward))
                                        }
                                    }
                                    if (rootActionMsg.isNotEmpty()) {
                                        Text(rootActionMsg, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }

                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                tonalElevation = 1.dp,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(stringResource(R.string.keep_wifi_group_label), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                            Text(stringResource(R.string.keep_wifi_group_desc), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Switch(
                                            checked = keepWifiGroup,
                                            onCheckedChange = {
                                                keepWifiGroup = it
                                                prefs.edit().putBoolean("keep_wifi_group", it).apply()
                                            }
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            WifiProxyServer.resetP2pGroup(context)
                                            Toast.makeText(context, context.getString(R.string.router_reset_group), Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(stringResource(R.string.router_reset_group))
                                    }
                                }
                            }
                        }

                        if (!routerState.isRunning) {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                tonalElevation = 2.dp,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.router_start))
                                    }
                                }
                            }
                        } else {
                            val httpAddr = "${routerState.proxyHost}:${routerState.proxyPort}"
                            val socksAddr = "${routerState.proxyHost}:${routerState.socksPort}"
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                                val wifiQrContent = "WIFI:T:WPA;S:${routerState.p2pSsid};P:${routerState.p2pPass};;"
                                val wifiQrBitmap = remember(wifiQrContent) { QrGenerator.generate(wifiQrContent, 420) }
                                val quickSetupUri = "nearchat://connect?host=${routerState.proxyHost}&port=${routerState.socksPort}"
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(stringResource(R.string.router_p2p_label), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                                Text("SSID: ${routerState.p2pSsid}", fontWeight = FontWeight.Medium)
                                                Text("${stringResource(R.string.password_label)}: ${routerState.p2pPass}", fontWeight = FontWeight.Medium)
                                            }
                                            IconButton(onClick = { copyToClipboard(context, routerState.p2pPass) }) {
                                                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                        if (wifiQrBitmap != null) {
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(stringResource(R.string.qr_wifi_title), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                                Spacer(Modifier.height(6.dp))
                                                Image(
                                                    bitmap = wifiQrBitmap,
                                                    contentDescription = stringResource(R.string.qr_wifi_title),
                                                    modifier = Modifier
                                                        .size(170.dp)
                                                        .background(Color.White, RoundedCornerShape(12.dp))
                                                        .padding(8.dp)
                                                )
                                            }
                                        }
                                        OutlinedButton(
                                            onClick = { copyToClipboard(context, quickSetupUri) },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text(stringResource(R.string.copy_quick_setup))
                                        }
                                    }
                                }
                            } else if (routerState.p2pError.isNotEmpty()) {
                                Text(routerState.p2pError, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            }
                            // Connected Devices & Quota/Speed Control Card (shown only while Host Router is active)
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                tonalElevation = 2.dp,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "${stringResource(R.string.connected_devices_title)} (${connectedClients.size})",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (connectedClients.isEmpty()) {
                                        Text(
                                            text = stringResource(R.string.no_connected_devices),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    } else {
                                        connectedClients.forEach { client ->
                                            Surface(
                                                shape = RoundedCornerShape(12.dp),
                                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Column(
                                                    modifier = Modifier.padding(10.dp),
                                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text(
                                                                text = client.ip + if (client.blocked) " (${stringResource(R.string.device_blocked_badge)})" else "",
                                                                fontWeight = FontWeight.SemiBold,
                                                                color = if (client.blocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                                                            )
                                                            val usedMb = String.format(java.util.Locale.US, "%.1f MB", client.bytesUsed / (1024.0 * 1024.0))
                                                            val speedKbps = client.bytesPerSec / 1024L
                                                            val limitInfo = buildString {
                                                                if (client.quotaMb > 0) append(" • Max ${client.quotaMb}MB")
                                                                if (client.speedLimitKbps > 0) append(" • ${client.speedLimitKbps}KB/s")
                                                            }
                                                            Text(
                                                                text = "$usedMb • ${speedKbps} KB/s$limitInfo",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                        }
                                                    }
                                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                        OutlinedButton(
                                                            onClick = { limitTarget = client },
                                                            modifier = Modifier.weight(1f)
                                                        ) {
                                                            Text(stringResource(R.string.device_limit_btn), maxLines = 1)
                                                        }
                                                        OutlinedButton(
                                                            onClick = { WifiProxyServer.setClientBlocked(client.ip, !client.blocked) },
                                                            modifier = Modifier.weight(1f)
                                                        ) {
                                                            Text(
                                                                stringResource(if (client.blocked) R.string.device_unblock_btn else R.string.device_block_btn),
                                                                color = if (client.blocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                                                maxLines = 1
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            Button(
                                onClick = onStopRouter,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Rounded.Stop, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.router_stop))
                            }
                        }
                    }

                    1 -> {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            tonalElevation = 2.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(stringResource(R.string.vpn_client_section), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(stringResource(R.string.vpn_manual_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = stringResource(R.string.auto_vpn_hotspot_title),
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = stringResource(R.string.auto_vpn_hotspot_desc),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Switch(
                                            checked = autoVpnOnHotspot,
                                            onCheckedChange = { enabled ->
                                                autoVpnOnHotspot = enabled
                                                prefs.edit().putBoolean("auto_vpn_on_hotspot", enabled).apply()
                                                if (enabled) {
                                                    val prep = VpnService.prepare(context)
                                                    if (prep != null) {
                                                        vpnPermLauncher.launch(prep)
                                                    } else {
                                                        HotspotAutoVpnManager.triggerCheckNow(context)
                                                    }
                                                    if (autoVpnBgWatcher) {
                                                        runCatching { LanShareService.startWatcher(context) }
                                                    }
                                                } else {
                                                    runCatching { LanShareService.stopWatcher(context) }
                                                }
                                            }
                                        )
                                    }
                                }
                                if (autoVpnOnHotspot) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = stringResource(R.string.auto_vpn_watcher_title),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                                Text(
                                                    text = stringResource(R.string.auto_vpn_watcher_desc),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Switch(
                                                checked = autoVpnBgWatcher,
                                                onCheckedChange = { enabled ->
                                                    autoVpnBgWatcher = enabled
                                                    prefs.edit().putBoolean("auto_vpn_bg_watcher", enabled).apply()
                                                    if (enabled && autoVpnOnHotspot) {
                                                        runCatching { LanShareService.startWatcher(context) }
                                                    } else {
                                                        runCatching { LanShareService.stopWatcher(context) }
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                                if (!isVpnRunning) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(
                                            onClick = {
                                                clientHostIp = "192.168.49.1"
                                                clientPort = "1080"
                                                prefs.edit().putString("vpn_host_ip", clientHostIp).putString("vpn_host_port", clientPort).apply()
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(stringResource(R.string.preset_host_router), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                clientHostIp = "127.0.0.1"
                                                clientPort = XrayTunnelManager.LOCAL_SOCKS_PORT.toString()
                                                prefs.edit().putString("vpn_host_ip", clientHostIp).putString("vpn_host_port", clientPort).apply()
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(stringResource(R.string.preset_local_core), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
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
                                    OutlinedButton(
                                        onClick = {
                                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            val clip = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
                                            if (clip.startsWith("nearchat://")) {
                                                val hostMatch = Regex("host=([^&]+)").find(clip)?.groupValues?.get(1)
                                                val portMatch = Regex("port=([0-9]+)").find(clip)?.groupValues?.get(1)
                                                if (!hostMatch.isNullOrBlank()) {
                                                    clientHostIp = hostMatch
                                                    prefs.edit().putString("vpn_host_ip", hostMatch).apply()
                                                }
                                                if (!portMatch.isNullOrBlank()) {
                                                    clientPort = portMatch
                                                    prefs.edit().putString("vpn_host_port", portMatch).apply()
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.paste_quick_setup))
                                    }
                                    Button(
                                        enabled = !routerState.isRunning,
                                        onClick = {
                                            val portNum = clientPort.trim().toIntOrNull()?.coerceIn(1, 65535) ?: 1080
                                            startVpnWithTarget(clientHostIp.ifBlank { "192.168.49.1" }, portNum)
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Rounded.VpnKey, contentDescription = null)
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.vpn_connect))
                                    }
                                    if (rootStatus.hasSuBinary) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(
                                                onClick = {
                                                    scope.launch {
                                                        if (!rootStatus.rootGranted) {
                                                            rootStatus = RootHelper.requestRootAccess()
                                                        }
                                                        if (rootStatus.rootGranted) {
                                                            val httpPort = 8899
                                                            RootHelper.applyGlobalHttpProxy(clientHostIp.ifBlank { "192.168.49.1" }, httpPort)
                                                                .onSuccess { rootActionMsg = "System HTTP Proxy: $clientHostIp:$httpPort" }
                                                                .onFailure { rootActionMsg = "Root error: ${it.message}" }
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text(stringResource(R.string.root_apply_proxy), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    scope.launch {
                                                        if (!rootStatus.rootGranted) {
                                                            rootStatus = RootHelper.requestRootAccess()
                                                        }
                                                        if (rootStatus.rootGranted) {
                                                            RootHelper.clearGlobalHttpProxy()
                                                                .onSuccess { rootActionMsg = "System HTTP Proxy cleared" }
                                                                .onFailure { rootActionMsg = "Root error: ${it.message}" }
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text(stringResource(R.string.root_clear_proxy), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                        if (rootActionMsg.isNotEmpty()) {
                                            Text(rootActionMsg, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                } else {
                                    Button(
                                        onClick = { NearChatVpnService.stop(context) },
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Rounded.Stop, contentDescription = null)
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.vpn_disconnect))
                                    }
                                }
                            }
                        }
                    }

                    2 -> {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            tonalElevation = 2.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        stringResource(R.string.xray_section),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(onClick = {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = cm.primaryClip?.getItemAt(0)?.text?.toString()
                                        if (!clip.isNullOrBlank()) {
                                            xrayConfig = clip
                                            prefs.edit().putString("xray_config", clip).apply()
                                        }
                                    }) {
                                        Icon(Icons.Rounded.ContentPaste, contentDescription = stringResource(R.string.paste_label), modifier = Modifier.size(18.dp))
                                    }
                                    if (xrayConfig.isNotEmpty()) {
                                        IconButton(onClick = {
                                            xrayConfig = ""
                                            prefs.edit().putString("xray_config", "").apply()
                                        }) {
                                            Icon(Icons.Rounded.Clear, contentDescription = stringResource(R.string.clear_label), modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
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
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = stringResource(R.string.xray_overrides_title),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = hostOverride,
                                        onValueChange = {
                                            hostOverride = it
                                            prefs.edit().putString("xray_host_override", it).apply()
                                        },
                                        label = { Text(stringResource(R.string.xray_host_override)) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedTextField(
                                        value = sniOverride,
                                        onValueChange = {
                                            sniOverride = it
                                            prefs.edit().putString("xray_sni_override", it).apply()
                                        },
                                        label = { Text(stringResource(R.string.xray_sni_override)) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.xray_allow_insecure),
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Switch(
                                        checked = allowInsecure,
                                        onCheckedChange = {
                                            allowInsecure = it
                                            prefs.edit().putBoolean("xray_allow_insecure", it).apply()
                                        }
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(R.string.auto_vpn_label),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = stringResource(R.string.auto_vpn_desc),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = autoStartVpn,
                                        onCheckedChange = {
                                            autoStartVpn = it
                                            prefs.edit().putBoolean("xray_auto_vpn", it).apply()
                                        }
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.allow_lan_label),
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Switch(
                                        checked = allowLanBind,
                                        onCheckedChange = {
                                            allowLanBind = it
                                            prefs.edit().putBoolean("xray_allow_lan", it).apply()
                                        }
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(R.string.streamer_wakelock_title),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = stringResource(R.string.streamer_wakelock_desc),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = streamerWakeLock,
                                        onCheckedChange = { enabled ->
                                            streamerWakeLock = enabled
                                            prefs.edit().putBoolean("streamer_wakelock", enabled).apply()
                                            StreamerKeepAlive.setEnabled(context, enabled)
                                        }
                                    )
                                }
                            }
                        }

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
                                        profiles = XrayTunnelManager.saveProfile(context, profileName, xrayConfig, currentOverrides)
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
                                        XrayTunnelManager.testPing(context, xrayConfig, currentOverrides)
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
                                        if (autoStartVpn && !routerState.isRunning) {
                                            val prep = VpnService.prepare(context)
                                            if (prep != null) {
                                                xrayVpnPermLauncher.launch(prep)
                                                return@Button
                                            }
                                        }
                                        launchSingBoxAndVpn()
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.xray_start), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            } else {
                                Button(
                                    onClick = {
                                        XrayTunnelManager.stop()
                                        if (isVpnRunning) {
                                            NearChatVpnService.stop(context)
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.xray_stop))
                                }
                            }
                        }

                        if (pingResult.isNotEmpty()) {
                            Text(pingResult, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        if (xrayState.status.isNotEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (xrayState.isRunning) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = xrayState.status,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (xrayState.isRunning) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }

                        if (profiles.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(stringResource(R.string.saved_profiles), fontWeight = FontWeight.SemiBold)
                            profiles.forEach { p ->
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            xrayConfig = p.config
                                            hostOverride = p.hostOverride
                                            sniOverride = p.sniOverride
                                            allowInsecure = p.allowInsecure
                                            prefs.edit()
                                                .putString("xray_config", p.config)
                                                .putString("xray_host_override", p.hostOverride)
                                                .putString("xray_sni_override", p.sniOverride)
                                                .putBoolean("xray_allow_insecure", p.allowInsecure)
                                                .apply()
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(p.name, fontWeight = FontWeight.SemiBold)
                                            if (p.sniOverride.isNotEmpty() || p.hostOverride.isNotEmpty() || p.allowInsecure) {
                                                Text(
                                                    text = buildString {
                                                        if (p.hostOverride.isNotEmpty()) append("Host: ${p.hostOverride} ")
                                                        if (p.sniOverride.isNotEmpty()) append("SNI: ${p.sniOverride} ")
                                                        if (p.allowInsecure) append("[Insecure]")
                                                    }.trim(),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        IconButton(onClick = { profiles = XrayTunnelManager.deleteProfile(context, p.id) }) {
                                            Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
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

    limitTarget?.let { dev ->
        var quotaStr by remember(dev.ip) { mutableStateOf(if (dev.quotaMb > 0) dev.quotaMb.toString() else "0") }
        var speedStr by remember(dev.ip) { mutableStateOf(if (dev.speedLimitKbps > 0) dev.speedLimitKbps.toString() else "0") }
        AlertDialog(
            onDismissRequest = { limitTarget = null },
            title = { Text("${stringResource(R.string.device_limit_btn)}: ${dev.ip}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = quotaStr,
                        onValueChange = { quotaStr = it.filter { ch -> ch.isDigit() } },
                        label = { Text(stringResource(R.string.quota_mb_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = speedStr,
                        onValueChange = { speedStr = it.filter { ch -> ch.isDigit() } },
                        label = { Text(stringResource(R.string.speed_kbps_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(onClick = {
                        WifiProxyServer.resetClientUsage(dev.ip)
                        limitTarget = null
                    }) {
                        Text(stringResource(R.string.reset_usage_btn))
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    WifiProxyServer.setClientLimits(
                        dev.ip,
                        quotaStr.toIntOrNull() ?: 0,
                        speedStr.toIntOrNull() ?: 0
                    )
                    limitTarget = null
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { limitTarget = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun StatusPill(
    label: String,
    active: Boolean,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = if (active) Icons.Rounded.CheckCircle else icon,
                contentDescription = null,
                tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = "$label: ${stringResource(if (active) R.string.status_on else R.string.status_off)}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
