#include "qmodem_client.h"
#include <WiFiClient.h>
#include <HTTPClient.h>
#include <ArduinoJson.h>

static WiFiClient s_wifi;

// ------------------------------------------------------------------
// JSON-RPC 基础
// ------------------------------------------------------------------

String QModemClient::rpcBody(long id, const char* sid, const char* obj, const char* method, const String& argsJson) {
  String s = "{\"jsonrpc\":\"2.0\",\"id\":";
  s += id;
  s += ",\"method\":\"call\",\"params\":[\"";
  s += sid;
  s += "\",\"";
  s += obj;
  s += "\",\"";
  s += method;
  s += "\",";
  s += argsJson.length() ? argsJson : String("{}");
  s += "]}";
  return s;
}

String QModemClient::urlenc(const String& s) {
  static const char* hex = "0123456789ABCDEF";
  String out;
  for (unsigned i = 0; i < s.length(); i++) {
    char c = s[i];
    if (isalnum(c) || c == '-' || c == '_' || c == '.' || c == '~') out += c;
    else if (c == ' ') out += '+';
    else {
      out += '%';
      out += hex[(c >> 4) & 0xF];
      out += hex[c & 0xF];
    }
  }
  return out;
}

bool QModemClient::postRpc(const String& body, String& resp, int& httpCode, String& err, bool withCookie) {
  HTTPClient http;
  http.setConnectTimeout(6000);
  http.setTimeout(15000);
  String url = _base + "/ubus";
  if (!_direct) url = _base + "/cgi-bin/luci/admin/ubus/rpc";
  if (!http.begin(s_wifi, url)) { err = "无效的地址"; return false; }
  http.addHeader("Content-Type", "application/json");
  if (withCookie && _cookie.length()) http.addHeader("Cookie", _cookie, true);
  httpCode = http.POST(body);
  if (httpCode <= 0) {
    err = http.errorToString(httpCode);
    http.end();
    return false;
  }
  resp = http.getString();
  http.end();
  return true;
}

// 解析 ubus JSON-RPC 响应:
// 返回 true 表示 HTTP+ubus 都成功;ubusCode 为 result[0];dataOut 为 result[1] 对象原文
static bool parseUbusResp(const String& resp, int& ubusCode, String& dataOut, bool& sessionExpired, String& err) {
  JsonDocument doc;
  DeserializationError e = deserializeJson(doc, resp);
  if (e) { err = "响应不是有效 JSON"; return false; }
  sessionExpired = false;
  JsonObject error = doc["error"].as<JsonObject>();
  if (!error.isNull()) {
    int code = error["code"] | -32000;
    const char* msg = error["message"] | "";
    String m(msg);
    if (code == -32002 || m.indexOf("Unauthorized") >= 0 ||
        m.indexOf("access denied") >= 0 || m.indexOf("session") >= 0) {
      sessionExpired = true;
      err = "会话已失效";
      return false;
    }
    ubusCode = code;
    err = String("RPC 错误：") + msg;
    return false;
  }
  JsonArray result = doc["result"].as<JsonArray>();
  if (result.isNull()) { err = "ubus 响应缺少 result"; return false; }
  ubusCode = result[0] | -1;
  if (ubusCode != 0) {
    // ubus 标准错误码文案
    static const char* texts[] = {"无效命令", "无效参数", "方法不存在", "对象不存在（未安装插件？）",
                                  "无数据", "权限不足", "执行超时", "操作不支持"};
    err = (ubusCode >= 1 && ubusCode <= 8) ? texts[ubusCode - 1] : String("ubus 错误 ") + ubusCode;
    if (ubusCode == 6) sessionExpired = true; // PERMISSION_DENIED 一般也是会话问题
    return false;
  }
  JsonVariant data = result[1];
  if (!data.isNull()) {
    String s;
    serializeJson(data, s);
    dataOut = s;
  } else {
    dataOut = "{}";
  }
  return true;
}

bool QModemClient::ubusCallRaw(const char* obj, const char* method, const String& args, String& resp, int& ubusCode, String& err) {
  if (!_sid.length()) { err = "尚未登录"; return false; }
  String body = rpcBody(++_id, _sid.c_str(), obj, method, args);
  int httpCode = 0;
  String out;
  bool ok = postRpc(body, out, httpCode, err, true);
  if (!ok) {
    if (httpCode == 401 || httpCode == 403) { err = "会话已失效"; ubusCode = -32002; }
    return false;
  }
  bool expired = false;
  String data;
  ok = parseUbusResp(out, ubusCode, data, expired, err);
  if (expired) ubusCode = -32002;
  resp = data;
  return ok;
}

// ubus 调用,会话失效自动重登录重试一次(与 App withSession 一致)
bool QModemClient::ubusCall(const char* obj, const char* method, const String& args, String& resp, int& ubusCode, String& err) {
  if (ubusCallRaw(obj, method, args, resp, ubusCode, err)) return true;
  if (ubusCode == -32002) {
    if (!login(err)) return false;
    return ubusCallRaw(obj, method, args, resp, ubusCode, err);
  }
  return false;
}

// ------------------------------------------------------------------
// 登录
// ------------------------------------------------------------------

bool QModemClient::login(String& err) {
  // 方式 1:直连 /ubus(uhttpd-mod-ubus)
  {
    JsonDocument args;
    args["username"] = _user;
    args["password"] = _pass;
    args["timeout"] = 7200;
    String aStr; serializeJson(args, aStr);
    String body = rpcBody(++_id, "00000000000000000000000000000000", "session", "login", aStr);
    _direct = true;
    int httpCode = 0;
    String out;
    if (postRpc(body, out, httpCode, err, false) && httpCode == 200) {
      JsonDocument doc;
      if (!deserializeJson(doc, out)) {
        const char* sid = doc["result"][1]["ubus_rpc_session"] | "";
        int rc = doc["result"][0] | -1;
        if (rc == 0 && strlen(sid)) {
          _sid = sid;
          _cookie = "";
          err = "";
          return true;
        }
      }
    }
  }

  // 方式 2:LuCI 表单登录(sysauth cookie)
  _direct = false;
  String form = "luci_username=" + urlenc(_user) + "&luci_password=" + urlenc(_pass);
  HTTPClient http;
  http.setConnectTimeout(6000);
  http.setTimeout(15000);
  if (!http.begin(s_wifi, _base + "/cgi-bin/luci/")) { err = "无效的地址"; return false; }
  http.addHeader("Content-Type", "application/x-www-form-urlencoded");
  int code = http.POST(form);
  if (code <= 0) { err = String("无法连接路由器：") + http.errorToString(code); http.end(); return false; }
  String setCookie = http.header("Set-Cookie");   // 取第一个 Set-Cookie
  http.end();

  // sysauth cookie 名(兼容 http/https 变体)
  String sidVal;
  int eq = setCookie.indexOf('=');
  String name = (eq > 0) ? setCookie.substring(0, eq) : "";
  name.trim(); name.toLowerCase();
  if (name.startsWith("sysauth")) {
    int sc = setCookie.indexOf(';');
    _cookie = (sc > 0) ? setCookie.substring(0, sc) : setCookie;
    _cookie.trim();
    sidVal = _cookie.substring(eq + 1);
  }
  if (!sidVal.length()) {
    err = String("登录失败：请检查用户名、密码和地址（HTTP ") + code + "）";
    return false;
  }
  _sid = sidVal;
  err = "";
  return true;
}

// ------------------------------------------------------------------
// 后端探测 / 模组选择
// ------------------------------------------------------------------

bool QModemClient::detectModem(String& err) {
  int code = 0;
  String resp;

  // 1) uci get config=qmodem → 找 modem section(新版 qmodem)
  if (ubusCall("uci", "get", "{\"config\":\"qmodem\"}", resp, code, err)) {
    JsonDocument doc;
    if (!deserializeJson(doc, resp)) {
      JsonObject values = doc["values"].as<JsonObject>();
      for (JsonPair kv : values) {
        JsonObject so = kv.value().as<JsonObject>();
        const char* type = so[".type"] | "";
        if (!strstr(type, "modem")) continue;
        const char* state = so["state"] | "";
        if (!strcmp(state, "disabled")) continue;
        _section = String(kv.key().c_str());
        _backend = QM_NEW_UBUS;
        return true;
      }
    }
  }

  // 2) 旧版批量 modem_ctrl
  if (ubusCall("modem_ctrl", "info", "{}", resp, code, err)) {
    JsonDocument doc;
    if (!deserializeJson(doc, resp)) {
      JsonArray info = doc["info"].as<JsonArray>();
      if (!info.isNull() && info.size() > 0) {
        _section = "#0";
        _backend = QM_MODEM_CTRL;
        return true;
      }
    }
  }
  err = "未检测到 qmodem 插件接口，请确认路由器已安装 luci-app-qmodem";
  return false;
}

bool QModemClient::connect(String& err) {
  if (!login(err)) return false;
  if (!detectModem(err)) return false;
  return true;
}

// ------------------------------------------------------------------
// 信息读取
// ------------------------------------------------------------------

static void addEntries(JsonArray arr, std::vector<InfoEntry>& out, std::vector<String>& seen) {
  for (JsonObject e : arr) {
    const char* type = e["type"] | "plain_text";
    if (!strcmp(type, "avalible_band")) continue;   // 与 App 一致:跳过可用频段大数组
    const char* key = e["key"] | "";
    JsonVariant vv = e["value"];
    if ((!strlen(key) && strcmp(type, "warning_message")) || vv.isNull()) continue;
    String value;
    if (vv.is<const char*>()) value = vv.as<String>();
    else { serializeJson(vv, value); }
    if (!value.length() && strcmp(type, "warning_message")) continue;

    String k(key);
    bool dup = false;
    for (auto& s : seen) if (s == k) { dup = true; break; }
    if (dup) continue;
    seen.push_back(k);

    InfoEntry en;
    en.key = k;
    en.value = value;
    en.type = String(type);
    en.unit = String(e["unit"] | "");
    out.push_back(en);
  }
}

bool QModemClient::fetchFull(std::vector<InfoEntry>& out, String& err) {
  int code = 0;
  String resp;
  std::vector<String> seen;

  if (_backend == QM_MODEM_CTRL) {
    if (!ubusCall("modem_ctrl", "info", "{}", resp, code, err)) return false;
    JsonDocument doc;
    if (deserializeJson(doc, resp)) { err = "响应解析失败"; return false; }
    JsonArray arr = doc["info"][0]["modem_info"].as<JsonArray>();
    if (arr.isNull()) { err = "未获取到模组数据"; return false; }
    addEntries(arr, out, seen);
    return true;
  }

  static const char* methods[] = {"info", "base_info", "cell_info", "network_info", "sim_info"};
  bool anyOk = false;
  for (auto m : methods) {
    JsonDocument args;
    args["config_section"] = _section;
    String aStr; serializeJson(args, aStr);
    String e2;
    if (ubusCall("qmodem", m, aStr, resp, code, e2)) {
      anyOk = true;
      JsonDocument doc;
      if (!deserializeJson(doc, resp)) {
        JsonArray arr = doc["modem_info"].as<JsonArray>();
        if (!arr.isNull()) addEntries(arr, out, seen);
      }
    } else {
      // 单个方法缺失/不支持不影响其它方法(与 App 一致)
      if (code == 2 || code == 3 || code == 4 || code == 8) continue;
      err = e2;
      return false;
    }
  }
  if (!anyOk) { err = "未获取到任何模组数据"; return false; }
  return true;
}

// ------------------------------------------------------------------
// 实时信号(AT 直查,绕过 10 秒缓存)
// ------------------------------------------------------------------

bool QModemClient::getAtPort(String& port, String& err) {
  if (_atPort.length()) { port = _atPort; return true; }
  int code = 0;
  String resp;
  if (!ubusCall("qmodem", "get_at_cfg", "{}", resp, code, err)) return false;
  JsonDocument doc;
  if (deserializeJson(doc, resp)) { err = "AT 口配置解析失败"; return false; }
  JsonObject atCfg = doc["at_cfg"].as<JsonObject>();
  if (atCfg.isNull()) atCfg = doc.as<JsonObject>();
  // 后端字段名为 using_port(与 App 修复一致)
  port = atCfg["using_port"] | "";
  if (!port.length()) port = atCfg["at_port"] | "";
  if (!port.length()) { err = "未找到可用的 AT 串口"; return false; }
  _atPort = port;
  return true;
}

bool QModemClient::sendAt(const String& at, const String& port, String& res, String& err) {
  int code = 0;
  String resp;
  JsonDocument args;
  JsonObject params = args["params"].to<JsonObject>();
  params["at"] = at;
  params["port"] = port;
  String aStr; serializeJson(args, aStr);
  if (!ubusCall("qmodem", "send_at", aStr, resp, code, err)) return false;
  JsonDocument doc;
  if (deserializeJson(doc, resp)) { err = "AT 响应解析失败"; return false; }
  JsonObject atCfg = doc["at_cfg"].as<JsonObject>();
  if (atCfg.isNull()) atCfg = doc.as<JsonObject>();
  const char* status = atCfg["status"] | "";
  if (strcmp(status, "1") != 0) {
    err = "AT 命令执行失败（模组或端口不可用）";
    return false;
  }
  res = atCfg["res"] | "";
  res.trim();
  return true;
}

bool QModemClient::fetchLive(LiveSignal& sig, String& ratName, String& err) {
  String port;
  if (!getAtPort(port, err)) return false;
  String res;
  if (sendAt("AT+QENG=\"servingcell\"", port, res, err) && Analyzer::parseQeng(res, sig, ratName)) {
    sig.ok = true;
    return true;
  }
  // QENG 不支持时回退 AT+CSQ(仅 RSSI)
  String e2;
  if (sendAt("AT+CSQ", port, res, e2) && Analyzer::parseCsq(res, sig)) {
    sig.ok = true;
    err = "";
    return true;
  }
  if (!err.length()) err = "模组未返回可解析的信号数据";
  return false;
}
