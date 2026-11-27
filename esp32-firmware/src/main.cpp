// QModem 助手 ESP32 固件 — 主程序
// 双通道刷新(与 App v1.1.2 一致):
//   全量:30 秒一轮(运营商/频段/制式/小区/温度)
//   实时:10 秒 AT 直查 AT+QENG="servingcell"(绕过 qmodem 后端 10 秒缓存)
//         连续失败 3 次自动降级为仅全量
#include <WiFi.h>
#include <vector>
#include "config.h"
#include "portal.h"
#include "qmodem_client.h"
#include "ui.h"

static Ui s_ui;
static QModemClient* s_client = nullptr;
static std::vector<InfoEntry> s_entries;
static LiveSignal s_live;
static bool s_atAlive = true;
static int s_atFail = 0;
static int s_fullFail = 0;
static bool s_haveData = false;
static uint32_t s_lastFull = 0, s_lastLive = 0, s_lastSec = 0, s_lastMs = 0;
static String s_lastErr;
static bool s_liveOk = false;
static String s_liveRat;
static PortalConfig s_cfg;

// 配网状态回调:屏幕显示提示
static void portalStatus(const String& s) {
  s_ui.boot(s);
}

// 进入配网(阻塞,提交后自动重启)
static void enterPortal() {
  WiFi.mode(WIFI_AP_STA);
  Portal::start(s_cfg, portalStatus);
}

// 长按 BOOT 键检测(运行时重配入口)
static void checkPortalButton() {
#if CFG_PORTAL_PIN >= 0
  static uint32_t held = 0;
  pinMode(CFG_PORTAL_PIN, INPUT_PULLUP);
  if (digitalRead(CFG_PORTAL_PIN) == LOW) {
    if (held == 0) held = millis();
    if (millis() - held >= CFG_PORTAL_HOLD_MS) {
      held = 0;
      enterPortal();  // 不会返回
    }
  } else {
    held = 0;
  }
#endif
}

// 返回是否连上;连不上由调用方决定是否进配网
static bool wifiConnect() {
  s_ui.boot("连接 WiFi…");
  WiFi.mode(WIFI_STA);
  WiFi.setSleep(false);
  WiFi.begin(s_cfg.wifiSsid, s_cfg.wifiPass);
  uint32_t t0 = millis();
  while (WiFi.status() != WL_CONNECTED) {
    if (millis() - t0 > 20000) break;
    delay(200);
  }
  return WiFi.status() == WL_CONNECTED;
}

static String findAnyWrap(const char* key) {
  return Analyzer::findAny(s_entries, {key});
}

// 从全量条目取信号值(降级时无实时数据)
static void fillFromFull(Metric& m, const char* key) {
  String v = findAnyWrap(key);
  v.trim();
  if (v.length()) {
    float f = v.toFloat();
    if (f != 0.0f || (v[0] == '0')) { m.has = true; m.value = f; }
  }
}

static void renderDashboard() {
  DashData d;
  d.op = Analyzer::operatorName(s_entries);
  d.mode = Analyzer::networkMode(s_entries);
  if (d.mode.length() && s_liveOk && s_liveRat.length()) d.mode = s_liveRat;
  d.badge = Analyzer::bandBadge(s_entries);
  d.pci = findAnyWrap("PCI");
  if (!d.pci.length()) d.pci = findAnyWrap("Physical Cell ID");
  d.earfcn = findAnyWrap("EARFCN");
  if (!d.earfcn.length()) d.earfcn = findAnyWrap("NR-ARFCN");
  String tmp = findAnyWrap("temperature");
  tmp.trim();
  if (tmp.length() && !tmp.equalsIgnoreCase("unknown")) d.temp = tmp;

  // 信号值:实时通道优先,降级后用全量
  if (s_liveOk && s_live.hasRsrp) { d.rsrp.has = true; d.rsrp.value = s_live.rsrp; } else fillFromFull(d.rsrp, "RSRP");
  if (s_liveOk && s_live.hasRsrq) { d.rsrq.has = true; d.rsrq.value = s_live.rsrq; } else fillFromFull(d.rsrq, "RSRQ");
  if (s_liveOk && s_live.hasSinr) { d.sinr.has = true; d.sinr.value = s_live.sinr; } else fillFromFull(d.sinr, "SINR");
  if (s_liveOk && s_live.hasRssi) { d.rssi.has = true; d.rssi.value = s_live.rssi; } else fillFromFull(d.rssi, "RSSI");

  d.atAlive = s_atAlive;
  d.lastMs = s_lastMs;
  d.wifiRssi = WiFi.RSSI();
  s_ui.render(d);
}

static bool doFull() {
  std::vector<InfoEntry> out;
  String err;
  if (!s_client->fetchFull(out, err)) {
    s_fullFail++;
    s_lastErr = err;
    if (s_fullFail >= 3) { s_ui.renderError("获取数据失败", err); s_haveData = false; }
    return false;
  }
  if (out.empty()) return false;
  s_entries = out;
  s_fullFail = 0;
  s_haveData = true;
  s_lastMs = millis();
  renderDashboard();
  return true;
}

static void doLive() {
  LiveSignal sig;
  String rat;
  String err;
  if (!s_client->fetchLive(sig, rat, err)) {
    s_atFail++;
    if (s_atFail >= CFG_AT_FAIL_LIMIT) {
      s_atAlive = false;
      s_liveOk = false;
      // 降级:立即按全量数据重绘,避免旧实时数据残留(与 App v1.1.2 修复一致)
      renderDashboard();
    }
    return;
  }
  s_atFail = 0;
  s_atAlive = true;
  s_live = sig;
  s_liveRat = rat;
  s_liveOk = true;
  s_lastMs = millis();
  renderDashboard();
}

void setup() {
  Serial.begin(115200);
  s_ui.begin();
  s_ui.boot("启动中…");

  // 1) 读 NVS 配置;首次开机(无配置)直接进配网
  if (!ConfigStore::load(s_cfg)) {
    s_cfg.routerUrl = CFG_ROUTER_URL;   // 路由器字段给个默认值,WiFi 留空等用户填
    s_cfg.routerUser = CFG_ROUTER_USER;
    enterPortal();  // 阻塞,提交后重启
  }

  // 2) 连 WiFi;连不上(密码错/信号问题)也进配网让用户重填
  if (!wifiConnect()) {
    enterPortal();
  }

  s_client = new QModemClient(s_cfg.routerUrl, s_cfg.routerUser, s_cfg.routerPass);
  s_ui.boot("连接路由器…");

  String err;
  uint32_t t0 = millis();
  while (true) {
    if (s_client->connect(err)) break;
    // 路由器连续连不上超过 60 秒 → 进配网重填路由器地址/密码
    if (millis() - t0 > 60000) { enterPortal(); }
    s_ui.renderError("连接路由器失败", err.length() ? err : String("检查地址/账号"));
    delay(3000);
    if (WiFi.status() != WL_CONNECTED && !wifiConnect()) { enterPortal(); }
  }

  s_ui.boot("获取模组信息…");
  doFull();
  s_lastFull = s_lastLive = millis();
  s_lastSec = millis();
}

void loop() {
  uint32_t now = millis();

  // 长按 BOOT 键 3 秒 → 重新配网
  checkPortalButton();

  // WiFi 掉线自动重连;长时间连不上进配网
  if (WiFi.status() != WL_CONNECTED) {
    s_ui.wifiLost();
    WiFi.reconnect();
    uint32_t t0 = millis();
    while (WiFi.status() != WL_CONNECTED && millis() - t0 < 15000) delay(300);
    if (WiFi.status() != WL_CONNECTED) { enterPortal(); }
    return;
  }

  if (now - s_lastFull >= CFG_FULL_INTERVAL_MS) {
    s_lastFull = now;
    doFull();
  }
  if (s_atAlive && now - s_lastLive >= CFG_LIVE_INTERVAL_MS) {
    s_lastLive = now;
    doLive();
  }
  // 降级后每 30 秒尝试恢复实时通道(与 App 手动刷新重置逻辑等效)
  if (!s_atAlive && now - s_lastLive >= CFG_FULL_INTERVAL_MS) {
    s_lastLive = now;
    s_atFail = 0;
    s_atAlive = true;
  }

  if (now - s_lastSec >= 1000) {
    s_lastSec = now;
    if (s_haveData) s_ui.tickTime(s_lastMs, s_atAlive);
  }

  delay(100);
}
