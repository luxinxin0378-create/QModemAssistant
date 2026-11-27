// 手机配网实现:Preferences(NVS)存配置 + SoftAP + DNSServer 强制门户 + WebServer 表单
#include "portal.h"
#include <Preferences.h>
#include <WiFi.h>
#include <WebServer.h>
#include <DNSServer.h>

static const char* NVS_NS = "qmodem";

// ==================== NVS 存储 ====================
bool ConfigStore::load(PortalConfig& c) {
  Preferences p;
  if (!p.begin(NVS_NS, true)) return false;  // readonly
  bool has = p.isKey("wifiSsid");
  if (has) {
    c.wifiSsid  = p.getString("wifiSsid", "");
    c.wifiPass  = p.getString("wifiPass", "");
    c.routerUrl = p.getString("routerUrl", "");
    c.routerUser= p.getString("routerUser", "");
    c.routerPass= p.getString("routerPass", "");
  }
  p.end();
  return has && c.wifiSsid.length() > 0;
}

void ConfigStore::save(const PortalConfig& c) {
  Preferences p;
  p.begin(NVS_NS, false);
  p.putString("wifiSsid",  c.wifiSsid);
  p.putString("wifiPass",  c.wifiPass);
  p.putString("routerUrl", c.routerUrl);
  p.putString("routerUser",c.routerUser);
  p.putString("routerPass",c.routerPass);
  p.end();
}

void ConfigStore::clear() {
  Preferences p;
  p.begin(NVS_NS, false);
  p.clear();
  p.end();
}

// ==================== 强制门户 ====================
static const byte DNS_PORT = 53;
static DNSServer dnsServer;
static WebServer server(80);
static PortalConfig* g_cfg = nullptr;
static void (*g_onStatus)(const String&) = nullptr;
static bool g_saved = false;

static void statusLine(const String& s) {
  if (g_onStatus) g_onStatus(s);
}

static String esc(const String& s) {
  String o;
  o.reserve(s.length() + 8);
  for (unsigned int i = 0; i < s.length(); i++) {
    char c = s.charAt(i);
    if (c == '<') o += "&lt;";
    else if (c == '>') o += "&gt;";
    else if (c == '&') o += "&amp;";
    else if (c == '"') o += "&quot;";
    else o += c;
  }
  return o;
}

static String page(const String& msg = "") {
  String h;
  h.reserve(1800);
  h += F("<!doctype html><html lang=zh><head><meta charset=utf-8>");
  h += F("<meta name=viewport content='width=device-width,initial-scale=1'>");
  h += F("<title>QModem 配网</title><style>");
  h += F("body{font-family:-apple-system,Arial;margin:0;padding:18px;background:#f2f4f7;color:#222}");
  h += F("h1{font-size:20px;margin:0 0 4px}p.tip{font-size:13px;color:#666;margin:0 0 16px}");
  h += F("label{display:block;font-size:14px;margin:14px 0 6px;font-weight:600}");
  h += F("input{width:100%;box-sizing:border-box;padding:12px;font-size:16px;border:1px solid #ccc;border-radius:8px}");
  h += F("button{width:100%;margin-top:22px;padding:14px;font-size:17px;color:#fff;background:#1a73e8;border:0;border-radius:8px}");
  h += F(".msg{background:#fff3cd;padding:10px;border-radius:6px;font-size:14px;margin-bottom:12px}");
  h += F("</style></head><body><h1>QModem 助手 · 配网</h1>");
  h += F("<p class=tip>填好后点保存,设备会自动重启并连接。</p>");
  if (msg.length()) { h += F("<div class=msg>"); h += esc(msg); h += F("</div>"); }
  h += F("<form method=post action=/save>");
  h += F("<label>WiFi 名称(2.4G)</label><input name=ssid value='"); h += esc(g_cfg->wifiSsid); h += F("'>");
  h += F("<label>WiFi 密码</label><input name=wpass type=password value='"); h += esc(g_cfg->wifiPass); h += F("'>");
  h += F("<label>路由器地址</label><input name=rurl value='"); h += esc(g_cfg->routerUrl.length() ? g_cfg->routerUrl : F("http://192.168.1.1")); h += F("'>");
  h += F("<label>路由器账号</label><input name=ruser value='"); h += esc(g_cfg->routerUser.length() ? g_cfg->routerUser : F("root")); h += F("'>");
  h += F("<label>路由器密码</label><input name=rpass type=password value='"); h += esc(g_cfg->routerPass); h += F("'>");
  h += F("<button type=submit>保存并重启</button></form></body></html>");
  return h;
}

static void handleRoot() {
  server.send(200, "text/html; charset=utf-8", page());
}

static void handleSave() {
  PortalConfig c;
  c.wifiSsid   = server.arg("ssid");
  c.wifiPass   = server.arg("wpass");
  c.routerUrl  = server.arg("rurl");
  c.routerUser = server.arg("ruser");
  c.routerPass = server.arg("rpass");
  c.routerUrl.trim();
  if (!c.routerUrl.startsWith("http")) c.routerUrl = "http://" + c.routerUrl;
  if (!c.wifiSsid.length() || !c.routerUrl.length()) {
    server.send(400, "text/html; charset=utf-8", page("WiFi 名称和路由器地址不能为空"));
    return;
  }
  ConfigStore::save(c);
  g_saved = true;
  String h = F("<!doctype html><html><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>已保存</title></head><body style='font-family:Arial;padding:24px'>");
  h += F("<h2>配置已保存</h2><p>设备正在重启并连接 WiFi…</p><p>可关闭本页。</p></body></html>");
  server.send(200, "text/html; charset=utf-8", h);
  statusLine("配置已保存,重启中…");
}

static void handleNotFound() {
  // 强制门户:任何未知路径都返回配置页(手机/系统的连网检测请求也会被导到这里)
  server.send(200, "text/html; charset=utf-8", page());
}

void Portal::start(const PortalConfig& current, void (*onStatus)(const String&)) {
  g_cfg = const_cast<PortalConfig*>(&current);  // 只读使用
  g_onStatus = onStatus;
  g_saved = false;

  WiFi.mode(WIFI_AP);
  WiFi.setSleep(false);
  WiFi.softAP("QModem-Setup");  // 开放热点,无需密码,方便手机连接
  IPAddress ip = WiFi.softAPIP();

  statusLine("配网:连接热点 QModem-Setup");
  delay(300);

  // 把所有域名的 DNS 解析到设备自身 → 手机自动弹出强制门户
  dnsServer.start(DNS_PORT, "*", ip);

  server.on("/", HTTP_GET, handleRoot);
  server.on("/save", HTTP_POST, handleSave);
  server.onNotFound(handleNotFound);
  server.begin();

  statusLine("配网:浏览器打开 192.168.4.1");

  uint32_t t0 = millis();
  while (!g_saved) {
    dnsServer.processNextRequest();
    server.handleClient();
    // 每 3 秒提醒一次热点名,便于屏幕轮播
    static uint32_t lastHint = 0;
    if (millis() - lastHint > 3000) {
      lastHint = millis();
      statusLine("配网热点 QModem-Setup");
    }
    if (millis() - t0 > 600000) break;  // 10 分钟无操作退出(重启后仍无配置会再进)
    delay(10);
  }

  dnsServer.stop();
  server.stop();
  delay(500);
  ESP.restart();
}
