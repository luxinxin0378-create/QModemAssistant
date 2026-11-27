// QModem 助手 — OLED 极简渲染层(0.96寸 SSD1306 128x64 I2C)
// 只显示四项信号数值 + 频段,与 TFT 版共用 Ui 接口/协议层
// 编译环境:s3mini_oled_096(见 platformio.ini,用 build_src_filter 替换 ui.cpp)
#include "ui.h"
#include <U8g2lib.h>
#include <Wire.h>

// I2C 引脚:默认 SDA=8 / SCL=9(ESP32-S3 Arduino Wire 默认),可在 platformio.ini 改
#ifndef OLED_SDA
#define OLED_SDA 8
#endif
#ifndef OLED_SCL
#define OLED_SCL 9
#endif

// F = 全缓冲(128x64 = 1KB,裸板内存足够)
U8G2_SSD1306_128X64_NONAME_F_HW_I2C s_oled(U8G2_R0, U8X8_PIN_NONE, OLED_SCL, OLED_SDA);

static DashData s_last;
static bool s_hasData = false;
static String s_bootLine;
static String s_errTitle, s_errDetail;

static void drawStatusLine() {
  // 底栏:y=63 附近,左=实时/降级,右=距上次刷新秒数
  char buf[24];
  uint32_t age = s_last.lastMs ? (millis() - s_last.lastMs) / 1000 : 0;
  if (age > 99) age = 99;
  snprintf(buf, sizeof(buf), "%s %2us",
           s_last.atAlive ? "LIVE" : "SLOW", (unsigned)age);
  s_oled.setFont(u8g2_font_5x7_tf);
  s_oled.drawStr(2, 63, buf);
}

static void drawNumbers() {
  // 四行大数字:RSRP / RSRQ / SINR / RSSI
  const int y0 = 13, lh = 11;
  const char* labels[4] = {"RSRP", "RSRQ", "SINR", "RSSI"};
  const char* units[4]  = {"dBm",  "dB",   "dB",   "dBm"};
  float vals[4];
  bool has[4];
  vals[0] = s_last.rsrp.value; has[0] = s_last.rsrp.has;
  vals[1] = s_last.rsrq.value; has[1] = s_last.rsrq.has;
  vals[2] = s_last.sinr.value; has[2] = s_last.sinr.has;
  vals[3] = s_last.rssi.value; has[3] = s_last.rssi.has;

  for (int i = 0; i < 4; i++) {
    char line[32];
    if (has[i]) {
      // 数值统一保留 1 位小数,宽度对齐
      snprintf(line, sizeof(line), "%-4s %6.1f %s", labels[i], vals[i], units[i]);
    } else {
      snprintf(line, sizeof(line), "%-4s    --- %s", labels[i], units[i]);
    }
    s_oled.setFont(u8g2_font_6x10_tf);
    s_oled.drawStr(3, y0 + i * lh, line);
  }
}

static void drawHeader() {
  // 顶栏:制式 + 频段(纯 ASCII,避免中文字体占 Flash)
  char buf[32];
  s_oled.setFont(u8g2_font_5x7_tf);
  if (s_last.mode.length() || s_last.badge.length()) {
    String mode = s_last.mode;
    // 中文制式在 OLED 上回落为 MCC-MNC 之外的简短标记,只保留可打印 ASCII
    String ascii;
    for (unsigned int i = 0; i < mode.length(); i++) {
      char c = mode.charAt(i);
      if (c >= 32 && c <= 126) ascii += c;
    }
    snprintf(buf, sizeof(buf), "%s", ascii.length() ? ascii.c_str() : "NET");
    s_oled.drawStr(2, 9, buf);

    // 频段取 "B3 · 1864.8 MHz" 里的 Bx 与频率数字
    String badge = s_last.badge;
    String bshort;
    int bpos = badge.indexOf('B');
    if (bpos >= 0) {
      int sp = badge.indexOf(' ', bpos);
      bshort = badge.substring(bpos, sp > 0 ? sp : badge.length());
      int dot = badge.indexOf("MHz");
      if (dot > 0) {
        int p = dot - 1;
        while (p > bpos && badge.charAt(p) == ' ') p--;
        while (p > bpos && (isdigit(badge.charAt(p)) || badge.charAt(p) == '.')) p--;
        bshort += " " + badge.substring(p + 1, dot + 3);
      }
    }
    if (!bshort.length()) bshort = "LTE";
    snprintf(buf, sizeof(buf), "%s", bshort.c_str());
    // 右对齐
    int w = s_oled.getStrWidth(buf);
    s_oled.drawStr(126 - w, 9, buf);
  } else {
    s_oled.drawStr(2, 9, "QModem");
  }
  s_oled.drawLine(0, 11, 127, 11);
}

static void renderFull() {
  s_oled.clearBuffer();
  drawHeader();
  drawNumbers();
  drawStatusLine();
  s_oled.sendBuffer();
}

// ==================== Ui 接口实现 ====================

void Ui::begin() {
  Wire.begin(OLED_SDA, OLED_SCL);
  s_oled.begin();
  s_oled.setFont(u8g2_font_5x7_tf);
  s_oled.clearBuffer();
  s_oled.drawStr(2, 20, "QModem Assistant");
  s_oled.drawStr(2, 36, "OLED 128x64 I2C");
  s_oled.drawStr(2, 52, "Init...");
  s_oled.sendBuffer();
}

void Ui::boot(const String& line) {
  s_bootLine = line;
  s_oled.clearBuffer();
  s_oled.setFont(u8g2_font_5x7_tf);
  s_oled.drawStr(2, 24, "QModem Assistant");
  // 启动提示多为中文,OLED 只显示可打印部分,否则给点阵进度
  String ascii;
  for (unsigned int i = 0; i < line.length(); i++) {
    char c = line.charAt(i);
    if (c >= 32 && c <= 126) ascii += c;
  }
  s_oled.drawStr(2, 42, ascii.length() ? ascii.c_str() : "Loading...");
  s_oled.sendBuffer();
}

void Ui::render(const DashData& d) {
  s_last = d;
  s_hasData = true;
  renderFull();
}

void Ui::renderError(const String& title, const String& detail) {
  s_oled.clearBuffer();
  s_oled.setFont(u8g2_font_5x7_tf);
  String a;
  for (unsigned int i = 0; i < title.length(); i++) {
    char c = title.charAt(i);
    if (c >= 32 && c <= 126) a += c;
  }
  s_oled.drawStr(2, 24, a.length() ? a.c_str() : "ERROR");
  // detail 通常是英文/URL,截断到 20 字符一行
  String b = detail;
  if (b.length() > 21) b = b.substring(0, 21);
  s_oled.drawStr(2, 42, b.c_str());
  s_oled.sendBuffer();
}

void Ui::tickTime(uint32_t lastMs, bool atAlive) {
  if (!s_hasData) return;
  s_last.lastMs = lastMs;
  s_last.atAlive = atAlive;
  renderFull();
}

void Ui::wifiLost() {
  s_oled.clearBuffer();
  s_oled.setFont(u8g2_font_5x7_tf);
  s_oled.drawStr(2, 28, "WiFi lost...");
  s_oled.drawStr(2, 44, "Reconnecting");
  s_oled.sendBuffer();
}
