package com.khereba1.app.network

import android.content.Context
import android.util.Base64
import com.khereba1.app.AppLog
import com.khereba1.app.PREFS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import org.json.JSONArray
import org.json.JSONObject
import android.annotation.SuppressLint
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.nio.charset.StandardCharsets
import java.util.UUID

data class XrayState(
    val isRunning: Boolean = false,
    val status: String = ""
)

data class XrayOverrides(
    val hostOverride: String = "",
    val sniOverride: String = "",
    val allowInsecure: Boolean = false
)

data class XrayProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val config: String,
    val hostOverride: String = "",
    val sniOverride: String = "",
    val allowInsecure: Boolean = false
)

object XrayTunnelManager {
    private var controller: CoreController? = null
    private var envInitialized = false
    private val _state = MutableStateFlow(XrayState())
    val state: StateFlow<XrayState> = _state

    const val LOCAL_SOCKS_PORT = 10808
    private const val PREF_PROFILES = "xray_profiles_json"

    fun loadProfiles(context: Context): List<XrayProfile> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PREF_PROFILES, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                XrayProfile(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    name = o.optString("name", "Profile"),
                    config = o.optString("config", ""),
                    hostOverride = o.optString("hostOverride", ""),
                    sniOverride = o.optString("sniOverride", ""),
                    allowInsecure = o.optBoolean("allowInsecure", false)
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun saveProfile(
        context: Context,
        name: String,
        config: String,
        overrides: XrayOverrides = XrayOverrides()
    ): List<XrayProfile> {
        val list = loadProfiles(context).toMutableList()
        list.add(
            XrayProfile(
                name = name.trim().ifEmpty { "Profile ${list.size + 1}" },
                config = config.trim(),
                hostOverride = overrides.hostOverride.trim(),
                sniOverride = overrides.sniOverride.trim(),
                allowInsecure = overrides.allowInsecure
            )
        )
        persistProfiles(context, list)
        return list
    }

    fun deleteProfile(context: Context, id: String): List<XrayProfile> {
        val list = loadProfiles(context).filterNot { it.id == id }
        persistProfiles(context, list)
        return list
    }

    private fun persistProfiles(context: Context, list: List<XrayProfile>) {
        val arr = JSONArray()
        for (p in list) {
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("config", p.config)
                    .put("hostOverride", p.hostOverride)
                    .put("sniOverride", p.sniOverride)
                    .put("allowInsecure", p.allowInsecure)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(PREF_PROFILES, arr.toString()).apply()
    }

    suspend fun testPing(
        context: Context,
        rawInput: String,
        overrides: XrayOverrides = XrayOverrides()
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            if (!envInitialized) {
                Libv2ray.initCoreEnv(context.filesDir.absolutePath, "")
                envInitialized = true
            }
            val cfg = buildConfigJson(rawInput.trim(), overrides)
            Libv2ray.measureOutboundDelay(cfg, "https://www.gstatic.com/generate_204")
        }
    }

    fun start(
        context: Context,
        rawInput: String,
        overrides: XrayOverrides = XrayOverrides()
    ) {
        stop()
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            _state.value = XrayState(false, "Paste a config link or JSON first")
            return
        }

        _state.value = XrayState(false, "Connecting…")
        Thread {
            try {
                val configJson = buildConfigJson(trimmed, overrides)
                if (!envInitialized) {
                    Libv2ray.initCoreEnv(context.filesDir.absolutePath, "")
                    envInitialized = true
                }
                val ctrl = Libv2ray.newCoreController(object : CoreCallbackHandler {
                    override fun startup(): Long = 0L
                    override fun shutdown(): Long {
                        _state.value = XrayState(false, "Stopped")
                        return 0L
                    }

                    override fun onEmitStatus(p0: Long, p1: String?): Long {
                        if (!p1.isNullOrBlank()) {
                            _state.value = _state.value.copy(status = p1)
                        }
                        return 0L
                    }
                })
                controller = ctrl
                _state.value = XrayState(true, "Running on 127.0.0.1:$LOCAL_SOCKS_PORT")
                ctrl.startLoop(configJson, 0)
            } catch (e: Throwable) {
                AppLog.log("XRAY", "StartLoop failed", e)
                _state.value = XrayState(false, "Error: ${e.message}")
                controller = null
            }
        }.start()
    }

    fun stop() {
        try {
            controller?.stopLoop()
        } catch (_: Throwable) {
        }
        controller = null
        _state.value = XrayState(false, "")
    }

    private fun buildConfigJson(input: String, overrides: XrayOverrides): String {
        if (input.startsWith("{")) {
            val root = JSONObject(input)
            sanitizeJsonConfig(root, overrides)
            return root.toString()
        }

        val outbound = when {
            input.startsWith("vless://", ignoreCase = true) -> parseVless(input, overrides)
            input.startsWith("vmess://", ignoreCase = true) -> parseVmess(input, overrides)
            input.startsWith("trojan://", ignoreCase = true) -> parseTrojan(input, overrides)
            input.startsWith("ss://", ignoreCase = true) -> parseShadowsocks(input)
            input.startsWith("socks://", ignoreCase = true) -> parseSocksUri(input)
            else -> error("Unsupported scheme (use vless://, vmess://, trojan://, ss://, socks://, or JSON)")
        }

        val inbound = JSONObject()
            .put("tag", "socks-in")
            .put("port", LOCAL_SOCKS_PORT)
            .put("listen", "127.0.0.1")
            .put("protocol", "socks")
            .put("settings", JSONObject().put("auth", "noauth").put("udp", true))

        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", JSONArray().put(inbound))
            .put("outbounds", JSONArray().put(outbound))
            .toString()
    }



    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private fun fetchServerCertSha256(host: String, port: Int, sni: String): String? {
        if (host.isBlank() || port !in 1..65535) return null
        return runCatching {
            val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            })
            val sslCtx = SSLContext.getInstance("TLS")
            sslCtx.init(null, trustAll, SecureRandom())
            val sock = sslCtx.socketFactory.createSocket() as SSLSocket
            sock.use { s ->
                s.soTimeout = 4000
                if (sni.isNotBlank() && !sni.all { it.isDigit() || it == '.' || it == ':' }) {
                    val params = s.sslParameters
                    params.serverNames = listOf(SNIHostName(sni))
                    s.sslParameters = params
                }
                s.connect(InetSocketAddress(host, port), 4000)
                s.startHandshake()
                val cert = s.session.peerCertificates.firstOrNull() ?: return null
                val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
                digest.joinToString("") { "%02x".format(it) }
            }
        }.onFailure { e ->
            AppLog.log("XRAY", "Cert SHA-256 probe failed for $host:$port", e)
        }.getOrNull()
    }

    private fun sanitizeJsonConfig(root: JSONObject, overrides: XrayOverrides) {
        val outbounds = root.optJSONArray("outbounds") ?: return
        for (i in 0 until outbounds.length()) {
            val ob = outbounds.optJSONObject(i) ?: continue
            val settings = ob.optJSONObject("settings")
            var serverAddr = ""
            var serverPort = 443
            val vnext = settings?.optJSONArray("vnext")
            if (vnext != null && vnext.length() > 0) {
                val v0 = vnext.optJSONObject(0)
                serverAddr = v0?.optString("address", "") ?: ""
                serverPort = v0?.optInt("port", 443) ?: 443
            } else {
                val servers = settings?.optJSONArray("servers")
                if (servers != null && servers.length() > 0) {
                    val s0 = servers.optJSONObject(0)
                    serverAddr = s0?.optString("address", "") ?: ""
                    serverPort = s0?.optInt("port", 443) ?: 443
                }
            }
            val stream = ob.optJSONObject("streamSettings") ?: continue
            val hostOv = overrides.hostOverride.trim()
            if (hostOv.isNotEmpty()) {
                val ws = stream.optJSONObject("wsSettings")
                if (ws != null) {
                    val headers = ws.optJSONObject("headers") ?: JSONObject().also { ws.put("headers", it) }
                    headers.put("Host", hostOv)
                }
                val hu = stream.optJSONObject("httpupgradeSettings")
                hu?.put("host", hostOv)
            }
            val tls = stream.optJSONObject("tlsSettings")
            if (tls != null) {
                val hadInsecure = tls.optBoolean("allowInsecure", false)
                tls.remove("allowInsecure")
                val sniOv = overrides.sniOverride.trim()
                if (sniOv.isNotEmpty()) {
                    tls.put("serverName", sniOv)
                }
                val currentSni = tls.optString("serverName", "").ifBlank { serverAddr }
                if ((hadInsecure || overrides.allowInsecure) && !tls.has("pinnedPeerCertSha256")) {
                    val sha = fetchServerCertSha256(serverAddr, serverPort, currentSni)
                    if (!sha.isNullOrBlank()) {
                        tls.put("pinnedPeerCertSha256", sha)
                    } else if (!tls.has("verifyPeerCertByName") && serverAddr.isNotBlank() && !serverAddr.equals(currentSni, ignoreCase = true)) {
                        tls.put("verifyPeerCertByName", serverAddr)
                    }
                }
            }
        }
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split("&").mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) null
            else URLDecoder.decode(pair.substring(0, idx), "UTF-8") to
                URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
        }.toMap()
    }

    private fun buildStreamSettings(
        q: Map<String, String>,
        defaultHost: String,
        defaultPort: Int,
        overrides: XrayOverrides
    ): JSONObject {
        val network = (q["type"] ?: "tcp").lowercase()
        val security = (q["security"] ?: "none").lowercase()
        val stream = JSONObject().put("network", network).put("security", security)

        val effectiveHost = overrides.hostOverride.trim().ifEmpty { q["host"] ?: "" }
        val effectiveSni = overrides.sniOverride.trim()
            .ifEmpty { q["sni"] ?: effectiveHost.ifEmpty { defaultHost } }
        val linkInsecure = (q["allowInsecure"] ?: q["insecure"] ?: "").lowercase()
        val effectiveInsecure = overrides.allowInsecure ||
            linkInsecure == "1" || linkInsecure == "true"

        when (network) {
            "ws" -> {
                val ws = JSONObject().put("path", q["path"] ?: "/")
                if (effectiveHost.isNotBlank()) {
                    ws.put("headers", JSONObject().put("Host", effectiveHost))
                }
                stream.put("wsSettings", ws)
            }
            "grpc" -> {
                stream.put(
                    "grpcSettings",
                    JSONObject().put("serviceName", q["serviceName"] ?: q["path"] ?: "")
                )
            }
            "httpupgrade" -> {
                val hu = JSONObject().put("path", q["path"] ?: "/")
                if (effectiveHost.isNotBlank()) hu.put("host", effectiveHost)
                stream.put("httpupgradeSettings", hu)
            }
        }

        when (security) {
            "tls" -> {
                val tls = JSONObject().put("serverName", effectiveSni)
                var pcs = q["pcs"] ?: q["pinnedPeerCertSha256"]
                if (pcs.isNullOrBlank() && effectiveInsecure) {
                    pcs = fetchServerCertSha256(defaultHost, defaultPort, effectiveSni)
                }
                if (!pcs.isNullOrBlank()) {
                    tls.put("pinnedPeerCertSha256", pcs)
                } else {
                    val vcn = (q["vcn"] ?: q["verifyPeerCertByName"]).orEmpty().ifBlank {
                        if (effectiveInsecure && defaultHost.isNotBlank() && !defaultHost.equals(effectiveSni, ignoreCase = true)) {
                            defaultHost
                        } else ""
                    }
                    if (vcn.isNotBlank()) {
                        tls.put("verifyPeerCertByName", vcn)
                    }
                }
                val fp = q["fp"]
                if (!fp.isNullOrBlank()) tls.put("fingerprint", fp)
                val alpn = q["alpn"]
                if (!alpn.isNullOrBlank()) {
                    tls.put("alpn", JSONArray(alpn.split(",")))
                }
                stream.put("tlsSettings", tls)
            }
            "reality" -> {
                val reality = JSONObject()
                    .put("serverName", effectiveSni)
                    .put("fingerprint", q["fp"] ?: "chrome")
                    .put("publicKey", q["pbk"] ?: "")
                    .put("shortId", q["sid"] ?: "")
                stream.put("realitySettings", reality)
            }
        }
        return stream
    }

    private fun parseVless(raw: String, overrides: XrayOverrides): JSONObject {
        val uri = URI(raw)
        val uuid = uri.userInfo ?: error("Missing UUID")
        val host = uri.host ?: error("Missing host")
        val port = if (uri.port > 0) uri.port else 443
        val q = parseQuery(uri.rawQuery)

        val user = JSONObject()
            .put("id", uuid)
            .put("encryption", q["encryption"] ?: "none")
        val flow = q["flow"]
        if (!flow.isNullOrBlank()) user.put("flow", flow)

        val vnext = JSONObject()
            .put("address", host)
            .put("port", port)
            .put("users", JSONArray().put(user))

        return JSONObject()
            .put("tag", "proxy")
            .put("protocol", "vless")
            .put("settings", JSONObject().put("vnext", JSONArray().put(vnext)))
            .put("streamSettings", buildStreamSettings(q, host, port, overrides))
    }

    private fun parseVmess(raw: String, overrides: XrayOverrides): JSONObject {
        val b64 = raw.removePrefix("vmess://").trim()
        val jsonText = String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8)
        val obj = JSONObject(jsonText)
        val host = obj.getString("add")
        val port = obj.optString("port", "443").toIntOrNull() ?: obj.optInt("port", 443)
        val uuid = obj.getString("id")
        val aid = obj.optString("aid", "0").toIntOrNull() ?: 0

        val q = mutableMapOf<String, String>()
        q["type"] = obj.optString("net", "tcp")
        q["security"] = if (obj.optString("tls", "").equals("tls", ignoreCase = true)) "tls" else "none"
        if (obj.has("host")) q["host"] = obj.optString("host")
        if (obj.has("path")) q["path"] = obj.optString("path")
        if (obj.has("sni")) q["sni"] = obj.optString("sni")
        if (obj.has("fp")) q["fp"] = obj.optString("fp")
        if (obj.optBoolean("allowInsecure", false)) q["allowInsecure"] = "1"

        val user = JSONObject()
            .put("id", uuid)
            .put("alterId", aid)
            .put("security", obj.optString("scy", "auto"))

        val vnext = JSONObject()
            .put("address", host)
            .put("port", port)
            .put("users", JSONArray().put(user))

        return JSONObject()
            .put("tag", "proxy")
            .put("protocol", "vmess")
            .put("settings", JSONObject().put("vnext", JSONArray().put(vnext)))
            .put("streamSettings", buildStreamSettings(q, host, port, overrides))
    }

    private fun parseTrojan(raw: String, overrides: XrayOverrides): JSONObject {
        val uri = URI(raw)
        val pass = uri.userInfo ?: error("Missing password")
        val host = uri.host ?: error("Missing host")
        val port = if (uri.port > 0) uri.port else 443
        val q = parseQuery(uri.rawQuery).toMutableMap()
        if (!q.containsKey("security")) q["security"] = "tls"

        val server = JSONObject()
            .put("address", host)
            .put("port", port)
            .put("password", pass)

        return JSONObject()
            .put("tag", "proxy")
            .put("protocol", "trojan")
            .put("settings", JSONObject().put("servers", JSONArray().put(server)))
            .put("streamSettings", buildStreamSettings(q, host, port, overrides))
    }

    private fun parseShadowsocks(raw: String): JSONObject {
        val withoutScheme = raw.removePrefix("ss://").substringBefore("#")
        val mainPart = withoutScheme.substringBefore("?")
        val (method, pass, host, port) = if (mainPart.contains("@")) {
            val left = mainPart.substringBefore("@")
            val right = mainPart.substringAfter("@")
            val decoded = if (left.contains(":")) left else String(Base64.decode(left, Base64.DEFAULT), StandardCharsets.UTF_8)
            val m = decoded.substringBefore(":")
            val p = decoded.substringAfter(":")
            val h = right.substringBeforeLast(":")
            val pt = right.substringAfterLast(":").toInt()
            listOf(m, p, h, pt.toString())
        } else {
            val decoded = String(Base64.decode(mainPart, Base64.DEFAULT), StandardCharsets.UTF_8)
            val m = decoded.substringBefore(":")
            val rest = decoded.substringAfter(":")
            val p = rest.substringBeforeLast("@")
            val right = rest.substringAfterLast("@")
            val h = right.substringBeforeLast(":")
            val pt = right.substringAfterLast(":").toInt()
            listOf(m, p, h, pt.toString())
        }

        val server = JSONObject()
            .put("address", host)
            .put("port", port.toInt())
            .put("method", method)
            .put("password", pass)

        return JSONObject()
            .put("tag", "proxy")
            .put("protocol", "shadowsocks")
            .put("settings", JSONObject().put("servers", JSONArray().put(server)))
    }

    private fun parseSocksUri(raw: String): JSONObject {
        val uri = URI(raw)
        val host = uri.host ?: error("Missing host")
        val port = if (uri.port > 0) uri.port else 1080
        val server = JSONObject().put("address", host).put("port", port)
        return JSONObject()
            .put("tag", "proxy")
            .put("protocol", "socks")
            .put("settings", JSONObject().put("servers", JSONArray().put(server)))
    }
}
