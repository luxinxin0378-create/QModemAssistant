package com.qmodem.app.net

import com.qmodem.app.model.InfoEntry
import com.qmodem.app.model.SignalQuality

/**
 * 从 modem_info 条目中提取关键字段并做汉化/评级。
 * 纯 JVM 实现，便于单元测试。
 */
object InfoAnalyzer {

    /** 常见 key → 中文名 */
    val KEY_ZH: Map<String, String> = mapOf(
        "name" to "模组型号",
        "manufacturer" to "制造商",
        "revision" to "固件版本",
        "at_port" to "AT 串口",
        "temperature" to "模组温度",
        "voltage" to "供电电压",
        "SIM Status" to "SIM 状态",
        "ISP" to "运营商（ISP）",
        "SIM Slot" to "SIM 卡槽",
        "SIM Number" to "SIM 号码",
        "IMEI" to "IMEI",
        "IMSI" to "IMSI",
        "ICCID" to "ICCID",
        "Network Type" to "网络类型",
        "network_mode" to "网络模式",
        "Network Mode" to "网络模式",
        "Band" to "频段",
        "EARFCN" to "EARFCN",
        "ARFCN" to "ARFCN",
        "UARFCN" to "UARFCN",
        "Physical Cell ID" to "物理小区 ID",
        "PCI" to "物理小区 ID",
        "Cell ID" to "小区 ID",
        "TAC" to "跟踪区码 TAC",
        "LAC" to "位置区码 LAC",
        "MCC" to "国家码 MCC",
        "MNC" to "网络码 MNC",
        "UL Bandwidth" to "上行带宽",
        "DL Bandwidth" to "下行带宽",
        "RSRP" to "RSRP 信号强度",
        "RSRQ" to "RSRQ 信号质量",
        "SINR" to "SINR 信噪比",
        "RSSI" to "RSSI 接收强度",
        "RSSNR" to "RSSNR",
        "RxLev" to "RxLev",
        "RSCP" to "RSCP",
        "Ec/Io" to "Ec/Io",
        "Ec/No" to "Ec/No",
        "CQI" to "CQI",
        "CQI UL" to "上行 CQI",
        "CQI DL" to "下行 CQI",
        "AMBR UL" to "上行 AMBR",
        "AMBR DL" to "下行 AMBR",
        "Duplex Mode" to "双工模式",
        "SCS" to "子载波间隔",
        "Srxlev" to "Srxlev",
        "TX Power" to "发射功率",
        "PSC" to "主扰码 PSC",
        "Rx Rate" to "接收速率",
        "Tx Rate" to "发送速率",
        "connect_status" to "连接状态",
        "Net State" to "连接状态",
        "net_state" to "连接状态",
        "ipv4_dns1" to "IPv4 DNS1",
        "ipv4_dns2" to "IPv4 DNS2",
        "ipv6_dns1" to "IPv6 DNS1",
        "ipv6_dns2" to "IPv6 DNS2"
    )

    /** 常见英文取值 → 中文（用于 Yes/Ready/Unknown 等状态值） */
    val VALUE_ZH: Map<String, String> = mapOf(
        "yes" to "是", "no" to "否", "ok" to "正常",
        "ready" to "就绪", "unknown" to "未知",
        "connected" to "已连接", "disconnect" to "未连接", "disconnected" to "未连接",
        "online" to "在线", "offline" to "离线",
        "active" to "激活", "inactive" to "未激活",
        "enabled" to "启用", "disabled" to "停用"
    )

    /** 常见 MCC → 国家/地区 */
    val COUNTRY_ZH: Map<String, String> = mapOf(
        "460" to "中国", "461" to "中国",
        "454" to "中国香港", "455" to "中国澳门", "466" to "中国台湾",
        "310" to "美国", "311" to "美国", "312" to "美国", "316" to "美国",
        "204" to "荷兰", "206" to "比利时", "208" to "法国", "214" to "西班牙",
        "222" to "意大利", "226" to "罗马尼亚", "234" to "英国", "262" to "德国",
        "240" to "瑞典", "242" to "挪威", "244" to "芬兰", "268" to "土耳其",
        "272" to "爱尔兰", "425" to "以色列", "410" to "巴基斯坦",
        "412" to "阿富汗", "419" to "科威特", "420" to "沙特", "422" to "阿曼",
        "424" to "阿联酋", "429" to "尼泊尔", "432" to "伊朗", "436" to "缅甸",
        "440" to "日本", "441" to "日本", "450" to "韩国", "452" to "越南",
        "456" to "柬埔寨", "457" to "老挝", "459" to "文莱", "470" to "孟加拉",
        "472" to "马尔代夫", "502" to "马来西亚", "515" to "菲律宾",
        "520" to "泰国", "525" to "新加坡", "528" to "印尼", "530" to "新西兰",
        "537" to "巴新", "545" to "斐济", "702" to "墨西哥", "704" to "危地马拉",
        "716" to "秘鲁", "724" to "巴西", "730" to "智利", "732" to "哥伦比亚",
        "630" to "刚果（金）", "639" to "肯尼亚", "640" to "坦桑尼亚",
        "621" to "尼日利亚", "655" to "南非", "604" to "喀麦隆", "608" to "布基纳法索"
    )

    /** 分组（原始 class）→ 卡片标题 */
    val GROUP_ZH: Map<String, String> = mapOf(
        "Base Information" to "设备信息",
        "SIM Information" to "SIM 卡信息",
        "Network Information" to "网络信息",
        "Cell Information" to "小区信息",
        "Other Information" to "其他信息"
    )

    /** 中国大陆常用 MCC/MNC → 运营商 */
    val PLMN_ZH: Map<String, String> = mapOf(
        "46000" to "中国移动", "46002" to "中国移动", "46004" to "中国移动",
        "46007" to "中国移动", "46008" to "中国移动",
        "46001" to "中国联通", "46006" to "中国联通", "46009" to "中国联通",
        "46003" to "中国电信", "46005" to "中国电信", "46011" to "中国电信",
        "46015" to "中国电信", "46020" to "中国铁通",
        "46016" to "中国广电", "46017" to "中国广电"
    )

    private fun findAny(entries: List<InfoEntry>, vararg keys: String): InfoEntry? {
        for (k in keys) {
            val hit = entries.firstOrNull { it.key.equals(k, true) }
            if (hit != null) return hit
        }
        return null
    }

    private fun findBar(entries: List<InfoEntry>, vararg keys: String): InfoEntry? {
        for (k in keys) {
            val hit = entries.firstOrNull { it.key.equals(k, true) && it.type == "progress_bar" }
            if (hit != null) return hit
        }
        return null
    }

    fun entryLabel(e: InfoEntry): String {
        KEY_ZH[e.key]?.let { return it }
        val extra = e.extraInfo
        if (!extra.isNullOrBlank() && e.key != extra) return e.key
        return e.key
    }

    fun groupLabel(group: String): String = GROUP_ZH[group] ?: group.ifEmpty { "其他信息" }

    /** 网络模式：优先识别 NR5G-SA / NR5G-NSA / LTE / WCDMA / GSM */
    fun networkMode(entries: List<InfoEntry>): String? {
        val has = { k: String -> entries.any { it.key.equals(k, true) || it.value.equals(k, true) } }
        when {
            has("NR5G-SA") -> return "5G SA"
            has("NR5G-NSA") -> return "5G NSA"
            has("LTE") -> return "LTE"
            has("WCDMA") -> return "WCDMA"
            has("GSM") -> return "GSM"
        }
        val v = findAny(entries, "Network Type", "network_mode", "Network Mode")?.value
        if (!v.isNullOrBlank()) {
            val up = v.uppercase()
            return when {
                up.contains("SA") && up.contains("5G") -> "5G SA"
                up.contains("NSA") -> "5G NSA"
                up.contains("NR") || up.contains("5G") -> "5G"
                up.contains("LTE") || up.contains("4G") -> "LTE"
                up.contains("WCDMA") || up.contains("3G") -> "WCDMA"
                up.contains("GSM") || up.contains("2G") -> "GSM"
                else -> v
            }
        }
        return null
    }

    /** PLMN 数字串（"46001"）→ 运营商中文名；非数字或未知返回 null */
    fun plmnName(raw: String): String? {
        val v = raw.trim()
        if (!Regex("\\d{4,6}").matches(v)) return null
        PLMN_ZH[v]?.let { return it }
        // 6 位码（如 460011）先取前 5 位，再尝试补 0 的 3 位 MNC
        if (v.length >= 5) {
            PLMN_ZH[v.take(5)]?.let { return it }
            PLMN_ZH[v.take(4) + "0"]?.let { return it }
        }
        if (v.length == 4) {
            PLMN_ZH[v + "0"]?.let { return it }
        }
        return null
    }

    /** 运营商：优先 ISP（数字 PLMN 自动转中文），其次 MCC/MNC 映射 */
    fun operator(entries: List<InfoEntry>): String? {
        findAny(entries, "ISP")?.value?.takeIf { it.isNotBlank() }?.let {
            return plmnName(it) ?: it
        }
        val mcc = findAny(entries, "MCC")?.value?.trim() ?: return null
        val mnc = findAny(entries, "MNC")?.value?.trim() ?: return null
        if (mcc.isEmpty() || mnc.isEmpty() || mcc == "0" || mnc == "0") return null
        val mncPad = mnc.padStart(2, '0')
        return PLMN_ZH["$mcc$mnc"] ?: PLMN_ZH["$mcc$mncPad"] ?: "$mcc-$mnc"
    }

    /** 频段（Band 字段原值，兼容旧逻辑） */
    fun band(entries: List<InfoEntry>): String? {
        return findAny(entries, "Band")?.value?.takeIf { it.isNotBlank() && it.lowercase() != "unknown" }
    }

    /** 从条目推断制式（网络模式 → RAN 名） */
    private fun ratOf(mode: String?): String = when {
        mode == null -> "LTE"
        mode.startsWith("5G") -> "NR"
        mode == "WCDMA" -> "WCDMA"
        mode == "GSM" -> "GSM"
        else -> "LTE"
    }

    /** 从频点号找条目 */
    private fun channelEntry(entries: List<InfoEntry>): InfoEntry? {
        return findAny(entries, "NR-ARFCN", "nRarfcn", "NRARFCN", "EARFCN", "UARFCN", "ARFCN")
    }

    /**
     * 综合推断频段信息：Band 字段给出频段号，EARFCN/NR-ARFCN 等频点推算下行频率，
     * 合并成「B3 · 1864.8 MHz」这类直观描述。
     */
    fun bandInfo(entries: List<InfoEntry>): BandMap.BandInfo? {
        val rawBand = findAny(entries, "Band")?.value?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("unknown", true) && !it.equals("n/a", true) }
        val mode = networkMode(entries)
        val ch = channelEntry(entries)?.value?.trim()?.toIntOrNull()
        val fromCh = ch?.let { BandMap.fromChannel(it, ratOf(mode)) }

        var rat = ratOf(mode)
        var bandNo: Int? = null
        rawBand?.let { rb ->
            BandMap.parseBandRaw(rb)?.let { (r, n) ->
                rat = r; bandNo = n
            } ?: run { bandNo = null }
        }
        if (bandNo == null) bandNo = fromCh?.bandNo
        val freq = fromCh?.freqMhz
        val common = fromCh?.common
        if (bandNo == null && freq == null && rawBand.isNullOrBlank()) return null
        return BandMap.BandInfo(rat, bandNo, freq, common, rawBand)
    }

    /** 频段徽标文本，如 "B3 · 1864.8 MHz"；无法推断时返回 null */
    fun bandBadge(entries: List<InfoEntry>): String? = bandInfo(entries)?.badge()

    /** MCC 国家名（仅数字时） */
    private fun countryName(v: String): String? {
        if (!Regex("\\d{3}").matches(v)) return null
        return COUNTRY_ZH[v]
    }

    /**
     * 行展示值：在原始数值后附加直观解释。
     * - EARFCN 1798 → "1798（B3 · 1864.8 MHz）"
     * - Band 3 → "3（B3 · 1864.8 MHz）"
     * - ISP 46001 → "中国联通（46001）"
     * - MCC/MNC → 附加国家/运营商名
     * - Yes/Ready → 是/就绪
     */
    fun annotateValue(e: InfoEntry, all: List<InfoEntry>): String {
        val k = e.key.uppercase()
        val raw = e.value.trim()

        // 频点号 → 附频段与频率
        val n = raw.toIntOrNull()
        if (n != null && (k.contains("ARFCN") || k.contains("UARFCN") || k.contains("EARFCN"))) {
            val rat = when {
                k.contains("NR") -> "NR"
                k.contains("UARFCN") -> "WCDMA"
                k == "ARFCN" -> ratOf(networkMode(all))
                else -> ratOf(networkMode(all))
            }
            BandMap.fromChannel(n, rat)?.let { bi ->
                bi.badge()?.let { return "$raw（$it）" }
            }
            return e.value
        }
        // Band → 附频率
        if (k == "BAND" && n != null) {
            val mode = networkMode(all)
            val ch = channelEntry(all)?.value?.trim()?.toIntOrNull()
            val fromCh = ch?.let { BandMap.fromChannel(it, ratOf(mode)) }
            val bi = BandMap.BandInfo(
                ratOf(mode), n, fromCh?.freqMhz, fromCh?.common, raw
            )
            bi.badge()?.let { return "$raw（$it）" }
            return e.value
        }
        // ISP：数字 PLMN → 中文
        if (k == "ISP") {
            plmnName(raw)?.let { return "$it（$raw）" }
            return e.value
        }
        // MNC：结合 MCC 得出运营商
        if (k == "MNC") {
            val mcc = findAny(all, "MCC")?.value?.trim()
            if (mcc != null && Regex("\\d{1,3}").matches(raw)) {
                val name = plmnName(mcc + raw) ?: plmnName(mcc + raw.padStart(2, '0'))
                if (name != null) return "$raw（$name）"
            }
            return e.value
        }
        // MCC：附国家
        if (k == "MCC") {
            countryName(raw)?.let { return "$raw（$it）" }
            return e.value
        }
        // 状态值翻译（Yes / Ready / Unknown …）
        VALUE_ZH[raw.lowercase()]?.let { return it }
        return displayValue(e)
    }

    /** 用于主仪表盘的信号条（按优先级） */
    fun primarySignal(entries: List<InfoEntry>): InfoEntry? {
        return findBar(entries, "RSRP", "RSCP", "RSSI", "RxLev")
            ?: findAny(entries, "RSRP", "RSCP", "RSSI", "RxLev")?.takeIf { it.numValue != null }
    }

    /** 主信号 + 单位（RSRP/RSCP 为 dBm，RSSI 为 dBm，RxLev 为 dBm） */
    fun signalUnit(e: InfoEntry): String = e.unit ?: "dBm"

    /** 按各指标阈值评级 */
    fun quality(e: InfoEntry): SignalQuality {
        val v = e.numValue ?: return SignalQuality.UNKNOWN
        val key = e.key.uppercase()
        return when {
            key == "RSRP" -> when {
                v >= -80 -> SignalQuality.EXCELLENT
                v >= -90 -> SignalQuality.GOOD
                v >= -100 -> SignalQuality.FAIR
                else -> SignalQuality.POOR
            }
            key == "RSRQ" -> when {
                v >= -10 -> SignalQuality.EXCELLENT
                v >= -15 -> SignalQuality.GOOD
                v >= -20 -> SignalQuality.FAIR
                else -> SignalQuality.POOR
            }
            key == "SINR" -> when {
                v >= 20 -> SignalQuality.EXCELLENT
                v >= 13 -> SignalQuality.GOOD
                v >= 0 -> SignalQuality.FAIR
                else -> SignalQuality.POOR
            }
            key == "RSSI" -> when {
                v >= -70 -> SignalQuality.EXCELLENT
                v >= -85 -> SignalQuality.GOOD
                v >= -100 -> SignalQuality.FAIR
                else -> SignalQuality.POOR
            }
            key == "RSCP" -> when {
                v >= -70 -> SignalQuality.EXCELLENT
                v >= -85 -> SignalQuality.GOOD
                v >= -100 -> SignalQuality.FAIR
                else -> SignalQuality.POOR
            }
            key == "RXLEV" -> when {
                v >= -70 -> SignalQuality.EXCELLENT
                v >= -85 -> SignalQuality.GOOD
                v >= -95 -> SignalQuality.FAIR
                else -> SignalQuality.POOR
            }
            else -> SignalQuality.UNKNOWN
        }
    }

    /** 展示值（数字保留原样，非法值显示原文本） */
    fun displayValue(e: InfoEntry): String {
        val unit = e.unit
        return if (unit != null && e.numValue != null) "${e.value} $unit" else e.value
    }

    /** 信号条（主卡内展示）：RSRP / RSRQ / SINR / RSSI / RSCP / RxLev */
    fun signalBars(entries: List<InfoEntry>): List<InfoEntry> {
        val wanted = listOf("RSRP", "RSRQ", "SINR", "RSSI", "RSCP", "RxLev", "Ec/Io")
        val out = ArrayList<InfoEntry>()
        for (k in wanted) {
            val hit = findBar(entries, k) ?: findAny(entries, k)
            if (hit != null && hit.numValue != null) out.add(hit)
        }
        return out
    }

    /** 模组温度（℃） */
    fun temperature(entries: List<InfoEntry>): String? {
        val e = findAny(entries, "temperature") ?: return null
        val v = e.value.trim()
        if (v.isEmpty() || v.equals("unknown", true)) return null
        return if (v.toDoubleOrNull() != null) "$v ℃" else v
    }

    // ------------------------------------------------------------------
    // AT+QENG="servingcell" 实时信号解析
    // ------------------------------------------------------------------

    private val NUM_TOKEN = Regex("-?\\d+(\\.\\d+)?")

    /**
     * 解析 AT+QENG="servingcell" 响应，返回 RSRP/RSRQ/SINR/RSSI 值（dB 系）。
     *
     * 支持的行格式（Quectel 兼容固件）：
     *  - LTE：     +QENG: "servingcell","NOCONN","LTE","FDD",460,01,E256701,301,1300,3,20,20,20,-85,-10,-73,12,17
     *              尾部数字 = rsrp, rsrq, rssi, sinr, srxlev
     *  - NR5G-SA： +QENG: "servingcell","NOCONN","NR5G-SA","TDD",460,01,,,-,N/A,301,350,20,-85,-10,10
     *              尾部数字 = rsrp, rsrq, sinr
     *  - NR5G-NSA：同 NR5G-SA（另附 LTE 行，NSA 行优先）
     *  - WCDMA：   尾部 5 个数字 = rssi, ecio, rscp, pathloss（取 rssi）
     *  - GSM：     rssi = 最后一个数字
     */
    fun parseQeng(response: String): Map<String, Double> {
        val out = LinkedHashMap<String, Double>()
        val lines = response.split("\n").map { it.replace("\r", "").trim() }
        var sawServingcell = false
        for (line in lines) {
            if (!line.contains("\"servingcell\"", true) && !line.contains("servingcell", true)) continue
            if (!line.contains("QENG", true)) continue
            sawServingcell = true
            val tokens = NUM_TOKEN.findAll(line).map { it.value.toDouble() }.toList()
            if (tokens.isEmpty()) continue
            val mode = when {
                line.contains("NR5G-SA", true) -> "NR5G-SA"
                line.contains("NR5G-NSA", true) -> "NR5G-NSA"
                line.contains("\"LTE\"", true) || line.contains(",LTE,", true) -> "LTE"
                line.contains("WCDMA", true) -> "WCDMA"
                line.contains("\"GSM\"", true) || line.contains(",GSM,", true) -> "GSM"
                else -> ""
            }
            fun put(k: String, v: Double) { if (!out.containsKey(k)) out[k] = v }
            when (mode) {
                "NR5G-SA", "NR5G-NSA" -> {
                    // 尾部 3 个数字 = rsrp / rsrq / sinr
                    if (tokens.size >= 3) {
                        val t = tokens.takeLast(3)
                        put("RSRP", t[0]); put("RSRQ", t[1]); put("SINR", t[2])
                    }
                }
                "LTE" -> {
                    // 尾部 5 个数字 = rsrp / rsrq / rssi / sinr / srxlev
                    if (tokens.size >= 5) {
                        val t = tokens.takeLast(5)
                        put("RSRP", t[0]); put("RSRQ", t[1]); put("RSSI", t[2]); put("SINR", t[3])
                    } else if (tokens.size >= 4) {
                        val t = tokens.takeLast(4)
                        put("RSRP", t[0]); put("RSRQ", t[1]); put("RSSI", t[2]); put("SINR", t[3])
                    }
                }
                "WCDMA" -> {
                    // 尾部 4 个数字 = rssi / ecio / rscp / pathloss
                    if (tokens.size >= 4) put("RSSI", tokens[tokens.size - 4])
                }
                "GSM" -> {
                    put("RSSI", tokens.last())
                }
                else -> {
                    // 无模式标记的 servingcell 行：尽量按 LTE/NR 尾部猜
                    if (tokens.size >= 5) {
                        val t = tokens.takeLast(5)
                        put("RSRP", t[0]); put("RSRQ", t[1]); put("RSSI", t[2]); put("SINR", t[3])
                    }
                }
            }
        }
        return if (sawServingcell || out.isNotEmpty()) out else emptyMap()
    }

    /** 解析 AT+CSQ 响应，返回 RSSI（dBm，-113 + 2*n） */
    fun parseCsq(response: String): Map<String, Double> {
        val m = Regex("\\+CSQ:\\s*(\\d+)\\s*,\\s*(\\d+)").find(response) ?: return emptyMap()
        val rssi = m.groupValues[1].toInt()
        if (rssi in 0..31) {
            return mapOf("RSSI" to (-113.0 + 2 * rssi))
        }
        return emptyMap()
    }
}
