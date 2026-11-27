package com.qmodem.app.net

/**
 * 频点号 → 频段/下行频率 映射（依据 3GPP TS 36.101 / TS 38.104 / TS 25.101）。
 *
 * 把 EARFCN 1798 这类原始编号翻译成「LTE B3 · 1864.8 MHz（1800 MHz）」，
 * 让用户能直接看出是哪个运营商的哪个频段。
 */
object BandMap {

    /** 一次频点解析的结果 */
    data class BandInfo(
        val rat: String,          // LTE / NR / WCDMA / GSM
        val bandNo: Int?,         // 频段号
        val freqMhz: Double?,     // 下行频率
        val common: String?,      // 常用称呼，如 "1800 MHz"
        val rawBand: String? = null // 路由器直接上报的 Band 原文
    ) {
        /** 徽标文本，如 "B3 · 1864.8 MHz" / "n78 · 3.55 GHz" */
        fun badge(): String? {
            val label = when {
                bandNo != null -> if (rat == "NR") "n$bandNo" else "B$bandNo"
                !rawBand.isNullOrBlank() -> rawBand
                else -> null
            } ?: return null
            val freqTxt = freqMhz?.let { fmtFreq(it) } ?: common
            return listOfNotNull(label, freqTxt).joinToString(" · ")
        }
    }

    fun fmtFreq(mhz: Double): String = if (mhz >= 3000) {
        String.format("%.2f GHz", mhz / 1000)
    } else {
        String.format("%.1f MHz", mhz)
    }

    // ------------------------------------------------------------------
    // LTE：EARFCN → 频段（dlLow, dlHigh, N_offs, 常用名）
    // ------------------------------------------------------------------

    private data class LteBand(val no: Int, val lo: Double, val hi: Double, val offs: Int, val common: String)

    private val LTE_BANDS = listOf(
        LteBand(1, 2110.0, 2170.0, 0, "2100 MHz"),          // 联通/电信
        LteBand(2, 1930.0, 1990.0, 600, "1900 MHz"),
        LteBand(3, 1805.0, 1885.0, 1200, "1800 MHz"),       // 联通主频段
        LteBand(4, 2110.0, 2155.0, 1950, "2100 MHz AWS"),
        LteBand(5, 869.0, 894.0, 2400, "850 MHz"),          // 电信
        LteBand(6, 875.0, 885.0, 2650, "850 MHz"),
        LteBand(7, 2620.0, 2690.0, 2750, "2600 MHz"),
        LteBand(8, 925.0, 960.0, 3450, "900 MHz"),          // 移动/联通
        LteBand(9, 1844.9, 1879.9, 3800, "1800 MHz"),
        LteBand(10, 2110.0, 2170.0, 4150, "2100 MHz"),
        LteBand(11, 1475.9, 1495.9, 4750, "1500 MHz"),
        LteBand(12, 729.0, 746.0, 5010, "700 MHz"),
        LteBand(13, 746.0, 756.0, 5180, "700 MHz"),
        LteBand(14, 758.0, 768.0, 5280, "700 MHz"),
        LteBand(17, 734.0, 746.0, 5730, "700 MHz"),
        LteBand(18, 815.0, 865.0, 5850, "850 MHz"),
        LteBand(19, 830.0, 875.0, 6000, "850 MHz"),
        LteBand(20, 791.0, 821.0, 6150, "800 MHz"),
        LteBand(21, 1495.9, 1510.9, 6450, "1500 MHz"),
        LteBand(22, 3510.0, 3590.0, 6600, "3500 MHz"),
        LteBand(23, 2000.0, 2020.0, 7400, "2000 MHz"),
        LteBand(24, 1626.5, 1660.5, 7550, "1600 MHz"),
        LteBand(25, 1930.0, 1990.0, 8040, "1900 MHz"),
        LteBand(26, 859.0, 894.0, 8690, "850 MHz"),
        LteBand(27, 852.0, 869.0, 9040, "850 MHz"),
        LteBand(28, 758.0, 803.0, 9210, "700 MHz"),         // 广电 700M
        LteBand(29, 717.0, 728.0, 9660, "700 MHz"),
        LteBand(30, 2305.0, 2315.0, 9770, "2300 MHz"),
        LteBand(31, 452.5, 457.5, 9870, "450 MHz"),
        LteBand(32, 1900.0, 1920.0, 9920, "1900 MHz"),
        LteBand(33, 1900.0, 1920.0, 36000, "1900 MHz TDD"),
        LteBand(34, 2010.0, 2025.0, 36200, "2100 MHz TDD"), // 移动
        LteBand(35, 1850.0, 1910.0, 36350, "1900 MHz TDD"),
        LteBand(36, 1930.0, 1990.0, 36950, "1900 MHz TDD"),
        LteBand(37, 1910.0, 1930.0, 37550, "1900 MHz TDD"),
        LteBand(38, 2570.0, 2620.0, 37750, "2600 MHz TDD"),
        LteBand(39, 1880.0, 1920.0, 38250, "1900 MHz TDD"), // 移动
        LteBand(40, 2300.0, 2400.0, 38650, "2300 MHz TDD"),
        LteBand(41, 2496.0, 2690.0, 39650, "2500 MHz TDD"), // 移动/广电
        LteBand(42, 3400.0, 3600.0, 41590, "3500 MHz TDD"),
        LteBand(43, 3600.0, 3800.0, 43590, "3600 MHz TDD"),
        LteBand(44, 703.0, 803.0, 45590, "700 MHz TDD"),
        LteBand(45, 1447.0, 1467.0, 46790, "1450 MHz TDD"),
        LteBand(46, 5150.0, 5925.0, 46990, "5200 MHz TDD"),
        LteBand(47, 5855.0, 5925.0, 54540, "5900 MHz TDD"),
        LteBand(48, 3550.0, 3700.0, 54680, "3600 MHz TDD"),
        LteBand(49, 1430.0, 1510.0, 55680, "1500 MHz TDD"),
        LteBand(50, 1430.0, 1517.0, 56480, "1500 MHz TDD"),
        LteBand(51, 1427.0, 1432.0, 57280, "1430 MHz TDD"),
        LteBand(52, 3300.0, 3400.0, 57330, "3300 MHz TDD"),
        LteBand(53, 2483.5, 2495.0, 58330, "2490 MHz TDD"),
        LteBand(65, 2110.0, 2200.0, 65536, "2100 MHz"),
        LteBand(66, 2110.0, 2180.0, 66436, "2100 MHz AWS"),
        LteBand(67, 738.0, 758.0, 67336, "700 MHz"),
        LteBand(68, 753.0, 783.0, 67436, "750 MHz"),
        LteBand(69, 2575.0, 2635.0, 67536, "2600 MHz"),
        LteBand(70, 1995.0, 2010.0, 67636, "2000 MHz"),
        LteBand(71, 617.0, 653.0, 68586, "600 MHz"),
        LteBand(72, 451.0, 456.0, 68936, "450 MHz"),
        LteBand(73, 456.0, 461.0, 69036, "450 MHz"),
        LteBand(74, 1427.0, 1462.0, 69136, "1450 MHz")
    )

    fun fromLteEarfcn(n: Int): BandInfo? {
        val hit = LTE_BANDS.firstOrNull { b ->
            n >= b.offs && n <= b.offs + ((b.hi - b.lo) * 10).toInt()
        } ?: return null
        val freq = hit.lo + 0.1 * (n - hit.offs)
        return BandInfo("LTE", hit.no, freq, hit.common)
    }

    // ------------------------------------------------------------------
    // NR：ARFCN → 频段（F_low, F_high, 常用名），窄区间优先匹配
    // ------------------------------------------------------------------

    private data class NrBand(val lo: Double, val hi: Double, val no: Int, val common: String)

    private val NR_BANDS = listOf(
        NrBand(2110.0, 2170.0, 1, "2100 MHz"),
        NrBand(1930.0, 1990.0, 2, "1900 MHz"),
        NrBand(1805.0, 1885.0, 3, "1800 MHz"),
        NrBand(869.0, 894.0, 5, "850 MHz"),
        NrBand(2620.0, 2690.0, 7, "2600 MHz"),
        NrBand(925.0, 960.0, 8, "900 MHz"),
        NrBand(729.0, 746.0, 12, "700 MHz"),
        NrBand(758.0, 768.0, 14, "700 MHz"),
        NrBand(815.0, 865.0, 18, "850 MHz"),
        NrBand(791.0, 821.0, 20, "800 MHz"),
        NrBand(1626.5, 1660.5, 24, "1600 MHz"),
        NrBand(1930.0, 1990.0, 25, "1900 MHz"),
        NrBand(859.0, 894.0, 26, "850 MHz"),
        NrBand(758.0, 803.0, 28, "700 MHz"),
        NrBand(717.0, 728.0, 29, "700 MHz"),
        NrBand(2305.0, 2315.0, 30, "2300 MHz"),
        NrBand(2010.0, 2025.0, 34, "2100 MHz TDD"),
        NrBand(2570.0, 2620.0, 38, "2600 MHz TDD"),
        NrBand(1880.0, 1920.0, 39, "1900 MHz TDD"),
        NrBand(2300.0, 2400.0, 40, "2300 MHz TDD"),
        NrBand(1517.0, 1593.0, 50, "1500 MHz TDD"),
        NrBand(1427.0, 1432.0, 51, "1430 MHz TDD"),
        NrBand(2483.5, 2495.0, 53, "2490 MHz TDD"),
        NrBand(2110.0, 2200.0, 65, "2100 MHz"),
        NrBand(1995.0, 2010.0, 70, "2000 MHz"),
        NrBand(617.0, 653.0, 71, "600 MHz"),
        NrBand(1427.0, 1462.0, 74, "1450 MHz"),
        NrBand(3300.0, 3800.0, 78, "3500 MHz TDD"),   // n78 放在 n77 前（窄者优先）
        NrBand(3300.0, 4200.0, 77, "3500 MHz TDD"),
        NrBand(4400.0, 5000.0, 79, "4900 MHz TDD"),
        NrBand(2496.0, 2690.0, 41, "2600 MHz TDD"),   // n41 优先于 n90
        NrBand(2496.0, 2690.0, 90, "2600 MHz TDD"),
        NrBand(1475.0, 1517.0, 75, "1500 MHz"),
        NrBand(26500.0, 29500.0, 257, "28 GHz"),
        NrBand(24250.0, 27500.0, 258, "26 GHz"),
        NrBand(39500.0, 43500.0, 260, "40 GHz"),
        NrBand(27500.0, 28350.0, 261, "28 GHz")
    )

    /** NR ARFCN → 频率（全局栅格公式） */
    fun nrFreqMhz(n: Int): Double = when {
        n < 600000 -> n * 0.005
        n < 2016667 -> 3000.0 + (n - 600000) * 0.015
        else -> 24250.0 + (n - 2016667) * 0.06
    }

    fun fromNrarfcn(n: Int): BandInfo? {
        val freq = nrFreqMhz(n)
        val hit = NR_BANDS.filter { freq >= it.lo && freq <= it.hi }
            .minByOrNull { it.hi - it.lo } ?: return null
        return BandInfo("NR", hit.no, freq, hit.common)
    }

    // ------------------------------------------------------------------
    // WCDMA：UARFCN → 频段（F = 0.2 * N + off）
    // ------------------------------------------------------------------

    private data class WBand(val nLo: Int, val nHi: Int, val off: Double, val no: Int, val common: String)

    private val WCDMA_BANDS = listOf(
        WBand(10562, 10838, 0.0, 1, "2100 MHz"),
        WBand(9662, 9938, 0.0, 2, "1900 MHz"),
        WBand(1162, 1625, 1560.0, 3, "1800 MHz"),
        WBand(1537, 1738, 1805.0, 4, "2100 MHz AWS"),
        WBand(4357, 4458, 0.0, 5, "850 MHz"),
        WBand(2237, 2563, 2175.0, 7, "2600 MHz"),
        WBand(2937, 3088, 340.0, 8, "900 MHz"),
        WBand(937, 988, 1664.9, 9, "1800 MHz"),
        WBand(712, 788, 733.3, 11, "1500 MHz"),
        WBand(712, 737, 685.7, 19, "850 MHz"),
        WBand(10087, 10125, 0.0, 34, "2100 MHz TDD")
    )

    fun fromWcdmaUarfcn(n: Int): BandInfo? {
        val hit = WCDMA_BANDS.firstOrNull { n >= it.nLo && n <= it.nHi } ?: return null
        val freq = 0.2 * n + hit.off
        return BandInfo("WCDMA", hit.no, freq, hit.common)
    }

    // ------------------------------------------------------------------
    // GSM：ARFCN → 频段
    // ------------------------------------------------------------------

    fun fromGsmArfcn(n: Int): BandInfo? {
        val (freq, no, common) = when (n) {
            in 0..124 -> Triple(935.0 + 0.2 * n, 8, "900 MHz")
            in 955..1023 -> Triple(925.0 + 0.2 * (n - 955), 8, "900 MHz")
            in 128..251 -> Triple(869.0 + 0.2 * (n - 128), 5, "850 MHz")
            in 512..885 -> Triple(1805.0 + 0.1 * (n - 512), 3, "1800 MHz")
            in 512..810 -> Triple(1930.0 + 0.2 * (n - 512), 2, "1900 MHz")
            else -> return null
        }
        return BandInfo("GSM", no, freq, common)
    }

    /**
     * 按制式解析频点号。
     * @param rat 期望制式；LTE 频点超出范围时自动尝试 NR（大频点）。
     */
    fun fromChannel(n: Int, rat: String): BandInfo? {
        return when (rat.uppercase()) {
            "NR" -> fromNrarfcn(n) ?: BandInfo("NR", null, nrFreqMhz(n), null)
            "WCDMA" -> fromWcdmaUarfcn(n)
            "GSM" -> fromGsmArfcn(n)
            else -> fromLteEarfcn(n) ?: fromNrarfcn(n)
        }
    }

    /** 解析 Band 原文（"3" / "B3" / "LTE B3" / "n78" / "BAND 1"）→ (制式, 频段号) */
    fun parseBandRaw(raw: String): Pair<String, Int>? {
        val m = Regex("(\\d+)").find(raw) ?: return null
        val no = m.groupValues[1].toIntOrNull() ?: return null
        val rat = when {
            raw.startsWith("n", ignoreCase = true) && raw.length <= 5 -> "NR"
            raw.contains("NR", true) -> "NR"
            raw.contains("LTE", true) -> "LTE"
            else -> "LTE"
        }
        return rat to no
    }
}
