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

// ---- 配网 ----
// 首次开机或 WiFi 连不上时,设备会自动开热点 "QModem-Setup" 让你用手机配置(见 README)
// 两个按键分工: RESET(EN) 键=断电复位; 本引脚=配网键,单击一下立即进入配网
// 大多数板子的 BOOT = GPIO0,即扩展板上的第二个按键;设为 -1 可禁用按键配网
#define CFG_PORTAL_PIN  0
#define CFG_PORTAL_DEBOUNCE_MS  40
