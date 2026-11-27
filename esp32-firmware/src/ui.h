#pragma once
// TFT 屏幕渲染(ILI9341 横屏 320x240,中文用 U8g2 点阵字体)
#include <Arduino.h>
#include <vector>
#include "analyzer.h"

// 一个信号指标块
struct Metric {
  const char* label;   // ASCII 标签,如 "RSRP"
  bool has;
  float value;
  const char* unit;    // "dBm"/"dB"
};

struct DashData {
  String op;           // 运营商
  String mode;         // 5G SA / LTE …
  String badge;        // B3 · 1864.8 MHz
  Metric rsrp = {"RSRP", false, 0, "dBm"};
  Metric rsrq = {"RSRQ", false, 0, "dB"};
  Metric sinr = {"SINR", false, 0, "dB"};
  Metric rssi = {"RSSI", false, 0, "dBm"};
  String pci, earfcn, temp;
  bool atAlive = true;       // AT 实时通道是否可用
  uint32_t lastMs = 0;       // 最近一次成功刷新(毫秒)
  int wifiRssi = 0;
};

class Ui {
public:
  void begin();                                  // 初始化屏 + 字体
  void boot(const String& line);                 // 启动阶段提示
  void render(const DashData& d);                // 仪表盘全量渲染
  void renderError(const String& title, const String& detail); // 全屏错误
  void tickTime(uint32_t lastMs, bool atAlive);  // 每秒只刷新底部时间/状态
  void wifiLost();                               // WiFi 断开提示

private:
  void drawMetric(int col, int row, const Metric& m);   // 横屏/正方:2x2 块
  void drawRow(int row, const Metric& m, int rh);       // 竖条屏:单列行
  void renderStacked(const DashData& d);                // 竖条屏整屏
  void drawBottom(bool atAlive, uint32_t lastMs);
};
