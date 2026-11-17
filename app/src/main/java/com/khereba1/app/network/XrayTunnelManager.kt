package com.khereba1.app.network

import android.content.Context
import android.util.Base64
import com.khereba1.app.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class XrayState(
    val isRunning: Boolean = false,
    val status: String = ""
)

object XrayTunnelManager {
    private var controller: CoreController? = null
    private var envInitialized = false
    private val _state = MutableStateFlow(XrayState())
    val state: StateFlow<XrayState> = _state

    const val LOCAL_SOCKS_PORT = 10808

    fun start(context: Context, rawInput: String) {
        stop()
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            _state.value = XrayState(false, "Paste a config link or JSON first")
            return
        }

        val configJson = try {
            buildConfigJson(trimmed)
        } catch (e: Throwable) {
            AppLog.log("XRAY", "Config parse error", e)
            _state.value = XrayState(false, "Invalid config: ${e.message}")
            return
        }

        Thread {
            try {
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

    private fun buildConfigJson(input: String): String {
        if (input.startsWith("{")) {
            // Ensure it parses as valid JSON
            JSONObject(input)
            return input
        }

        val outbound = when {
            input.startsWith("vless://", ignoreCase = true) -> parseVless(input)
            input.startsWith("trojan://", ignoreCase = true) -> parseTrojan(input)
            input.startsWith("ss://", ignoreCase = true) -> parseShadowsocks(input)
            input.startsWith("socks://", ignoreCase = true) -> parseSocksUri(input)
            else -> error("Unsupported scheme (use vless://, trojan://, ss://, socks://, or JSON)")
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

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split("&").mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) null
            else URLDecoder.decode(pair.substring(0, idx), "UTF-8") to
                URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
        }.toMap()
    }

    private fun buildStreamSettings(q: Map<String, String>, defaultHost: String): JSONObject {
        val network = (q["type"] ?: "tcp").lowercase()
        val security = (q["security"] ?: "none").lowercase()
        val stream = JSONObject().put("network", network).put("security", security)

        when (network) {
            "ws" -> {
                val ws = JSONObject().put("path", q["path"] ?: "/")
                val host = q["host"]
                if (!host.isNullOrBlank()) {
                    ws.put("headers", JSONObject().put("Host", host))
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
                val host = q["host"]
                if (!host.isNullOrBlank()) hu.put("host", host)
                stream.put("httpupgradeSettings", hu)
            }
        }

        when (security) {
            "tls" -> {
                val tls = JSONObject()
                    .put("serverName", q["sni"] ?: q["host"] ?: defaultHost)
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
                    .put("serverName", q["sni"] ?: defaultHost)
                    .put("fingerprint", q["fp"] ?: "chrome")
                    .put("publicKey", q["pbk"] ?: "")
                    .put("shortId", q["sid"] ?: "")
                stream.put("realitySettings", reality)
            }
        }
        return stream
    }

    private fun parseVless(raw: String): JSONObject {
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
            .put("streamSettings", buildStreamSettings(q, host))
    }

    private fun parseTrojan(raw: String): JSONObject {
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
            .put("streamSettings", buildStreamSettings(q, host))
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
