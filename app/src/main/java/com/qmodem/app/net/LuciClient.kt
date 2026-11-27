package com.qmodem.app.net

import com.qmodem.app.model.InfoEntry
import com.qmodem.app.model.ModemSection
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicLong

/** 登录失败 */
class LoginException(message: String) : Exception(message)

/** 网络异常（无法连接等） */
class NetworkException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** ubus / RPC 调用失败 */
class RpcException(val ubusCode: Int, message: String) : Exception(message)

/** 会话过期，需要重新登录 */
class AuthExpiredException(message: String) : Exception(message)

/** 与路由器的通信方式 */
enum class Transport {
    /** 直连 uhttpd 的 /ubus JSON-RPC 端点（uhttpd-mod-ubus） */
    UBUS_DIRECT,

    /** LuCI 表单登录 + /cgi-bin/luci/admin/ubus 代理 */
    LUCI_PROXY
}

/** qmodem 后端类型 */
enum class Backend {
    /** 新版 qmodem（rpcd ubus 对象 qmodem） */
    UBUS_QMODEM,

    /** 旧版 qmodem 的 ubus 对象 modem_ctrl（无参数、批量返回） */
    UBUS_MODEM_CTRL,

    /** 旧版 Lua LuCI HTTP 端点（admin/modem/qmodem/modem_ctrl） */
    LEGACY_HTTP
}

/**
 * OpenWrt LuCI / qmodem 客户端。
 *
 * 登录策略：
 *  1. 尝试直连 /ubus（JSON-RPC session.login，适用于 uhttpd-mod-ubus）；
 *  2. 失败则走 LuCI 表单登录（POST /cgi-bin/luci，保存 sysauth 会话 Cookie），
 *     后续通过 /cgi-bin/luci/admin/ubus JSON-RPC 代理调用 ubus。
 *
 * qmodem 后端探测顺序：ubus `qmodem`（新版）→ Lua HTTP 端点（旧版）→ ubus `modem_ctrl`（旧版批量）。
 *
 * 本类不依赖 Android SDK，可在 JVM 单元测试中直接验证。
 */
class LuciClient(
    private val schemeHost: String,
    private val port: Int,
    private val username: String,
    private val password: String
) {
    var transport: Transport = Transport.LUCI_PROXY
        private set
    var backend: Backend = Backend.UBUS_QMODEM
        private set
    var sessionId: String? = null
        private set

    private val reqId = AtomicLong(1)
    private var cookie: String? = null
    private var legacyToken: String? = null
    private var modemCtrlBulk: JSONArray? = null

    // ------------------------------------------------------------------
    // HTTP 基础
    // ------------------------------------------------------------------

    private class Resp(val status: Int, val body: String, val headers: Map<String, List<String>>)

    private fun http(
        method: String,
        urlStr: String,
        body: String? = null,
        contentType: String? = null,
        timeoutMs: Int = 25000,
        withCookie: Boolean = true
    ): Resp {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 6000
                readTimeout = timeoutMs
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "QModemAssistant/1.0")
                if (withCookie) cookie?.let { setRequestProperty("Cookie", it) }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", contentType ?: "application/json")
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    setFixedLengthStreamingMode(bytes.size)
                    outputStream.use { it.write(bytes); it.flush() }
                }
            }
            val status = conn.responseCode
            val stream: InputStream? = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val headers = HashMap<String, List<String>>()
            for ((k, v) in conn.headerFields ?: emptyMap()) {
                if (k != null) headers[k] = v
            }
            return Resp(status, text, headers)
        } catch (e: Exception) {
            val msg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            throw NetworkException("无法连接路由器：$msg", e)
        } finally {
            conn?.disconnect()
        }
    }

    private fun baseUrl(): String =
        if (port == 80 || (schemeHost.startsWith("https") && port == 443)) schemeHost
        else "$schemeHost:$port"

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun parseJson(text: String, what: String = "响应"): JSONObject {
        val t = text.trim()
        if (t.isEmpty()) throw NetworkException("$what 为空（可能未登录或固件不支持）")
        return try {
            JSONObject(t)
        } catch (e: Exception) {
            throw NetworkException("$what 不是有效的 JSON（可能未登录或地址错误）")
        }
    }

    private fun rpcBody(method: String, params: JSONArray): String {
        val o = JSONObject()
        o.put("jsonrpc", "2.0")
        o.put("id", reqId.incrementAndGet())
        o.put("method", method)
        o.put("params", params)
        return o.toString()
    }

    // ------------------------------------------------------------------
    // 登录
    // ------------------------------------------------------------------

    /** 登录路由器，建立会话。失败抛 LoginException / NetworkException */
    fun login() {
        // 方式 1：直连 /ubus（uhttpd-mod-ubus）
        try {
            val args = JSONObject()
                .put("username", username)
                .put("password", password)
                .put("timeout", 7200)
            val params = JSONArray().put("").put("session").put("login").put(args)
            val resp = http("POST", baseUrl() + "/ubus", rpcBody("call", params))
            if (resp.status == 200) {
                val j = parseJson(resp.body, "ubus 登录响应")
                val result = j.optJSONArray("result")
                if (result != null && result.optInt(0) == 0) {
                    val sid = result.optJSONObject(1)?.optString("ubus_rpc_session") ?: ""
                    if (sid.isNotEmpty()) {
                        transport = Transport.UBUS_DIRECT
                        sessionId = sid
                        cookie = null
                        return
                    }
                }
            }
        } catch (e: Exception) {
            // /ubus 不存在或网络问题 → 继续尝试 LuCI 表单登录
        }

        // 方式 2：LuCI 表单登录
        val form = "luci_username=${enc(username)}&luci_password=${enc(password)}"
        val resp = try {
            http("POST", baseUrl() + "/cgi-bin/luci/", form, "application/x-www-form-urlencoded")
        } catch (e: Exception) {
            throw LoginException("无法连接路由器：${e.message}")
        }
        val setCookies = resp.headers
            .filterKeys { it.equals("Set-Cookie", true) }
            .values
            .flatten()
        var sysauth: String? = null
        for (sc in setCookies) {
            val first = sc.split(";").first().trim()
            val name = first.substringBefore("=").trim().lowercase()
            if (name == "sysauth_http" || name == "sysauth" || name == "sysauth_https") {
                sysauth = first
            }
        }
        if (sysauth == null) {
            throw LoginException("登录失败：请检查用户名、密码和地址（HTTP ${resp.status}）")
        }
        cookie = sysauth
        sessionId = sysauth.substringAfter("=").trim()
        transport = Transport.LUCI_PROXY
    }

    /** 登录（若未登录）并执行 block；block 中遇到会话过期则重新登录后重试一次 */
    fun <T> withSession(block: () -> T): T {
        if (sessionId == null) login()
        return try {
            block()
        } catch (e: AuthExpiredException) {
            synchronized(this) { login() }
            block()
        }
    }

    // ------------------------------------------------------------------
    // ubus JSON-RPC
    // ------------------------------------------------------------------

    private fun ubusCallOnce(obj: String, method: String, args: JSONObject): JSONObject? {
        val sid = sessionId ?: throw AuthExpiredException("尚未登录")
        val params = JSONArray().put(sid).put(obj).put(method).put(args)
        val path = when (transport) {
            Transport.UBUS_DIRECT -> "/ubus"
            Transport.LUCI_PROXY -> "/cgi-bin/luci/admin/ubus/$obj.$method"
        }
        val resp = http("POST", baseUrl() + path, rpcBody("call", params))
        if (resp.status == 401 || resp.status == 403) {
            throw AuthExpiredException("会话已失效（HTTP ${resp.status}）")
        }
        val j = parseJson(resp.body, "ubus 响应")
        val err = j.optJSONObject("error")
        if (err != null) {
            val code = err.optInt("code")
            val msg = err.optString("message")
            if (code == -32002 || msg.contains("Unauthorized", true) ||
                msg.contains("access denied", true) || msg.contains("session", true)
            ) {
                throw AuthExpiredException("会话已失效：$msg")
            }
            throw RpcException(-1, "RPC 错误：$msg")
        }
        val result = j.optJSONArray("result") ?: throw RpcException(-1, "ubus 响应缺少 result")
        val ubusCode = result.optInt(0, -1)
        if (ubusCode != 0) throw RpcException(ubusCode, ubusStatusText(ubusCode))
        return if (result.length() > 1) result.optJSONObject(1) else JSONObject()
    }

    fun ubusCall(
        obj: String,
        method: String,
        args: JSONObject = JSONObject(),
        allowRelogin: Boolean = true
    ): JSONObject? {
        return try {
            ubusCallOnce(obj, method, args)
        } catch (e: AuthExpiredException) {
            if (!allowRelogin) throw e
            synchronized(this) { login() }
            ubusCallOnce(obj, method, args)
        }
    }

    private fun ubusStatusText(code: Int): String = when (code) {
        1 -> "无效命令"
        2 -> "无效参数"
        3 -> "方法不存在"
        4 -> "对象不存在（未安装对应插件？）"
        5 -> "无数据"
        6 -> "权限不足"
        7 -> "执行超时"
        8 -> "操作不支持"
        10 -> "连接失败"
        else -> "ubus 错误 $code"
    }

    // ------------------------------------------------------------------
    // 旧版 Lua LuCI HTTP 端点
    // ------------------------------------------------------------------

    private fun fetchLegacyToken(): String {
        legacyToken?.let { return it }
        val resp = http("GET", baseUrl() + "/cgi-bin/luci/admin/modem/qmodem/modem_info")
        val m = Regex("name=\"token\"[^>]*value=\"([0-9a-fA-F]+)\"").find(resp.body)
        val t = m?.groupValues?.get(1) ?: throw RpcException(-1, "无法获取 LuCI 表单 token")
        legacyToken = t
        return t
    }

    private fun legacyCall(action: String, cfg: String): JSONObject {
        val qs = "action=${enc(action)}&cfg=${enc(cfg)}"
        var resp = http("GET", baseUrl() + "/cgi-bin/luci/admin/modem/qmodem/modem_ctrl?$qs")
        if (resp.status == 403 || resp.status == 401) {
            // 可能需要 CSRF token，改用 POST
            val token = try {
                fetchLegacyToken()
            } catch (e: Exception) {
                throw AuthExpiredException("会话已失效（HTTP ${resp.status}）")
            }
            resp = http(
                "POST",
                baseUrl() + "/cgi-bin/luci/admin/modem/qmodem/modem_ctrl",
                "$qs&token=${enc(token)}",
                "application/x-www-form-urlencoded"
            )
        }
        if (resp.status == 401 || resp.status == 403) {
            throw AuthExpiredException("会话已失效（HTTP ${resp.status}）")
        }
        return parseJson(resp.body, "qmodem 接口响应")
    }

    // ------------------------------------------------------------------
    // 模组列表
    // ------------------------------------------------------------------

    /** 登录并探测 qmodem 后端，返回模组列表 */
    fun connect(): List<ModemSection> {
        login()
        val modems = getModems()
        if (modems.isEmpty()) {
            throw LoginException("连接成功，但未发现任何模组：请确认已安装 luci-app-qmodem 并正确识别模组")
        }
        if (backend != Backend.UBUS_MODEM_CTRL) detectBackend(modems.first())
        return modems
    }

    private fun modemsFromUci(): List<ModemSection> {
        val data = ubusCall("uci", "get", JSONObject().put("config", "qmodem"), allowRelogin = false)
            ?: return emptyList()
        val values = data.optJSONObject("values") ?: return emptyList()
        val out = ArrayList<ModemSection>()
        val keys = values.names() ?: return emptyList()
        for (i in 0 until keys.length()) {
            val key = keys.optString(i)
            val so = values.optJSONObject(key) ?: continue
            val type = so.optString(".type", "")
            if (type != "modem-device" && !type.contains("modem")) continue
            if (so.optString("state", "") == "disabled") continue
            val nm = so.optString("name", "").ifEmpty { so.optString("alias", "") }
            val label = if (nm.isNotEmpty() && nm != key) "$nm ($key)" else key
            val atPort = so.optString("at_port", "").ifEmpty { null }
            out.add(ModemSection(key, label, atPort))
        }
        return out
    }

    private fun modemsFromLegacyCfg(): List<ModemSection> {
        val data = legacyList()
        val arr = data.optJSONArray("cfgs") ?: return emptyList()
        val out = ArrayList<ModemSection>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val cfg = o.optString("cfg", "")
            if (cfg.isEmpty()) continue
            val nm = o.optString("name", cfg)
            out.add(ModemSection(cfg, nm, o.optString("at_port", "").ifEmpty { null }))
        }
        return out
    }

    private fun legacyList(): JSONObject {
        var resp = http("GET", baseUrl() + "/cgi-bin/luci/admin/modem/qmodem/get_modem_cfg")
        if (resp.status == 401 || resp.status == 403) {
            val token = fetchLegacyToken()
            resp = http(
                "POST",
                baseUrl() + "/cgi-bin/luci/admin/modem/qmodem/get_modem_cfg",
                "token=${enc(token)}",
                "application/x-www-form-urlencoded"
            )
        }
        if (resp.status == 401 || resp.status == 403) {
            throw AuthExpiredException("会话已失效（HTTP ${resp.status}）")
        }
        return parseJson(resp.body, "模组列表响应")
    }

    private fun modemsFromModemCtrlBulk(): List<ModemSection> {
        val data = ubusCall("modem_ctrl", "info", JSONObject(), allowRelogin = false)
            ?: return emptyList()
        val arr = data.optJSONArray("info") ?: return emptyList()
        val out = ArrayList<ModemSection>()
        for (i in 0 until arr.length()) {
            val entries = arr.optJSONObject(i)?.optJSONArray("modem_info")
            var nm = ""
            if (entries != null) {
                for (k in 0 until entries.length()) {
                    val e = entries.optJSONObject(k)
                    if (e?.optString("key") == "name") {
                        nm = e.optString("value")
                        break
                    }
                }
            }
            out.add(ModemSection("#$i", nm.ifEmpty { "模组 ${i + 1}" }))
        }
        modemCtrlBulk = arr
        return out
    }

    fun getModems(): List<ModemSection> {
        try {
            val l = modemsFromUci()
            if (l.isNotEmpty()) return l
        } catch (_: Exception) {
        }
        if (transport == Transport.LUCI_PROXY) {
            try {
                val l = modemsFromLegacyCfg()
                if (l.isNotEmpty()) return l
            } catch (_: Exception) {
            }
        }
        try {
            val l = modemsFromModemCtrlBulk()
            if (l.isNotEmpty()) {
                backend = Backend.UBUS_MODEM_CTRL
                return l
            }
        } catch (_: Exception) {
        }
        return emptyList()
    }

    private fun detectBackend(section: ModemSection) {
        try {
            ubusCall("qmodem", "base_info", JSONObject().put("config_section", section.id), allowRelogin = false)
            backend = Backend.UBUS_QMODEM
            return
        } catch (_: Exception) {
        }
        if (transport == Transport.LUCI_PROXY) {
            try {
                legacyCall("info", section.id)
                backend = Backend.LEGACY_HTTP
                return
            } catch (_: Exception) {
            }
        }
        try {
            ubusCall("modem_ctrl", "info", JSONObject(), allowRelogin = false)
            backend = Backend.UBUS_MODEM_CTRL
            return
        } catch (_: Exception) {
        }
        throw RpcException(-1, "未检测到 qmodem 插件接口，请确认路由器已安装 luci-app-qmodem")
    }

    // ------------------------------------------------------------------
    // 信息读取
    // ------------------------------------------------------------------

    /** 拉取并合并某个模组的全部信息条目 */
    fun fetchInfo(section: ModemSection): List<InfoEntry> {
        return withSession { fetchInfoOnce(section) }
    }

    private fun fetchInfoOnce(section: ModemSection): List<InfoEntry> {
        val merged = LinkedHashMap<Pair<String, String>, InfoEntry>()

        fun addEntries(arr: JSONArray) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val type = o.optString("type", "plain_text")
                if (type == "avalible_band") continue
                val key = o.optString("key", "")
                if (key.isEmpty() && type != "warning_message") continue
                val value = o.optString("value", "")
                if (value.isEmpty() && type != "warning_message") continue
                val e = InfoEntry(
                    key = key,
                    value = value,
                    fullName = o.optString("full_name", key),
                    type = type,
                    minValue = o.optString("min_value", "").toDoubleOrNull(),
                    maxValue = o.optString("max_value", "").toDoubleOrNull(),
                    unit = o.optString("unit", "").ifEmpty { null },
                    group = o.optString("class", "").ifEmpty { "其他信息" },
                    extraInfo = o.optString("extra_info", "").ifEmpty { null }
                )
                val k = e.group to e.key
                if (!merged.containsKey(k)) merged[k] = e
            }
        }

        if (backend == Backend.UBUS_MODEM_CTRL) {
            val bulk = modemCtrlBulk
            val idx = section.id.removePrefix("#").toIntOrNull() ?: 0
            val arr = bulk?.optJSONObject(idx)?.optJSONArray("modem_info")
            if (arr != null) addEntries(arr)
            return merged.values.toList()
        }

        val methods = listOf("info", "base_info", "cell_info", "network_info", "sim_info")
        for (m in methods) {
            try {
                val data: JSONObject? = if (backend == Backend.UBUS_QMODEM) {
                    ubusCall("qmodem", m, JSONObject().put("config_section", section.id))
                } else {
                    legacyCall(m, section.id)
                }
                val arr = data?.optJSONArray("modem_info")
                if (arr != null) addEntries(arr)
            } catch (e: RpcException) {
                // 单个方法缺失/不支持时不影响其它方法
                if (e.ubusCode !in intArrayOf(3, 4, 8, 2)) throw e
            }
        }
        return merged.values.toList()
    }

    // ------------------------------------------------------------------
    // 断开
    // ------------------------------------------------------------------

    fun logout() {
        try {
            sessionId?.let {
                if (transport == Transport.UBUS_DIRECT) {
                    val params = JSONArray().put(it).put("session").put("logout").put(JSONObject())
                    http("POST", baseUrl() + "/ubus", rpcBody("call", params), withCookie = false)
                } else {
                    val params = JSONArray().put(it).put("session").put("logout").put(JSONObject())
                    http("POST", baseUrl() + "/cgi-bin/luci/admin/ubus/session.logout", rpcBody("call", params))
                }
            }
        } catch (_: Exception) {
        } finally {
            sessionId = null
            cookie = null
        }
    }
}
