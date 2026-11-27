#pragma once
// ============================================================
// QModem 助手 ESP32 固件 — 用户配置(烧录前请修改本文件)
// ============================================================

// ---- 上网 WiFi(设备通过这个 WiFi 连到路由器) ----
#define CFG_WIFI_SSID   "你的WiFi名称"
#define CFG_WIFI_PASS   "你的WiFi密码"

// ---- OpenWrt 路由器(和 App 主界面填的一样) ----
#define CFG_ROUTER_URL  "http://192.168.1.1"   // 以 http:// 开头,不带末尾斜杠
#define CFG_ROUTER_USER "root"
#define CFG_ROUTER_PASS "你的路由器密码"

// ---- 刷新节奏(毫秒) ----
// 全量信息(运营商/频段/制式/小区)30 秒一轮;
// 实时信号走 AT 直查(绕过 qmodem 后端 10 秒缓存)10 秒一轮,连续失败 3 次自动降级
#define CFG_FULL_INTERVAL_MS  30000
#define CFG_LIVE_INTERVAL_MS  10000
#define CFG_AT_FAIL_LIMIT     3
