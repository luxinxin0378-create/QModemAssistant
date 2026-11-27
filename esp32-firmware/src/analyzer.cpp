// 解析实现:移植自安卓 App InfoAnalyzer.kt / BandMap.kt
#include "analyzer.h"
#include <math.h>

// ------------------------------------------------------------------
// 通用小工具
// ------------------------------------------------------------------

static String upperOf(const String& s) {
  String r = s; r.toUpperCase(); return r;
}

// ------------------------------------------------------------------
// 3GPP 频点 → 频段映射(与 App BandMap.kt 同源)
// ------------------------------------------------------------------

struct LteBand { int no; float lo, hi; int offs; const char* common; };
static const LteBand LTE_BANDS[] = {
  {1, 2110.0f, 2170.0f, 0, "2100 MHz"},
  {2, 1930.0f, 1990.0f, 600, "1900 MHz"},
  {3, 1805.0f, 1885.0f, 1200, "1800 MHz"},
  {4, 2110.0f, 2155.0f, 1950, "2100 MHz AWS"},
  {5, 869.0f, 894.0f, 2400, "850 MHz"},
  {6, 875.0f, 885.0f, 2650, "850 MHz"},
  {7, 2620.0f, 2690.0f, 2750, "2600 MHz"},
  {8, 925.0f, 960.0f, 3450, "900 MHz"},
  {9, 1844.9f, 1879.9f, 3800, "1800 MHz"},
  {10, 2110.0f, 2170.0f, 4150, "2100 MHz"},
  {11, 1475.9f, 1495.9f, 4750, "1500 MHz"},
  {12, 729.0f, 746.0f, 5010, "700 MHz"},
  {13, 746.0f, 756.0f, 5180, "700 MHz"},
  {14, 758.0f, 768.0f, 5280, "700 MHz"},
  {17, 734.0f, 746.0f, 5730, "700 MHz"},
  {18, 815.0f, 865.0f, 5850, "850 MHz"},
  {19, 830.0f, 875.0f, 6000, "850 MHz"},
  {20, 791.0f, 821.0f, 6150, "800 MHz"},
  {21, 1495.9f, 1510.9f, 6450, "1500 MHz"},
  {22, 3510.0f, 3590.0f, 6600, "3500 MHz"},
  {23, 2000.0f, 2020.0f, 7400, "2000 MHz"},
  {24, 1626.5f, 1660.5f, 7550, "1600 MHz"},
  {25, 1930.0f, 1990.0f, 8040, "1900 MHz"},
  {26, 859.0f, 894.0f, 8690, "850 MHz"},
  {27, 852.0f, 869.0f, 9040, "850 MHz"},
  {28, 758.0f, 803.0f, 9210, "700 MHz"},
  {29, 717.0f, 728.0f, 9660, "700 MHz"},
  {30, 2305.0f, 2315.0f, 9770, "2300 MHz"},
  {31, 452.5f, 457.5f, 9870, "450 MHz"},
  {32, 1900.0f, 1920.0f, 9920, "1900 MHz"},
  {33, 1900.0f, 1920.0f, 36000, "1900 MHz TDD"},
  {34, 2010.0f, 2025.0f, 36200, "2100 MHz TDD"},
  {35, 1850.0f, 1910.0f, 36350, "1900 MHz TDD"},
  {36, 1930.0f, 1990.0f, 36950, "1900 MHz TDD"},
  {37, 1910.0f, 1930.0f, 37550, "1900 MHz TDD"},
  {38, 2570.0f, 2620.0f, 37750, "2600 MHz TDD"},
  {39, 1880.0f, 1920.0f, 38250, "1900 MHz TDD"},
  {40, 2300.0f, 2400.0f, 38650, "2300 MHz TDD"},
  {41, 2496.0f, 2690.0f, 39650, "2500 MHz TDD"},
  {42, 3400.0f, 3600.0f, 41590, "3500 MHz TDD"},
  {43, 3600.0f, 3800.0f, 43590, "3600 MHz TDD"},
  {44, 703.0f, 803.0f, 45590, "700 MHz TDD"},
  {45, 1447.0f, 1467.0f, 46790, "1450 MHz TDD"},
  {46, 5150.0f, 5925.0f, 46990, "5200 MHz TDD"},
  {47, 5855.0f, 5925.0f, 54540, "5900 MHz TDD"},
  {48, 3550.0f, 3700.0f, 54680, "3600 MHz TDD"},
  {49, 1430.0f, 1510.0f, 55680, "1500 MHz TDD"},
  {50, 1430.0f, 1517.0f, 56480, "1500 MHz TDD"},
  {51, 1427.0f, 1432.0f, 57280, "1430 MHz TDD"},
  {52, 3300.0f, 3400.0f, 57330, "3300 MHz TDD"},
  {53, 2483.5f, 2495.0f, 58330, "2490 MHz TDD"},
  {65, 2110.0f, 2200.0f, 65536, "2100 MHz"},
  {66, 2110.0f, 2180.0f, 66436, "2100 MHz AWS"},
  {67, 738.0f, 758.0f, 67336, "700 MHz"},
  {68, 753.0f, 783.0f, 67436, "750 MHz"},
  {69, 2575.0f, 2635.0f, 67536, "2600 MHz"},
  {70, 1995.0f, 2010.0f, 67636, "2000 MHz"},
  {71, 617.0f, 653.0f, 68586, "600 MHz"},
  {72, 451.0f, 456.0f, 68936, "450 MHz"},
  {73, 456.0f, 461.0f, 69036, "450 MHz"},
  {74, 1427.0f, 1462.0f, 69136, "1450 MHz"}
};

struct NrBand { float lo, hi; int no; const char* common; };
static const NrBand NR_BANDS[] = {
  {2110.0f, 2170.0f, 1, "2100 MHz"},
  {1930.0f, 1990.0f, 2, "1900 MHz"},
  {1805.0f, 1885.0f, 3, "1800 MHz"},
  {869.0f, 894.0f, 5, "850 MHz"},
  {2620.0f, 2690.0f, 7, "2600 MHz"},
  {925.0f, 960.0f, 8, "900 MHz"},
  {729.0f, 746.0f, 12, "700 MHz"},
  {758.0f, 768.0f, 14, "700 MHz"},
  {815.0f, 865.0f, 18, "850 MHz"},
  {791.0f, 821.0f, 20, "800 MHz"},
  {1626.5f, 1660.5f, 24, "1600 MHz"},
  {1930.0f, 1990.0f, 25, "1900 MHz"},
  {859.0f, 894.0f, 26, "850 MHz"},
  {758.0f, 803.0f, 28, "700 MHz"},
  {717.0f, 728.0f, 29, "700 MHz"},
  {2305.0f, 2315.0f, 30, "2300 MHz"},
  {2010.0f, 2025.0f, 34, "2100 MHz TDD"},
  {2570.0f, 2620.0f, 38, "2600 MHz TDD"},
  {1880.0f, 1920.0f, 39, "1900 MHz TDD"},
  {2300.0f, 2400.0f, 40, "2300 MHz TDD"},
  {1517.0f, 1593.0f, 50, "1500 MHz TDD"},
  {1427.0f, 1432.0f, 51, "1430 MHz TDD"},
  {2483.5f, 2495.0f, 53, "2490 MHz TDD"},
  {2110.0f, 2200.0f, 65, "2100 MHz"},
  {1995.0f, 2010.0f, 70, "2000 MHz"},
  {617.0f, 653.0f, 71, "600 MHz"},
  {1427.0f, 1462.0f, 74, "1450 MHz"},
  {3300.0f, 3800.0f, 78, "3500 MHz TDD"},
  {3300.0f, 4200.0f, 77, "3500 MHz TDD"},
  {4400.0f, 5000.0f, 79, "4900 MHz TDD"},
  {2496.0f, 2690.0f, 41, "2600 MHz TDD"},
  {2496.0f, 2690.0f, 90, "2600 MHz TDD"},
  {1475.0f, 1517.0f, 75, "1500 MHz"},
  {26500.0f, 29500.0f, 257, "28 GHz"},
  {24250.0f, 27500.0f, 258, "26 GHz"},
  {39500.0f, 43500.0f, 260, "40 GHz"},
  {27500.0f, 28350.0f, 261, "28 GHz"}
};

struct WBand { int nLo, nHi; float off; int no; const char* common; };
static const WBand WCDMA_BANDS[] = {
  {10562, 10838, 0.0f, 1, "2100 MHz"},
  {9662, 9938, 0.0f, 2, "1900 MHz"},
  {1162, 1625, 1560.0f, 3, "1800 MHz"},
  {1537, 1738, 1805.0f, 4, "2100 MHz AWS"},
  {4357, 4458, 0.0f, 5, "850 MHz"},
  {2237, 2563, 2175.0f, 7, "2600 MHz"},
  {2937, 3088, 340.0f, 8, "900 MHz"},
  {937, 988, 1664.9f, 9, "1800 MHz"},
  {712, 788, 733.3f, 11, "1500 MHz"},
  {712, 737, 685.7f, 19, "850 MHz"},
  {10087, 10125, 0.0f, 34, "2100 MHz TDD"}
};

static float nrFreqMhz(int n) {
  if (n < 600000) return n * 0.005f;
  if (n < 2016667) return 3000.0f + (n - 600000) * 0.015f;
  return 24250.0f + (n - 2016667) * 0.06f;
}

String Analyzer::fmtFreq(float mhz) {
  char b[24];
  if (mhz >= 3000.0f) snprintf(b, sizeof(b), "%.2f GHz", mhz / 1000.0f);
  else snprintf(b, sizeof(b), "%.1f MHz", (double)mhz);
  return String(b);
}

bool Analyzer::fromChannel(int n, const String& ratIn, String& ratOut, int& bandNo, float& freqMhz, String& common) {
  String rat = upperOf(ratIn);
  if (rat == "NR") {
    float f = nrFreqMhz(n);
    const NrBand* hit = nullptr; float width = 1e12f;
    for (auto& b : NR_BANDS) {
      if (f >= b.lo && f <= b.hi && (b.hi - b.lo) < width) { hit = &b; width = b.hi - b.lo; }
    }
    ratOut = "NR";
    if (hit) { bandNo = hit->no; common = hit->common; } else bandNo = 0;
    freqMhz = f;
    return true;
  }
  if (rat == "WCDMA") {
    for (auto& b : WCDMA_BANDS) {
      if (n >= b.nLo && n <= b.nHi) {
        ratOut = "WCDMA"; bandNo = b.no; freqMhz = 0.2f * n + b.off; common = b.common;
        return true;
      }
    }
    return false;
  }
  if (rat == "GSM") {
    float f; int no; const char* c;
    if (n >= 0 && n <= 124)          { f = 935.0f + 0.2f * n; no = 8; c = "900 MHz"; }
    else if (n >= 955 && n <= 1023)  { f = 925.0f + 0.2f * (n - 955); no = 8; c = "900 MHz"; }
    else if (n >= 128 && n <= 251)   { f = 869.0f + 0.2f * (n - 128); no = 5; c = "850 MHz"; }
    else if (n >= 512 && n <= 885)   { f = 1805.0f + 0.1f * (n - 512); no = 3; c = "1800 MHz"; }
    else return false;
    ratOut = "GSM"; bandNo = no; freqMhz = f; common = c;
    return true;
  }
  // LTE:超出范围的大频点自动尝试 NR(与 App 行为一致)
  for (auto& b : LTE_BANDS) {
    int span = (int)((b.hi - b.lo) * 10.0f);
    if (n >= b.offs && n <= b.offs + span) {
      ratOut = "LTE"; bandNo = b.no;
      freqMhz = b.lo + 0.1f * (n - b.offs);
      common = b.common;
      return true;
    }
  }
  if (n > 500000) return fromChannel(n, "NR", ratOut, bandNo, freqMhz, common);
  return false;
}

// ------------------------------------------------------------------
// PLMN → 运营商(精简表:中国四大运营商为主)
// ------------------------------------------------------------------

struct Plmn { const char* code; const char* name; };
static const Plmn PLMN_ZH[] = {
  {"46000", "中国移动"}, {"46002", "中国移动"}, {"46004", "中国移动"},
  {"46007", "中国移动"}, {"46008", "中国移动"}, {"46020", "中国铁通"},
  {"46001", "中国联通"}, {"46006", "中国联通"}, {"46009", "中国联通"},
  {"46003", "中国电信"}, {"46005", "中国电信"}, {"46011", "中国电信"},
  {"46015", "中国电信"}, {"46016", "中国广电"}, {"46017", "中国广电"},
  {"45400", "中国香港CSL"}, {"45501", "中国澳门CTM"}
};

static String plmnLookup(const String& v) {
  for (auto& p : PLMN_ZH) if (v == p.code) return String(p.name);
  return "";
}

static bool allDigits(const String& s, int minLen, int maxLen) {
  if (s.length() < (size_t)minLen || s.length() > (size_t)maxLen) return false;
  for (unsigned i = 0; i < s.length(); i++) if (!isDigit(s[i])) return false;
  return true;
}

static String plmnName(const String& raw) {
  String v = raw; v.trim();
  if (!allDigits(v, 4, 6)) return "";
  String hit = plmnLookup(v);
  if (hit.length()) return hit;
  if (v.length() >= 5) {
    hit = plmnLookup(v.substring(0, 5)); if (hit.length()) return hit;
    hit = plmnLookup(v.substring(0, 4) + "0"); if (hit.length()) return hit;
  }
  if (v.length() == 4) {
    hit = plmnLookup(v + "0"); if (hit.length()) return hit;
  }
  return "";
}

// ------------------------------------------------------------------
// 字段提取(与 App InfoAnalyzer 同逻辑)
// ------------------------------------------------------------------

String Analyzer::findAny(const std::vector<InfoEntry>& es, std::initializer_list<const char*> keys) {
  for (auto k : keys) {
    for (auto& e : es) {
      if (e.key.equalsIgnoreCase(k)) return e.value;
    }
  }
  return "";
}

String Analyzer::networkMode(const std::vector<InfoEntry>& es) {
  auto has = [&](const char* k) {
    for (auto& e : es) if (e.key.equalsIgnoreCase(k) || e.value.equalsIgnoreCase(k)) return true;
    return false;
  };
  if (has("NR5G-SA")) return "5G SA";
  if (has("NR5G-NSA")) return "5G NSA";
  if (has("LTE")) return "LTE";
  if (has("WCDMA")) return "WCDMA";
  if (has("GSM")) return "GSM";
  String v = findAny(es, {"Network Type", "network_mode", "Network Mode"});
  if (v.length()) {
    String up = upperOf(v);
    if (up.indexOf("SA") >= 0 && up.indexOf("5G") >= 0) return "5G SA";
    if (up.indexOf("NSA") >= 0) return "5G NSA";
    if (up.indexOf("NR") >= 0 || up.indexOf("5G") >= 0) return "5G";
    if (up.indexOf("LTE") >= 0 || up.indexOf("4G") >= 0) return "LTE";
    if (up.indexOf("WCDMA") >= 0 || up.indexOf("3G") >= 0) return "WCDMA";
    if (up.indexOf("GSM") >= 0 || up.indexOf("2G") >= 0) return "GSM";
    return v;
  }
  return "";
}

String Analyzer::operatorName(const std::vector<InfoEntry>& es) {
  String isp = findAny(es, {"ISP"});
  if (isp.length()) {
    String p = plmnName(isp);
    return p.length() ? p : isp;
  }
  String mcc = findAny(es, {"MCC"}); mcc.trim();
  String mnc = findAny(es, {"MNC"}); mnc.trim();
  if (!mcc.length() || !mnc.length() || mcc == "0" || mnc == "0") return "";
  String mncPad = mnc;
  if (mncPad.length() < 2) mncPad = "0" + mncPad;
  String hit = plmnLookup(mcc + mnc);
  if (hit.length()) return hit;
  hit = plmnLookup(mcc + mncPad);
  if (hit.length()) return hit;
  return mcc + "-" + mnc;
}

// Band 原文解析("3"/"B3"/"LTE B3"/"n78")→ (制式, 频段号)
static bool parseBandRaw(const String& raw, String& rat, int& no) {
  int d = -1;
  for (unsigned i = 0; i < raw.length(); i++) {
    if (isDigit(raw[i])) { d = i; break; }
  }
  if (d < 0) return false;
  no = raw.substring(d).toInt();
  String r = raw; r.trim();
  if (r[0] == 'n' || r[0] == 'N') rat = "NR";
  else if (upperOf(r).indexOf("NR") >= 0) rat = "NR";
  else if (upperOf(r).indexOf("LTE") >= 0) rat = "LTE";
  else rat = "LTE";
  return true;
}

String Analyzer::bandBadge(const std::vector<InfoEntry>& es) {
  String rawBand = findAny(es, {"Band"});
  rawBand.trim();
  if (rawBand.equalsIgnoreCase("unknown") || rawBand.equalsIgnoreCase("n/a")) rawBand = "";

  String mode = networkMode(es);
  String rat;
  if (mode.startsWith("5G")) rat = "NR";
  else if (mode == "WCDMA") rat = "WCDMA";
  else if (mode == "GSM") rat = "GSM";
  else rat = "LTE";

  String chStr = findAny(es, {"NR-ARFCN", "nRarfcn", "NRARFCN", "EARFCN", "UARFCN", "ARFCN"});
  chStr.trim();
  bool hasCh = allDigits(chStr, 1, 7);
  int ch = hasCh ? chStr.toInt() : 0;

  int bandNo = 0;
  float freq = 0; bool hasFreq = false;
  String common = "";
  if (hasCh) {
    String rOut; float f; String c;
    if (fromChannel(ch, rat, rOut, bandNo, f, c)) {
      rat = rOut; freq = f; common = c; hasFreq = true;
    }
  }
  if (rawBand.length()) {
    int n2 = 0; String r2;
    if (parseBandRaw(rawBand, r2, n2)) {
      rat = r2;
      if (bandNo == 0) bandNo = n2;
    }
  }

  String label;
  if (bandNo > 0) label = (rat == "NR" ? "n" : "B") + String(bandNo);
  else if (rawBand.length()) label = rawBand;
  else return "";

  String freqTxt = hasFreq ? fmtFreq(freq) : common;
  if (freqTxt.length()) label += " · " + freqTxt;
  return label;
}

// ------------------------------------------------------------------
// 信号评级(与 App 同阈值)
// ------------------------------------------------------------------

SigQuality Analyzer::quality(const String& key, float v) {
  String k = upperOf(key);
  if (k == "RSRP") return v >= -80 ? SIG_EXCELLENT : v >= -90 ? SIG_GOOD : v >= -100 ? SIG_FAIR : SIG_POOR;
  if (k == "RSRQ") return v >= -10 ? SIG_EXCELLENT : v >= -15 ? SIG_GOOD : v >= -20 ? SIG_FAIR : SIG_POOR;
  if (k == "SINR") return v >= 20 ? SIG_EXCELLENT : v >= 13 ? SIG_GOOD : v >= 0 ? SIG_FAIR : SIG_POOR;
  if (k == "RSSI") return v >= -70 ? SIG_EXCELLENT : v >= -85 ? SIG_GOOD : v >= -100 ? SIG_FAIR : SIG_POOR;
  if (k == "RSCP") return v >= -70 ? SIG_EXCELLENT : v >= -85 ? SIG_GOOD : v >= -100 ? SIG_FAIR : SIG_POOR;
  if (k == "RXLEV") return v >= -70 ? SIG_EXCELLENT : v >= -85 ? SIG_GOOD : v >= -95 ? SIG_FAIR : SIG_POOR;
  return SIG_UNKNOWN;
}

uint16_t Analyzer::qualityColor(SigQuality q) {
  switch (q) {
    case SIG_EXCELLENT: return 0x07E0; // 绿
    case SIG_GOOD:      return 0xAFE0; // 黄绿
    case SIG_FAIR:      return 0xFD20; // 橙
    case SIG_POOR:      return 0xF800; // 红
    default:            return 0x630C; // 灰
  }
}

// ------------------------------------------------------------------
// AT+QENG="servingcell" 解析(与 App InfoAnalyzer.parseQeng 同逻辑)
// ------------------------------------------------------------------

bool Analyzer::parseQeng(const String& resp, LiveSignal& out, String& ratName) {
  bool seen = false;
  int pos = 0;
  String upperAll = upperOf(resp);
  while (true) {
    int nl = resp.indexOf('\n', pos);
    String line = (nl < 0) ? resp.substring(pos) : resp.substring(pos, nl);
    pos = (nl < 0) ? (int)resp.length() : nl + 1;
    line.replace('\r', ' ');
    line.trim();

    String up = upperOf(line);
    if (up.indexOf("SERVINGCELL") < 0 || up.indexOf("QENG") < 0) {
      if (nl < 0) break;
      continue;
    }
    seen = true;

    // 提取所有数字 token(与 Kotlin Regex 一致)
    std::vector<float> nums;
    const char* p = line.c_str();
    for (int i = 0; p[i];) {
      if (p[i] == '-' || (p[i] >= '0' && p[i] <= '9')) {
        char* end = nullptr;
        double v = strtod(p + i, &end);
        if (end > p + i) { nums.push_back((float)v); i += (int)(end - (p + i)); continue; }
      }
      i++;
    }

    String mode;
    if (up.indexOf("NR5G-SA") >= 0) mode = "NR5G-SA";
    else if (up.indexOf("NR5G-NSA") >= 0) mode = "NR5G-NSA";
    else if (up.indexOf("\"LTE\"") >= 0 || up.indexOf(",LTE,") >= 0) mode = "LTE";
    else if (up.indexOf("WCDMA") >= 0) mode = "WCDMA";
    else if (up.indexOf("\"GSM\"") >= 0 || up.indexOf(",GSM,") >= 0) mode = "GSM";

    auto putRsrp = [&](float v) { if (!out.hasRsrp) { out.hasRsrp = true; out.rsrp = v; } };
    auto putRsrq = [&](float v) { if (!out.hasRsrq) { out.hasRsrq = true; out.rsrq = v; } };
    auto putSinr = [&](float v) { if (!out.hasSinr) { out.hasSinr = true; out.sinr = v; } };
    auto putRssi = [&](float v) { if (!out.hasRssi) { out.hasRssi = true; out.rssi = v; } };

    size_t n = nums.size();
    if (mode == "NR5G-SA" || mode == "NR5G-NSA") {
      ratName = mode == "NR5G-SA" ? "5G SA" : "5G NSA";
      if (n >= 3) { putRsrp(nums[n - 3]); putRsrq(nums[n - 2]); putSinr(nums[n - 1]); }
    } else if (mode == "LTE") {
      ratName = "LTE";
      if (n >= 5) { putRsrp(nums[n - 5]); putRsrq(nums[n - 4]); putRssi(nums[n - 3]); putSinr(nums[n - 2]); }
      else if (n >= 4) { putRsrp(nums[n - 4]); putRsrq(nums[n - 3]); putRssi(nums[n - 2]); putSinr(nums[n - 1]); }
    } else if (mode == "WCDMA") {
      ratName = "WCDMA";
      if (n >= 4) putRssi(nums[n - 4]);
    } else if (mode == "GSM") {
      ratName = "GSM";
      if (n >= 1) putRssi(nums[n - 1]);
    } else {
      if (n >= 5) { putRsrp(nums[n - 5]); putRsrq(nums[n - 4]); putRssi(nums[n - 3]); putSinr(nums[n - 2]); }
    }

    if (nl < 0) break;
  }
  return seen;
}

bool Analyzer::parseCsq(const String& resp, LiveSignal& out) {
  int i = resp.indexOf("+CSQ:");
  if (i < 0) return false;
  int n = -1, m = -1;
  if (sscanf(resp.c_str() + i, "+CSQ:%d,%d", &n, &m) != 2) {
    if (sscanf(resp.c_str() + i, "+CSQ: %d , %d", &n, &m) != 2) return false;
  }
  if (n < 0 || n > 31) return false;
  out.hasRssi = true;
  out.rssi = -113.0f + 2.0f * n;
  return true;
}
