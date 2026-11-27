#pragma once
// 手机配网:NVS 存储配置 + SoftAP 强制门户配置页
#include <Arduino.h>

struct PortalConfig {
  String wifiSsid;
  String wifiPass;
  String routerUrl;
  String routerUser;
  String routerPass;
};

class ConfigStore {
public:
  // 读取已保存配置;返回 false 表示从未配置过
  static bool load(PortalConfig& c);
  static void save(const PortalConfig& c);
  static void clear();
};

class Portal {
public:
  // 阻塞式配网:开热点 + 强制门户,用户提交后保存并自动重启
  // onStatus 用于在屏幕显示当前提示(可传 nullptr)
  static void start(const PortalConfig& current, void (*onStatus)(const String&));
};
