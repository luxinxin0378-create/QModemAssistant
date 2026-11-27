#pragma once
// 协议层:移植自安卓 App LuciClient.kt
// 登录:直连 /ubus(JSON-RPC)→ 失败走 LuCI 表单登录(/cgi-bin/luci 代理)
// 后端:新版 ubus 对象 qmodem → 旧版 ubus 批量 modem_ctrl
#include <Arduino.h>
#include <vector>
#include "analyzer.h"

enum QmBackend { QM_NEW_UBUS = 0, QM_MODEM_CTRL };

class QModemClient {
public:
  QModemClient(const String& base, const String& user, const String& pass)
    : _base(base), _user(user), _pass(pass) {}

  // 登录 + 探测后端 + 选择第一个模组
  bool connect(String& err);

  // 全量信息:合并 info/base_info/cell_info/network_info/sim_info 的 modem_info 数组
  bool fetchFull(std::vector<InfoEntry>& out, String& err);

  // 实时信号:send_at AT+QENG(绕过 qmodem 后端 10 秒缓存),失败回退 AT+CSQ
  bool fetchLive(LiveSignal& sig, String& ratName, String& err);

  QmBackend backend() const { return _backend; }
  const String& section() const { return _section; }

private:
  bool login(String& err);
  bool detectModem(String& err);
  bool postRpc(const String& body, String& resp, int& httpCode, String& err, bool withCookie);
  bool ubusCallRaw(const char* obj, const char* method, const String& args, String& resp, int& ubusCode, String& err);
  bool ubusCall(const char* obj, const char* method, const String& args, String& resp, int& ubusCode, String& err);
  bool getAtPort(String& port, String& err);
  bool sendAt(const String& at, const String& port, String& res, String& err);

  static String rpcBody(long id, const char* sid, const char* obj, const char* method, const String& argsJson);
  static String urlenc(const String& s);

  String _base, _user, _pass;
  String _sid, _cookie;        // ubus session id / LuCI sysauth cookie
  bool _direct = false;        // true=直连 /ubus,false=LuCI 表单登录代理
  QmBackend _backend = QM_NEW_UBUS;
  String _section;
  String _atPort;
  long _id = 0;
};
