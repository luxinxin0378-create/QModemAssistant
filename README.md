# QModem 助手（Android）

一款用于查看 **OpenWrt 路由器 qmodem 插件**（luci-app-qmodem）蜂窝模组状态的安卓 App：
显示信号强度（RSRP / RSRQ / SINR / RSSI）、网络模式（5G SA / NSA / LTE）、运营商、频段、小区信息、SIM 卡与设备信息等，并支持自动刷新。

## 安装

1. 将 `QModemAssistant-v1.0.0.apk` 传到手机（微信 / QQ / USB 均可）；
2. 点击安装（如提示"未知来源应用"，允许一次即可；APK 已用自签名证书正式签名）；
3. 要求 Android 7.0（API 24）及以上。

## 使用方法

1. 手机与路由器处于**同一局域网**（或能访问路由器的网络）；
2. 打开 App，在主界面输入：
   - **路由器地址**：直接填 IP（如 `192.168.1.1`），也支持 `http://192.168.1.1:8080` 这种带协议/端口的形式；
   - **端口**：默认 80（地址里带了端口会自动识别）；
   - **用户名 / 密码**：路由器 LuCI 管理账号（默认 root）；
3. 勾选 **记住密码并保存设备** 后，该路由器会被保存，下次打开直接从"已保存设备"下拉框选择即可一键回填；不需要时点右侧垃圾桶图标删除；
4. 点 **连接路由器** → 进入仪表盘：
   - 弧形仪表显示主信号（RSRP/RSCP/RSSI 优先）与质量评级（极佳 / 良好 / 一般 / 较差，按 qmodem 同款阈值）；
   - 徽标显示 **网络模式、运营商（ISP 或 MCC/MNC 自动识别中国移动/联通/电信/广电）、频段、模组温度**；
   - 下方卡片按 qmodem 原生分组展示全部字段：设备信息 / SIM 卡信息 / 小区信息 / 网络信息，条形图按 min/max 区间渲染；
   - **实时刷新**：默认每 2.5 秒通过 AT 命令（`AT+QENG="servingcell"`）直查模组实时信号，绕过 qmodem 后端的 10 秒缓存；其余字段（频段/小区/运营商等）每 12 秒全量同步一次。右上角开关可关闭，也可下拉或点刷新按钮手动刷新。

## 实时刷新原理

qmodem 插件对 info 类接口有 **10 秒结果缓存**（`/tmp/cache_*`），所以普通轮询最快也只能 10 秒变一次。本 App 的做法：

- **信号**：走 `send_at` ubus 接口直发 `AT+QENG="servingcell"`，直接读模组，2.5 秒一次，解析出 RSRP/RSRQ/SINR/RSSI 实时更新仪表与信号条（支持 LTE / NR5G-SA / NR5G-NSA / WCDMA / GSM 行格式，QENG 不支持时回退 `AT+CSQ`）；
- **其余字段**：每 12 秒全量拉取 info（频段、小区、运营商等低频变化的数据）；
- **自动降级**：连续 3 次 AT 查询失败（模组不支持 QENG/串口被占用等）会自动切回常规刷新模式并提示；**点手动刷新会给实时通道一次复活机会**，成功即恢复实时模式。

> v1.1.2 修复：AT 命令不再带 `use_ubus_flag` 参数（qmodem 后端会走 `at -u` 即 ubus_at_daemon 代理，多数路由器未运行该守护进程导致 AT 全部失败）；AT 端口改读后端 `using_port` 字段；降级后不再用旧的实时值覆盖新数据（此前会造成"信号永远不变"的现象）。

## 频段与运营商的直观翻译

App 内置 3GPP 频点映射表（LTE EARFCN 全表、NR ARFCN、WCDMA UARFCN、GSM ARFCN），把路由器上报的原始编号翻译成人话：

| 路由器原始值 | App 显示 |
|---|---|
| EARFCN `1798` | **1798（B3 · 1864.8 MHz）** —— B3 即联通/电信 1800 MHz 频段 |
| EARFCN `40625` | 40625（B41 · 2593.5 MHz）—— 移动 2.6 GHz |
| NR-ARFCN `636668` | 636668（n78 · 3.55 GHz）—— 电信/联通 3.5 GHz |
| Band `3` | 3（B3 · 1864.8 MHz） |
| 运营商 ISP `46001` | **中国联通（46001）** |
| MCC `460` / MNC `01` | 460（中国） / 01（中国联通） |
| connect_status `Yes`、SIM Status `Ready` | 是、就绪 |

顶部徽标会合并显示频段与频率（如 **频段 B3 · 1864.8 MHz**），无需再查表。

## 关于「网络制式偏好」显示全关

制式偏好（3G/4G/5G 开关）的当前状态来自 qmodem 后端的 `get_network_prefer`，后端是通过 AT 命令（Quectel 系：`AT+QNWPREFCFG="mode_pref"`）查询模组的。如果出现**三个开关全是关闭**，通常是：

- 模组不响应这条 AT 命令（部分厂商固件用自家命令集）；
- AT 串口被拨号进程占用、查询超时。

此时可到「AT 命令调试」执行 `AT+QNWPREFCFG="mode_pref"` 自查（App 已内置该快捷命令）；若模组有响应，说明是 qmodem 版本问题。切换制式保存后 App 会自动回读确认。

## 兼容性说明

App 会自动探测路由器上的 qmodem 形态，以下三种均支持：

| 版本 | 探测方式 | 说明 |
|---|---|---|
| 新版 QModem（GWan1234/QModem，rpcd ubus 对象 `qmodem`） | ① 直连 `/ubus`（uhttpd-mod-ubus）② LuCI 登录后经 `/cgi-bin/luci/admin/ubus` 代理 | 方法：`base_info / cell_info / info / network_info / sim_info` |
| 旧版 qmodem（Lua LuCI 版） | LuCI 登录后调用 `/cgi-bin/luci/admin/modem/qmodem/modem_ctrl`、`get_modem_cfg` | 同一套 `modem_info` 数据结构 |
| 兜底 | 旧版 ubus 对象 `modem_ctrl` 批量信息 | 多模组自动拆分为"模组 1 / 模组 2…" |

登录方式：先尝试 OpenWrt 标准 `/ubus` JSON-RPC（`session.login`），失败则走 LuCI 表单登录（`sysauth_http` / `sysauth` 会话 Cookie），并支持会话过期自动重登。多个模组可在仪表盘顶部下拉切换。

## 已验证

- 内置 JVM 单元测试（20 项全部通过）：模拟 LuCI/qmodem 服务器验证表单登录、/ubus 直连、ubus 代理调用、模组枚举、信息合并去重、401 会话过期自动重登、旧版 HTTP 回退、信号阈值评级、N/A 容错、频点→频段映射（B3/B41/n78/n41/WCDMA B1）、PLMN→运营商翻译等。
- 运行：`gradle test`（工程内已配置 MockWebServer 模拟）。

## 目录结构

```
QModemAssistant/
├── app/src/main/java/com/qmodem/app/
│   ├── MainActivity.kt          # 连接页：地址/账号/密码/保存设备
│   ├── DashboardActivity.kt     # 仪表盘：信号/网络/频段/分组信息 + 自动刷新
│   ├── net/LuciClient.kt        # LuCI 登录 + ubus JSON-RPC + 旧版 HTTP（核心协议层）
│   ├── net/BandMap.kt           # 频点→频段/频率映射（LTE/NR/WCDMA/GSM，3GPP 标准）
│   ├── net/InfoAnalyzer.kt      # 字段提取/中文名/运营商/频段翻译/信号评级
│   ├── model/Models.kt          # 数据模型
│   ├── store/DeviceStore.kt     # 保存设备（SharedPreferences，密码 Base64 混淆存储）
│   └── ui/GaugeView.kt          # 弧形信号仪表控件
├── app/src/test/                # JVM 单元测试（MockWebServer 模拟路由器）
└── keystore/qmodem.keystore     # 签名密钥（storepass/keypass：qmodem2026）
```

## 从源码构建

```bash
# 需要 JDK 17+ 与 Android SDK (platforms;android-34, build-tools;34.0.0)
# local.properties 中配置 sdk.dir=/path/to/android-sdk
gradle assembleRelease   # 输出 app/build/outputs/apk/release/app-release.apk
gradle test              # 运行单元测试
```

## 安全提示

- "记住密码"仅将密码以 Base64 混淆保存在本机 App 私有目录（SharedPreferences），未做加密存储；如介意可取消勾选（仅保存地址与用户名）。
- App 与路由器之间为 HTTP 明文通信（路由器 LuCI 本身即 HTTP），请仅在可信局域网内使用。

## 常见问题

- **提示"登录失败"**：检查地址/端口/账号密码；路由器若修改过 LuCI 端口请一并填写。
- **提示"未发现任何模组"**：路由器需已安装 luci-app-qmodem 且模组识别正常（可在 LuCI 网页端确认）。
- **提示"权限不足"**：会话 ACL 受限，请使用 root 账号登录。
- **数据一直是 N/A**：模组未注网或厂商脚本不支持当前模组，与 App 无关，可对比 LuCI 网页端显示。
