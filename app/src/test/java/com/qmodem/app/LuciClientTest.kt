package com.qmodem.app

import com.qmodem.app.model.InfoEntry
import com.qmodem.app.model.SignalQuality
import com.qmodem.app.net.Backend
import com.qmodem.app.net.InfoAnalyzer
import com.qmodem.app.net.LuciClient
import com.qmodem.app.net.Transport
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 用 MockWebServer 模拟 OpenWrt LuCI / qmodem 接口，验证 LuciClient 的
 * 登录（/ubus 直连 + LuCI 表单）、ubus 代理调用、模组枚举、信息合并、会话过期重登等逻辑。
 */
class LuciClientTest {

    private lateinit var server: MockWebServer

    // 可配置的模拟行为（测试方法内可随时修改）
    var ubusEndpointEnabled = false        // /ubus 直连是否可用
    var failUbusProxy = false              // admin/ubus 是否不可用（旧版回退）
    var expireNextRequest = false          // 下一次请求返回 401
    var dialRunning = false                // dial_status 返回值
    var preferArrayFormat = false          // get_network_prefer 返回数组包对象
    private val validSessions = mutableSetOf("sessAAAAAAAAAAAAAAAAAAAAAAAAAA1")
    // 记录最近收到的写操作参数（用于断言）
    private var lastAtCmd: String? = null
    private var lastAtPort: String? = null
    private var lastSendAtParams: org.json.JSONObject? = null
    private var lastPrefer: org.json.JSONArray? = null
    private var lastUsbMode: String? = null
    private var lastRebootMethod: String? = null

    /** 模拟 ubus 对象 qmodem 的各方法返回（/ubus 直连与 admin/ubus 代理共用） */
    private fun qmodemMock(method: String, params: JSONArray): MockResponse {
        return when (method) {
            "get_at_cfg" -> rpcOk(
                JSONObject().put(
                    "at_cfg",
                    JSONObject()
                        .put("using_port", "/dev/ttyUSB2")
                        .put("ports", JSONArray().put("/dev/ttyUSB2").put("/dev/ttyUSB3"))
                        .put("valid_ports", JSONArray().put("/dev/ttyUSB2"))
                        .put("other_ttys", JSONArray().put("/dev/ttyUSB0"))
                        .put("at_port", "/dev/ttyUSB2")
                )
            )
            "send_at" -> {
                val p = params.getJSONObject(3).getJSONObject("params")
                lastSendAtParams = JSONObject(p.toString())
                lastAtCmd = p.getString("at")
                lastAtPort = p.getString("port")
                val at = p.getString("at")
                val res = when {
                    at.contains("QENG") ->
                        "+QENG: \"servingcell\",\"NOCONN\",\"LTE\",\"FDD\",460,01,E256701,301,1300,3,20,20,20,-85,-10,-73,12,17\n\nOK"
                    at.contains("CSQ") -> "+CSQ: 25,99\n\nOK"
                    else -> "OK\nmock response"
                }
                rpcOk(
                    JSONObject().put(
                        "at_cfg",
                        JSONObject()
                            .put("status", "1")
                            .put("cmd", "at ${p.getString("port")} $at")
                            .put("res", res)
                    )
                )
            }
            "get_network_prefer" -> rpcOk(
                if (preferArrayFormat) {
                    // 某些固件 jshn 输出：数组包对象
                    JSONObject().put(
                        "network_prefer",
                        JSONArray().put(JSONObject().put("3G", "1").put("4G", "0").put("5G", "1"))
                    )
                } else {
                    JSONObject().put(
                        "network_prefer",
                        JSONObject().put("3G", "0").put("4G", "1").put("5G", "1")
                    )
                }
            )
            "set_network_prefer" -> {
                lastPrefer = params.getJSONObject(3).getJSONArray("params")
                rpcOk(JSONObject())
            }
            "get_mode" -> rpcOk(
                JSONObject().put(
                    "mode",
                    JSONObject().put("qmi", "0").put("ecm", "0")
                        .put("mbim", "1").put("rndis", "0").put("ncm", "0")
                )
            )
            "set_mode" -> {
                lastUsbMode = params.getJSONObject(3).getString("mode")
                rpcOk(JSONObject())
            }
            "dial_status" -> rpcOk(JSONObject().put("running", dialRunning))
            "modem_dial", "modem_hang", "modem_redial" -> rpcOk(
                JSONObject().put(
                    "result",
                    JSONObject().put("status", "1").put("message", "ok")
                )
            )
            "get_dial_log" -> rpcOk(JSONObject().put("log", "2026-11-27 dial start\nconnected"))
            "clear_dial_log" -> rpcOk(JSONObject())
            "get_reboot_caps" -> rpcOk(
                JSONObject().put(
                    "reboot_caps",
                    JSONObject().put("soft_reboot_caps", 1).put("hard_reboot_caps", 1)
                )
            )
            "do_reboot" -> {
                lastRebootMethod = params.getJSONObject(3).getJSONObject("params").getString("method")
                rpcOk(
                    JSONObject().put(
                        "result",
                        JSONObject().put("status", "1").put("method", lastRebootMethod)
                    )
                )
            }
            else -> rpcOk(JSONObject().put("modem_info", sampleModemInfo()))
        }
    }

    private fun entry(
        key: String, value: String, fullName: String, type: String = "plain_text",
        min: Double? = null, max: Double? = null, unit: String? = null, cls: String = "Cell Information"
    ): JSONObject {
        val o = JSONObject()
            .put("key", key)
            .put("value", value)
            .put("full_name", fullName)
            .put("type", type)
            .put("class", cls)
        if (min != null) o.put("min_value", min)
        if (max != null) o.put("max_value", max)
        if (unit != null) o.put("unit", unit)
        return o
    }

    private fun sampleModemInfo(): JSONArray {
        return JSONArray()
            .put(entry("name", "RM500Q", "Name", cls = "Base Information"))
            .put(entry("manufacturer", "Quectel", "Manufacturer", cls = "Base Information"))
            .put(entry("temperature", "42", "Temperature", cls = "Base Information"))
            .put(entry("SIM Status", "Ready", "SIM Status", cls = "SIM Information"))
            .put(entry("ISP", "中国电信", "ISP", cls = "SIM Information"))
            .put(entry("IMEI", "860000000000000", "IMEI", cls = "SIM Information"))
            .put(entry("Network Type", "LTE", "Network Type", cls = "Network Information"))
            .put(entry("MCC", "460", "Mobile Country Code", cls = "Cell Information"))
            .put(entry("MNC", "11", "Mobile Network Code", cls = "Cell Information"))
            .put(entry("TAC", "12345", "Tracking Area Code", cls = "Cell Information"))
            .put(entry("Cell ID", "123456789", "Cell ID", cls = "Cell Information"))
            .put(entry("Physical Cell ID", "300", "Physical Cell ID", cls = "Cell Information"))
            .put(entry("EARFCN", "1300", "EARFCN", cls = "Cell Information"))
            .put(entry("Band", "B1", "Band", cls = "Cell Information"))
            .put(entry("RSRP", "-85", "Reference Signal Received Power", "progress_bar", -140.0, -44.0, "dBm"))
            .put(entry("RSRQ", "-9", "Reference Signal Received Quality", "progress_bar", -19.5, -3.0, "dB"))
            .put(entry("SINR", "22", "SINR", "progress_bar", 0.0, 30.0, "dB"))
    }

    private fun uciValues(): JSONObject = JSONObject().put(
        "values",
        JSONObject()
            .put(
                "modem1",
                JSONObject().put(".name", "modem1").put(".type", "modem-device").put("name", "RM500Q")
            )
            .put(
                "modem2",
                JSONObject().put(".name", "modem2").put(".type", "modem-device").put("state", "disabled")
            )
    )

    private fun rpcOk(data: JSONObject?): MockResponse {
        val result = JSONArray()
        result.put(0)
        if (data != null) result.put(data)
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("result", result)
            .toString()
        return MockResponse().setResponseCode(200).setBody(body)
    }

    private fun rpcErr(code: Int): MockResponse = MockResponse().setResponseCode(200).setBody(
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("result", JSONArray().put(code))
            .toString()
    )

    private fun jsonOk(obj: JSONObject): MockResponse =
        MockResponse().setResponseCode(200).setBody(obj.toString())

    private fun rpcParams(body: String): JSONArray = JSONObject(body).getJSONArray("params")

    @Before
    fun setup() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val body = request.body.readUtf8()

                // 1) uhttpd /ubus 直连
                if (path == "/ubus") {
                    if (!ubusEndpointEnabled) return MockResponse().setResponseCode(404)
                    val params = rpcParams(body)
                    val obj = params.getString(1)
                    val method = params.getString(2)
                    if (obj == "session" && method == "login") {
                        val args = params.getJSONObject(3)
                        return if (args.getString("password") == "goodpass") {
                            rpcOk(
                                JSONObject()
                                    .put("ubus_rpc_session", "sessAAAAAAAAAAAAAAAAAAAAAAAAAA1")
                                    .put("timeout", 7200)
                            )
                        } else {
                            rpcErr(6)
                        }
                    }
                    if (obj == "qmodem") {
                        return qmodemMock(method, params)
                    }
                    if (obj == "uci") {
                        return rpcOk(uciValues())
                    }
                    return rpcErr(4)
                }

                // 2) LuCI 表单登录（POST /cgi-bin/luci/）
                if (path == "/cgi-bin/luci/" || path == "/cgi-bin/luci") {
                    val ok = body.contains("luci_username=root") && body.contains("luci_password=goodpass")
                    return if (ok) {
                        MockResponse()
                            .setResponseCode(302)
                            .addHeader(
                                "Set-Cookie",
                                "sysauth_http=sessAAAAAAAAAAAAAAAAAAAAAAAAAA1; Path=/cgi-bin/luci; HttpOnly"
                            )
                    } else {
                        MockResponse().setResponseCode(200)
                            .setBody("<html><body><input name='luci_username'><input name='luci_password'></body></html>")
                    }
                }

                // 3) LuCI admin/ubus JSON-RPC 代理
                if (path.startsWith("/cgi-bin/luci/admin/ubus")) {
                    if (failUbusProxy) return MockResponse().setResponseCode(404)
                    val cookie = request.getHeader("Cookie") ?: ""
                    val params = rpcParams(body)
                    val sid = params.getString(0)
                    if (!cookie.contains("sysauth") || sid !in validSessions) {
                        return MockResponse().setResponseCode(401)
                    }
                    if (expireNextRequest) {
                        expireNextRequest = false
                        return MockResponse().setResponseCode(401)
                    }
                    val obj = params.getString(1)
                    val method = params.getString(2)
                    return when {
                        obj == "qmodem" -> qmodemMock(method, params)
                        obj == "uci" -> rpcOk(uciValues())
                        else -> rpcErr(4)
                    }
                }

                // 4) 旧版 Lua HTTP 端点
                if (path.startsWith("/cgi-bin/luci/admin/modem/qmodem/get_modem_cfg")) {
                    val cookie = request.getHeader("Cookie") ?: ""
                    if (!cookie.contains("sysauth")) return MockResponse().setResponseCode(403)
                    if (expireNextRequest) {
                        expireNextRequest = false
                        return MockResponse().setResponseCode(401)
                    }
                    val cfgs = JSONArray()
                        .put(
                            JSONObject()
                                .put("name", "RM500Q-AE")
                                .put("at_port", "/dev/ttyUSB2")
                                .put("cfg", "modem1")
                        )
                    return jsonOk(JSONObject().put("cfgs", cfgs))
                }
                if (path.startsWith("/cgi-bin/luci/admin/modem/qmodem/modem_ctrl")) {
                    val cookie = request.getHeader("Cookie") ?: ""
                    if (!cookie.contains("sysauth")) return MockResponse().setResponseCode(403)
                    if (expireNextRequest) {
                        expireNextRequest = false
                        return MockResponse().setResponseCode(401)
                    }
                    return jsonOk(JSONObject().put("modem_info", sampleModemInfo()))
                }

                return MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    private fun client(pass: String = "goodpass") =
        LuciClient("http://127.0.0.1", server.port, "root", pass)

    @Test
    fun `login via luci form when ubus endpoint absent`() {
        val c = client()
        c.login()
        assertEquals(Transport.LUCI_PROXY, c.transport)
        assertEquals("sessAAAAAAAAAAAAAAAAAAAAAAAAAA1", c.sessionId)
    }

    @Test
    fun `login via direct ubus`() {
        ubusEndpointEnabled = true
        val c = client()
        c.login()
        assertEquals(Transport.UBUS_DIRECT, c.transport)
    }

    @Test
    fun `wrong password throws`() {
        val c = client(pass = "badpass")
        var thrown = false
        try {
            c.login()
        } catch (e: Exception) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun `connect detects backend and lists modems via uci`() {
        val c = client()
        val modems = c.connect()
        assertEquals(Backend.UBUS_QMODEM, c.backend)
        assertEquals(1, modems.size)
        assertEquals("modem1", modems[0].id)
        assertEquals("RM500Q (modem1)", modems[0].name)
    }

    @Test
    fun `fetch info merges modem_info entries`() {
        val c = client()
        val modems = c.connect()
        val entries = c.fetchInfo(modems.first())
        val rsrp = entries.firstOrNull { it.key == "RSRP" }
        assertNotNull(rsrp)
        assertEquals(-140.0, rsrp!!.minValue!!, 1e-9)
        assertEquals(-44.0, rsrp.maxValue!!, 1e-9)
        assertEquals("dBm", rsrp.unit)
        val dup = entries.groupBy { it.group to it.key }.filter { it.value.size > 1 }
        assertTrue("存在重复条目: $dup", dup.isEmpty())
    }

    @Test
    fun `relogin on session expiry`() {
        val c = client()
        val modems = c.connect()
        expireNextRequest = true
        val entries = c.fetchInfo(modems.first())
        assertTrue(entries.isNotEmpty())
    }

    @Test
    fun `legacy fallback when ubus proxy unavailable`() {
        failUbusProxy = true
        val c = client()
        val modems = c.connect()
        assertEquals(Backend.LEGACY_HTTP, c.backend)
        assertEquals(1, modems.size)
        assertEquals("modem1", modems[0].id)
        val entries = c.fetchInfo(modems.first())
        assertTrue(entries.any { it.key == "RSRP" })
    }

    // ---------------- InfoAnalyzer ----------------

    private fun entries(list: List<JSONObject>): List<InfoEntry> {
        return list.map { o ->
            InfoEntry(
                key = o.getString("key"),
                value = o.getString("value"),
                fullName = o.getString("full_name"),
                type = o.getString("type"),
                minValue = o.optDouble("min_value", Double.NaN).takeIf { !it.isNaN() },
                maxValue = o.optDouble("max_value", Double.NaN).takeIf { !it.isNaN() },
                unit = o.optString("unit", "").ifEmpty { null },
                group = o.optString("class", "")
            )
        }
    }

    private fun sampleEntries(): List<JSONObject> {
        val arr = sampleModemInfo()
        return (0 until arr.length()).map { arr.optJSONObject(it)!! }
    }

    @Test
    fun `analyzer extracts hero fields`() {
        val es = entries(sampleEntries())
        assertEquals("LTE", InfoAnalyzer.networkMode(es))
        assertEquals("中国电信", InfoAnalyzer.operator(es))
        assertEquals("B1", InfoAnalyzer.band(es))
        assertEquals("42 ℃", InfoAnalyzer.temperature(es))
        val bars = InfoAnalyzer.signalBars(es)
        assertTrue(bars.any { it.key == "RSRP" })
        assertTrue(bars.any { it.key == "RSRQ" })
        assertTrue(bars.any { it.key == "SINR" })
        val primary = InfoAnalyzer.primarySignal(es)
        assertNotNull(primary)
        assertEquals("RSRP", primary!!.key)
        assertEquals(-85.0, primary.numValue!!, 1e-9)
    }

    @Test
    fun `analyzer quality thresholds`() {
        val rsrp = { v: String ->
            entries(listOf(entry("RSRP", v, "RSRP", "progress_bar", -140.0, -44.0, "dBm")))
        }
        assertEquals(SignalQuality.EXCELLENT, InfoAnalyzer.quality(rsrp("-75")[0]))
        assertEquals(SignalQuality.GOOD, InfoAnalyzer.quality(rsrp("-85")[0]))
        assertEquals(SignalQuality.FAIR, InfoAnalyzer.quality(rsrp("-95")[0]))
        assertEquals(SignalQuality.POOR, InfoAnalyzer.quality(rsrp("-110")[0]))
    }

    @Test
    fun `network mode prefers NR5G keys`() {
        val es = entries(
            listOf(
                entry("NR5G-NSA", "NR5G-NSA", "", cls = "Cell Information"),
                entry("LTE", "LTE", "", cls = "Cell Information")
            )
        )
        assertEquals("5G NSA", InfoAnalyzer.networkMode(es))
    }

    @Test
    fun `n-a values tolerated`() {
        val es = entries(listOf(entry("RSRP", "N/A", "RSRP", "progress_bar", -140.0, -44.0, "dBm")))
        assertFalse(InfoAnalyzer.signalBars(es).isNotEmpty())
    }

    // ---------------- 频段映射（BandMap）----------------

    @Test
    fun `earfcn 1798 maps to lte b3 1800mhz`() {
        val bi = com.qmodem.app.net.BandMap.fromChannel(1798, "LTE")!!
        assertEquals(3, bi.bandNo)
        assertEquals(1864.8, bi.freqMhz!!, 0.01)
        assertEquals("1800 MHz", bi.common)
        assertEquals("B3 · 1864.8 MHz", bi.badge())
    }

    @Test
    fun `earfcn 40625 maps to lte b41`() {
        val bi = com.qmodem.app.net.BandMap.fromChannel(40625, "LTE")!!
        assertEquals(41, bi.bandNo)
        assertEquals(2593.5, bi.freqMhz!!, 0.01)
    }

    @Test
    fun `nr arfcn maps to n78 and n41`() {
        val n78 = com.qmodem.app.net.BandMap.fromChannel(636668, "NR")!!
        assertEquals(78, n78.bandNo)
        assertEquals(3550.02, n78.freqMhz!!, 0.02)
        val n41 = com.qmodem.app.net.BandMap.fromChannel(510000, "NR")!!
        assertEquals(41, n41.bandNo)
        assertEquals(2550.0, n41.freqMhz!!, 0.01)
    }

    @Test
    fun `uarfcn maps to wcdma b1`() {
        val bi = com.qmodem.app.net.BandMap.fromChannel(10713, "WCDMA")!!
        assertEquals(1, bi.bandNo)
        assertEquals(2142.6, bi.freqMhz!!, 0.2)
    }

    @Test
    fun `plmn codes translate to chinese carrier`() {
        assertEquals("中国联通", InfoAnalyzer.plmnName("46001"))
        assertEquals("中国电信", InfoAnalyzer.plmnName("46011"))
        assertEquals("中国移动", InfoAnalyzer.plmnName("46000"))
        assertEquals("中国广电", InfoAnalyzer.plmnName("46016"))
        // 6 位码取前 5 位
        assertEquals("中国联通", InfoAnalyzer.plmnName("460010"))
        assertNullSafe(InfoAnalyzer.plmnName("中国电信"))
        assertNullSafe(InfoAnalyzer.plmnName("abc"))
    }

    private fun assertNullSafe(v: Any?) {
        org.junit.Assert.assertNull(v)
    }

    @Test
    fun `isp numeric plmn shown as carrier in badge`() {
        val es = entries(listOf(entry("ISP", "46001", "ISP", cls = "SIM Information")))
        assertEquals("中国联通", InfoAnalyzer.operator(es))
    }

    @Test
    fun `earfcn row is annotated with band info`() {
        val es = entries(
            listOf(
                entry("EARFCN", "1798", "EARFCN", cls = "Cell Information"),
                entry("MCC", "460", "Mobile Country Code", cls = "Cell Information"),
                entry("MNC", "01", "Mobile Network Code", cls = "Cell Information"),
                entry("connect_status", "Yes", "connect_status", cls = "Base Information"),
                entry("SIM Status", "Ready", "SIM Status", cls = "SIM Information")
            )
        )
        val earfcn = es.first { it.key == "EARFCN" }
        val annotated = InfoAnalyzer.annotateValue(earfcn, es)
        assertEquals("1798（B3 · 1864.8 MHz）", annotated)
        val mnc = es.first { it.key == "MNC" }
        assertEquals("01（中国联通）", InfoAnalyzer.annotateValue(mnc, es))
        val mcc = es.first { it.key == "MCC" }
        assertEquals("460（中国）", InfoAnalyzer.annotateValue(mcc, es))
        val conn = es.first { it.key == "connect_status" }
        assertEquals("是", InfoAnalyzer.annotateValue(conn, es))
        val sim = es.first { it.key == "SIM Status" }
        assertEquals("就绪", InfoAnalyzer.annotateValue(sim, es))
    }

    @Test
    fun `band badge combines band field and earfcn frequency`() {
        val es = entries(
            listOf(
                entry("EARFCN", "1798", "EARFCN", cls = "Cell Information"),
                entry("Band", "3", "Band", cls = "Cell Information"),
                entry("Network Type", "FDD LTE", "Network Type", cls = "Network Information")
            )
        )
        assertEquals("B3 · 1864.8 MHz", InfoAnalyzer.bandBadge(es))
    }

    @Test
    fun `band badge works without band field`() {
        val es = entries(
            listOf(
                entry("NR-ARFCN", "636668", "NR-ARFCN", cls = "Cell Information"),
                entry("Network Type", "NR5G-SA", "Network Type", cls = "Network Information")
            )
        )
        val badge = InfoAnalyzer.bandBadge(es)
        assertTrue(badge!!.startsWith("n78"))
        assertTrue(badge.contains("GHz"))
    }

    @Test
    fun `operator via mcc mnc with padding`() {
        val es = entries(
            listOf(
                entry("MCC", "460", "Mobile Country Code", cls = "Cell Information"),
                entry("MNC", "1", "Mobile Network Code", cls = "Cell Information")
            )
        )
        assertEquals("中国联通", InfoAnalyzer.operator(es))
    }

    // ---------------- qmodem 功能（设置/调试） ----------------

    private fun connectedClient(): LuciClient {
        val c = client()
        c.connect()
        return c
    }

    @Test
    fun `at config loads ports and send_at returns response`() {
        val c = connectedClient()
        val modem = c.getModems().first()
        val cfg = c.getAtConfig(modem)
        assertTrue(cfg.ports.contains("/dev/ttyUSB2"))
        // 后端字段名为 using_port
        assertEquals("/dev/ttyUSB2", cfg.defaultPort)
        // CSQ 命令 mock 返回真实格式，可校验解析链路
        val res = c.sendAt(modem, "AT+CSQ", "/dev/ttyUSB2")
        assertTrue(res.contains("+CSQ: 25,99"))
        val other = c.sendAt(modem, "ATI", "/dev/ttyUSB2")
        assertTrue(other.contains("mock response"))
        // 关键回归：不得传 use_ubus_flag（会走 at -u，多数路由器无 ubus_at_daemon 导致失败）
        assertNotNull(lastSendAtParams)
        assertFalse(lastSendAtParams!!.has("use_ubus_flag"))
        assertEquals("/dev/ttyUSB2", lastAtPort)
    }

    @Test
    fun `network prefer get and set`() {
        val c = connectedClient()
        val modem = c.getModems().first()
        val raw = c.getNetworkPrefer(modem)
        assertEquals("0", raw["3G"])
        assertEquals("1", raw["4G"])
        assertEquals("1", raw["5G"])
        assertFalse(c.preferEnabled(raw["3G"]))
        assertTrue(c.preferEnabled(raw["4G"]))
        c.setNetworkPrefer(modem, listOf("4G", "5G"))
        assertNotNull(lastPrefer)
        assertEquals(2, lastPrefer!!.length())
        assertEquals("4G", lastPrefer!!.getString(0))
        assertEquals("5G", lastPrefer!!.getString(1))
    }

    @Test
    fun `network prefer tolerates array wrapper`() {
        val c = connectedClient()
        val modem = c.getModems().first()
        preferArrayFormat = true
        val raw = c.getNetworkPrefer(modem)
        assertEquals("1", raw["3G"])
        assertEquals("0", raw["4G"])
        assertEquals("1", raw["5G"])
        assertTrue(c.preferEnabled(raw["3G"]))
        assertFalse(c.preferEnabled(raw["4G"]))
        preferArrayFormat = false
    }

    @Test
    fun `live signal parses qeng lte nr and csq fallback`() {
        val c = connectedClient()
        val modem = c.getModems().first()
        // LTE 行：尾部 5 个数字 = rsrp/rsrq/rssi/sinr/srxlev
        val lte = InfoAnalyzer.parseQeng(
            "+QENG: \"servingcell\",\"NOCONN\",\"LTE\",\"FDD\",460,01,E256701,301,1300,3,20,20,20,-85,-10,-73,12,17"
        )
        assertEquals(-85.0, lte["RSRP"]!!, 1e-9)
        assertEquals(-10.0, lte["RSRQ"]!!, 1e-9)
        assertEquals(-73.0, lte["RSSI"]!!, 1e-9)
        assertEquals(12.0, lte["SINR"]!!, 1e-9)

        // NR5G-SA 行：尾部 3 个数字 = rsrp/rsrq/sinr
        val nr = InfoAnalyzer.parseQeng(
            "+QENG: \"servingcell\",\"NOCONN\",\"NR5G-SA\",\"TDD\",460,01,,,-,N/A,301,350,20,-92,-11,15"
        )
        assertEquals(-92.0, nr["RSRP"]!!, 1e-9)
        assertEquals(-11.0, nr["RSRQ"]!!, 1e-9)
        assertEquals(15.0, nr["SINR"]!!, 1e-9)

        // NSA：NR 行优先于 LTE 行
        val nsa = InfoAnalyzer.parseQeng(
            "+QENG: \"servingcell\",\"NOCONN\",\"NR5G-NSA\",\"TDD\",460,01,,,-,N/A,301,78,20,-95,-12,8\n" +
                "+QENG: \"servingcell\",\"NOCONN\",\"LTE\",\"FDD\",460,01,23F7E01,301,1300,3,20,20,10,-88,-9,-70,10,15"
        )
        assertEquals(-95.0, nsa["RSRP"]!!, 1e-9)
        assertEquals(8.0, nsa["SINR"]!!, 1e-9)

        // CSQ 兜底
        val csq = InfoAnalyzer.parseCsq("+CSQ: 25,99\n\nOK")
        assertEquals(-63.0, csq["RSSI"]!!, 1e-9)

        // 完整链路：端口自动发现（get_at_cfg）→ send_at QENG → 解析
        val live = c.fetchLiveSignal(modem, null)
        assertEquals(-85.0, live["RSRP"]!!, 1e-9)
        assertEquals(12.0, live["SINR"]!!, 1e-9)
    }

    @Test
    fun `usb mode get and set`() {
        val c = connectedClient()
        val modem = c.getModems().first()
        val (cur, all) = c.getUsbMode(modem)
        assertEquals("mbim", cur)
        assertTrue(all.contains("qmi"))
        c.setUsbMode(modem, "rndis")
        assertEquals("rndis", lastUsbMode)
    }

    @Test
    fun `dial control status action and log`() {
        val c = connectedClient()
        val modem = c.getModems().first()
        assertFalse(c.dialStatus(modem))
        dialRunning = true
        assertTrue(c.dialStatus(modem))
        assertTrue(c.dialAction(modem, "modem_dial"))
        assertTrue(c.dialAction(modem, "modem_redial"))
        val log = c.getDialLog(modem)
        assertTrue(log.contains("dial start"))
        c.clearDialLog(modem)
    }

    @Test
    fun `reboot caps and do reboot`() {
        val c = connectedClient()
        val modem = c.getModems().first()
        val (soft, hard) = c.getRebootCaps(modem)
        assertTrue(soft)
        assertTrue(hard)
        c.doReboot(modem, "soft")
        assertEquals("soft", lastRebootMethod)
        c.doReboot(modem, "hard")
        assertEquals("hard", lastRebootMethod)
    }

    @Test
    fun `features rejected on legacy backend`() {
        failUbusProxy = true
        val c = client()
        c.connect()
        assertEquals(Backend.LEGACY_HTTP, c.backend)
        val modem = c.getModems().first()
        var msg: String? = null
        try {
            c.getAtConfig(modem)
        } catch (e: LuciClient.FeatureException) {
            msg = e.message
        }
        assertTrue(msg!!.contains("旧版"))
    }
}
