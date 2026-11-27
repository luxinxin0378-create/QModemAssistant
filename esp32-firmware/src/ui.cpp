#include "ui.h"
#include <TFT_eSPI.h>
#include <U8g2_for_Adafruit_GFX.h>
#include <Adafruit_GFX.h>

// 屏幕旋转方向由 platformio.ini 的 QM_ROTATION 宏指定:
// 横屏板(CYD / WT32-SC01 Plus)= 1,微雪竖屏板(1.47 / 1.54)= 0
#ifndef QM_ROTATION
#define QM_ROTATION 1
#endif

static TFT_eSPI s_tft;

// TFT_eSPI 不是 Adafruit_GFX 派生类,做一个最小 shim 供 U8g2 字体引擎绘制
class TftGfxShim : public Adafruit_GFX {
public:
  explicit TftGfxShim(TFT_eSPI& t) : Adafruit_GFX(t.width(), t.height()), _t(t) {}
  void drawPixel(int16_t x, int16_t y, uint16_t c) override { _t.drawPixel(x, y, c); }
private:
  TFT_eSPI& _t;
};
static TftGfxShim* s_shim = nullptr;
static U8G2_FOR_ADAFRUIT_GFX s_u8g2;

// 调色板
#define COL_TEXT   TFT_WHITE
#define COL_DIM    0x630C
#define COL_ACCENT 0x05FF
#define COL_BAR_BG 0x2104

// 窄屏(微雪 1.47,172 宽)走单列 4 行布局,其余(240x240 / 横屏)走 2x2
static bool stacked() { return s_tft.width() < 240; }

// 横屏布局:顶栏 0..24 / 运营商 26..52 / 频段 54..78 / 指标 2x2 / 详情 / 底栏(高20)
static int metH() { return (s_tft.height() - 80 - 42) / 2; }

// 竖屏布局常量:顶栏 0..22 / 运营商 / 频段 70 / 四行指标 / 详情 / 底栏
#define STK_TOP 74
static int stkRowH() { return (s_tft.height() - STK_TOP - 48) / 4; }

static void zh(const String& s, int x, int y, uint16_t color) {
  s_u8g2.setForegroundColor(color);
  s_u8g2.setCursor(x, y);
  s_u8g2.print(s);
}

void Ui::begin() {
  s_tft.init();
  s_tft.setRotation(QM_ROTATION);
  s_tft.fillScreen(TFT_BLACK);
  if (!s_shim) s_shim = new TftGfxShim(s_tft);
  s_u8g2.begin(*s_shim);
  s_u8g2.setFont(u8g2_font_unifont_t_chinese2);
  // 背光由 platformio.ini 的 TFT_BL/TFT_BACKLIGHT_ON 宏自动控制
}

void Ui::boot(const String& line) {
  s_tft.fillScreen(TFT_BLACK);
  int w = s_tft.width(), h = s_tft.height();
  zh("QModem 助手", w / 2 - 72, h / 2 - 20, COL_TEXT);
  zh(line, w / 2 - 72, h / 2 + 10, COL_DIM);
}

void Ui::renderError(const String& title, const String& detail) {
  s_tft.fillScreen(TFT_BLACK);
  int w = s_tft.width(), h = s_tft.height();
  zh(title, 12, h / 2 - 20, 0xF800);
  if (detail.length()) {
    String d = detail; d.trim();
    zh(d.substring(0, 24), 12, h / 2 + 10, COL_DIM);
    if (d.length() > 24) zh(d.substring(24, 48), 12, h / 2 + 30, COL_DIM);
  }
}

void Ui::wifiLost() {
  int w = s_tft.width();
  s_tft.fillRect(0, 0, w, 24, 0x6000);
  zh("WiFi 断开，重连中…", w / 2 - 96, 18, TFT_BLACK);
}

static uint16_t modeColor(const String& mode) {
  if (mode == "5G SA") return 0x07E0;
  if (mode == "5G NSA") return 0x05FF;
  if (mode == "5G") return 0x05FF;
  if (mode == "LTE") return 0xFD20;
  if (mode == "WCDMA") return 0xFFE0;
  return 0x630C;
}

// 指标数值 → 信号条比例(0..1)
static float metricRatio(const Metric& m) {
  if (!m.has) return 0;
  String k(m.label);
  float v = m.value;
  if (k == "RSRP" || k == "RSSI") return (v + 110.0f) / 60.0f;   // -110..-50
  if (k == "RSRQ") return (v + 20.0f) / 15.0f;                   // -20..-5
  if (k == "SINR") return v / 30.0f;                             // 0..30
  return 0;
}

// ------------------------------------------------------------------
// 横屏 / 正方屏:2x2 指标块
// ------------------------------------------------------------------
void Ui::drawMetric(int col, int row, const Metric& m) {
  int W = s_tft.width();
  int colW = W / 2;
  int mh = metH();
  int x = col * colW, y = 80 + row * mh;
  s_tft.fillRect(x, y, colW - 1, mh, TFT_BLACK);

  s_tft.setTextColor(COL_DIM, TFT_BLACK);
  s_tft.drawString(m.label, x + 8, y + 2, 2);

  if (m.has) {
    char vb[16];
    snprintf(vb, sizeof(vb), "%.0f", (double)m.value);
    SigQuality q = Analyzer::quality(m.label, m.value);
    s_tft.setTextColor(Analyzer::qualityColor(q), TFT_BLACK);
    s_tft.drawString(vb, x + 8, y + 20, 4);
    int tw = s_tft.textWidth(vb, 4);
    s_tft.setTextColor(COL_DIM, TFT_BLACK);
    s_tft.drawString(m.unit, x + 8 + tw + 6, y + 32, 2);

    float r = metricRatio(m);
    if (r < 0) r = 0; if (r > 1) r = 1;
    int bw = (int)((colW - 20) * r);
    s_tft.fillRect(x + 8, y + mh - 14, colW - 20, 8, COL_BAR_BG);
    if (bw > 0) s_tft.fillRect(x + 8, y + mh - 14, bw, 8, Analyzer::qualityColor(q));
  } else {
    s_tft.setTextColor(COL_DIM, TFT_BLACK);
    s_tft.drawString("--", x + 8, y + 20, 4);
    s_tft.fillRect(x + 8, y + mh - 14, colW - 20, 8, COL_BAR_BG);
  }
}

// ------------------------------------------------------------------
// 竖条屏(172 宽):单列 4 行,每行 = 标签+单位 / 右侧大值 / 信号条
// ------------------------------------------------------------------
void Ui::drawRow(int row, const Metric& m, int rh) {
  int W = s_tft.width();
  int y = STK_TOP + row * rh;
  s_tft.fillRect(0, y, W, rh, TFT_BLACK);

  s_tft.setTextColor(COL_DIM, TFT_BLACK);
  s_tft.drawString(m.label, 4, y + 1, 2);
  int lw = s_tft.textWidth(m.label, 2);
  s_tft.drawString(m.unit, 4 + lw + 6, y + 1, 2);

  if (m.has) {
    char vb[16];
    snprintf(vb, sizeof(vb), "%.0f", (double)m.value);
    SigQuality q = Analyzer::quality(m.label, m.value);
    s_tft.setTextColor(Analyzer::qualityColor(q), TFT_BLACK);
    s_tft.drawRightString(vb, W - 8, y + 13, 4);
    float r = metricRatio(m);
    if (r < 0) r = 0; if (r > 1) r = 1;
    int bw = (int)((W - 12) * r);
    s_tft.fillRect(4, y + rh - 9, W - 12, 7, COL_BAR_BG);
    if (bw > 0) s_tft.fillRect(4, y + rh - 9, bw, 7, Analyzer::qualityColor(q));
  } else {
    s_tft.setTextColor(COL_DIM, TFT_BLACK);
    s_tft.drawRightString("--", W - 8, y + 13, 4);
    s_tft.fillRect(4, y + rh - 9, W - 12, 7, COL_BAR_BG);
  }
}

void Ui::renderStacked(const DashData& d) {
  int W = s_tft.width(), H = s_tft.height();

  // 顶栏:标题 + WiFi
  zh("QModem 助手", 3, 17, COL_TEXT);
  char wb[16];
  snprintf(wb, sizeof(wb), "WiFi %d", d.wifiRssi);
  s_tft.setTextColor(COL_DIM, TFT_BLACK);
  s_tft.drawRightString(String(wb), W - 3, 5, 2);
  s_tft.drawFastHLine(0, 22, W, COL_BAR_BG);

  // 运营商 + 制式徽标
  String op = d.op.length() ? d.op : String("未识别运营商");
  zh(op, 3, 42, COL_TEXT);
  if (d.mode.length()) {
    int mw = s_tft.textWidth(d.mode, 2) + 10;
    int mx = W - 3 - mw;
    s_tft.fillRoundRect(mx, 26, mw, 17, 3, modeColor(d.mode));
    s_tft.setTextColor(TFT_BLACK, modeColor(d.mode));
    s_tft.drawString(d.mode, mx + 5, 28, 2);
  }

  // 频段徽标行
  if (d.badge.length()) zh(d.badge, 3, 64, COL_ACCENT);
  s_tft.drawFastHLine(0, 70, W, COL_BAR_BG);

  // 四行指标
  int rh = stkRowH();
  drawRow(0, d.rsrp, rh);
  drawRow(1, d.rsrq, rh);
  drawRow(2, d.sinr, rh);
  drawRow(3, d.rssi, rh);

  // 详情行:PCI / EARFCN / 温度
  int bottom = STK_TOP + 4 * rh;
  s_tft.drawFastHLine(0, bottom, W, COL_BAR_BG);
  String detail = "";
  if (d.pci.length()) detail += "PCI " + d.pci;
  if (d.earfcn.length()) {
    if (detail.length()) detail += " ";
    detail += "E" + d.earfcn;
  }
  if (d.temp.length()) {
    if (detail.length()) detail += " ";
    detail += d.temp + "C";
  }
  s_tft.setTextColor(COL_DIM, TFT_BLACK);
  s_tft.drawString(detail, 3, bottom + 3, 2);

  drawBottom(d.atAlive, d.lastMs);
}

void Ui::drawBottom(bool atAlive, uint32_t lastMs) {
  int W = s_tft.width(), H = s_tft.height();
  int bh = stacked() ? 22 : 20;
  s_tft.fillRect(0, H - bh, W, bh, TFT_BLACK);
  s_tft.drawFastHLine(0, H - bh - 1, W, COL_BAR_BG);
  if (atAlive) {
    zh(stacked() ? "实时刷新中" : "实时刷新中（10 秒）", 4, H - 6, 0x07E0);
  } else {
    zh(stacked() ? "已降级 30 秒全量" : "AT 不可用，已降级 30 秒全量", 4, H - 6, 0xFD20);
  }
  int sec = (int)((millis() - lastMs) / 1000);
  char tb[12];
  snprintf(tb, sizeof(tb), "%ds", sec);
  s_tft.fillRect(W - 56, H - bh + 2, 56, bh - 2, TFT_BLACK);
  s_tft.setTextColor(COL_DIM, TFT_BLACK);
  s_tft.drawRightString(tb, W - 6, H - bh + 6, 2);
}

void Ui::render(const DashData& d) {
  int W = s_tft.width(), H = s_tft.height();
  s_tft.fillScreen(TFT_BLACK);

  if (stacked()) {
    renderStacked(d);
    return;
  }

  // 顶栏
  s_tft.drawFastHLine(0, 24, W, COL_BAR_BG);
  char wb[16];
  snprintf(wb, sizeof(wb), "WiFi %d", d.wifiRssi);
  s_tft.setTextColor(COL_DIM, TFT_BLACK);
  s_tft.drawRightString(String(wb), W - 4, 6, 2);

  // 运营商 + 制式徽标
  String op = d.op.length() ? d.op : String("未识别运营商");
  zh(op, 4, 44, COL_TEXT);
  if (d.mode.length()) {
    int mw = s_tft.textWidth(d.mode, 2) + 12;
    int mx = W - 4 - mw;
    s_tft.fillRoundRect(mx, 28, mw, 18, 3, modeColor(d.mode));
    s_tft.setTextColor(TFT_BLACK, modeColor(d.mode));
    s_tft.drawString(d.mode, mx + 6, 31, 2);
  }

  // 频段徽标行
  if (d.badge.length()) zh(d.badge, 4, 70, COL_ACCENT);
  s_tft.drawFastHLine(0, 78, W, COL_BAR_BG);

  // 四个指标块(2x2,自适应块高)
  drawMetric(0, 0, d.rsrp);
  drawMetric(1, 0, d.rsrq);
  drawMetric(0, 1, d.sinr);
  drawMetric(1, 1, d.rssi);
  int metBottom = 80 + 2 * metH();
  s_tft.drawFastVLine(W / 2 - 1, 80, 2 * metH(), COL_BAR_BG);

  // 详情行:PCI / EARFCN / 温度
  int detY = metBottom + 3;
  s_tft.fillRect(0, metBottom + 1, W, 14, TFT_BLACK);
  String detail = "";
  if (d.pci.length()) detail += "PCI " + d.pci;
  if (d.earfcn.length()) {
    if (detail.length()) detail += "  ";
    detail += "EARFCN " + d.earfcn;
  }
  if (d.temp.length()) {
    if (detail.length()) detail += "  ";
    detail += d.temp + "C";
  }
  s_tft.setTextColor(COL_DIM, TFT_BLACK);
  s_tft.drawString(detail, 4, detY, 2);

  drawBottom(d.atAlive, d.lastMs);
}

void Ui::tickTime(uint32_t lastMs, bool atAlive) {
  drawBottom(atAlive, lastMs);
}
