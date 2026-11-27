#pragma once
// 数据模型与解析:移植自安卓 App 的 InfoAnalyzer / BandMap
#include <Arduino.h>
#include <vector>
#include <initializer_list>

// 一条 modem_info 信息条目(qmodem 返回 key/value 结构)
struct InfoEntry {
  String key, value, unit, type;
};

// AT 直查得到的实时信号(绕过 qmodem 后端 10 秒缓存)
struct LiveSignal {
  bool ok = false;
  bool hasRsrp = false, hasRsrq = false, hasSinr = false, hasRssi = false;
  float rsrp = 0, rsrq = 0, sinr = 0, rssi = 0;
};

enum SigQuality { SIG_UNKNOWN = 0, SIG_EXCELLENT, SIG_GOOD, SIG_FAIR, SIG_POOR };

class Analyzer {
public:
  // 按候选 key 依次查找(忽略大小写),返回 value;找不到返回空串
  static String findAny(const std::vector<InfoEntry>& es, std::initializer_list<const char*> keys);
  static String networkMode(const std::vector<InfoEntry>& es);  // "5G SA"/"5G NSA"/"LTE"/"WCDMA"/"GSM"/""
  static String operatorName(const std::vector<InfoEntry>& es); // ISP/PLMN → 运营商中文
  static String bandBadge(const std::vector<InfoEntry>& es);    // "B3 · 1864.8 MHz"
  static SigQuality quality(const String& key, float v);
  static uint16_t qualityColor(SigQuality q);

  // AT+QENG="servingcell" 解析 → 实时信号;ratName 返回制式("5G SA"/"LTE"…)
  static bool parseQeng(const String& resp, LiveSignal& out, String& ratName);
  // AT+CSQ 解析 → 仅 RSSI
  static bool parseCsq(const String& resp, LiveSignal& out);

  // 频点号 → 频段信息(供 bandBadge 及单元测试)
  static bool fromChannel(int n, const String& rat, String& ratOut, int& bandNo, float& freqMhz, String& common);
  static String fmtFreq(float mhz);
};
