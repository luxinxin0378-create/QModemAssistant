# QModem 助手 ESP32 固件(带屏显示版)

把「QModem 助手」App 的仪表盘搬到一个 **ESP32 + 小屏幕** 的独立硬件上:开机自动连 WiFi、登录 OpenWrt 路由器、读取 qmodem 模组信息,在屏幕上常驻显示运营商、网络制式、频段(B3 · 1864.8 MHz)和 RSRP / RSRQ / SINR / RSSI 四项信号,并按信号强弱变色。

协议实现与安卓 App 同源(见上方目录的 App 源码):

- 登录:直连 `/ubus` JSON-RPC → 失败自动走 LuCI 表单登录(sysauth Cookie)
- 后端:新版 ubus 对象 `qmodem`(info/base_info/cell_info/network_info/sim_info)→ 旧版 `modem_ctrl` 批量
- 频段:3GPP 频点表(EARFCN/ARFCN → B3 · 1864.8 MHz),与 App 的 BandMap 一致
- 实时信号:10 秒一轮 `send_at` 直查 `AT+QENG="servingcell"`(绕过 qmodem 后端 10 秒缓存),连续失败 3 次自动降级为 30 秒全量刷新

## 推荐硬件(四档任选,固件已全部适配)

| 方案 | 硬件 | 价格参考 | 屏幕特征 | 烧录环境 |
|------|------|---------|---------|---------|
| 微雪 1.54 寸 | ESP32-S3-LCD-1.54(-Touch) | ~60-90 元 | **240x240 正方形** IPS,S3R8(16MB Flash+8MB PSRAM),Type-C,带外壳/喇叭/电池口 | `pio run -e waveshare_s3_154` |
| 微雪 1.47 寸 | ESP32-S3-LCD-1.47(B 型) | ~40-75 元 | **172x320 竖条屏**,S3R8,USB-A 直插更小巧 | `pio run -e waveshare_s3_147` |
| 大屏 | WT32-SC01 Plus | ~60-80 元 | 3.5 寸 480x320 电容触摸,S3 并口屏,信息最全 | `pio run -e wt32_sc01_plus` |
| 最低成本 | ESP32-2432S028R「CYD 小黄屏」 | ~20 元 | 2.8 寸 320x240 触摸,经典 ESP32 | `pio run -e cyd_ili9341` |
| 裸板自配 | **ESP32-S3-MINI 裸板 + 外接 SPI 屏** | ~15 元起(板)+~15 元(屏) | 板子便宜但**不带屏**,自己接一块 ST7789 | `pio run -e s3mini_spi` |
| **极简数值** | ESP32-S3-MINI 裸板 + **0.96 寸 IIC OLED**(SSD1306 128x64) | ~15 元(板)+~8 元(屏) | **只显示四项信号数值**,4 根线,最省事 | `pio run -e s3mini_oled_096` |

屏幕布局自动适配:窄条屏(172 宽)显示单列四行指标,240x240 及横屏显示 2x2 四块,内容一致(运营商/制式/频段/RSRP/RSRQ/SINR/RSSI/PCI/温度)。**OLED 版是独立极简界面**:顶栏显示制式与频段,中间四行大数字 RSRP/RSRQ/SINR/RSSI,底栏 LIVE/SLOW + 刷新秒数——只保留数值,协议层(登录/ubus/AT 实时刷新/降级)与带屏版完全一致。

> 芯片怎么选:**ESP32-C3 不建议**(内存小、驱动屏吃力、引脚少);以上几款中微雪两块与 WT32-SC01 Plus 均为 **ESP32-S3**(性能余量大,推荐),CYD 为经典 ESP32(最便宜)。
> 注意:需要 2.4G WiFi(ESP32 不支持 5G WiFi);微雪 1.47/1.54 屏为 ST7789 SPI 接口,已按官方引脚配置好。

### 用 ESP32-S3-MINI 裸板可以吗?

**可以**,芯片和 WiFi 完全够用(固件 RAM 仅占 15%。裸板唯一的区别是**不自带屏幕**,需要自己外接一块 SPI 屏并接 7 根线。适合手上已有裸板、或想极致压成本的情况;追求省心建议直接买上面的带屏一体板。

**推荐外接屏**:2.4 寸 ST7789 SPI 模块(240x320,约 15 元),或 1.47 寸 172x320 竖条屏。接线(固件默认引脚,可改 `platformio.ini` 里 `s3mini_spi` 段的 `TFT_*`):

| 屏幕引脚 | 接 S3-MINI 板 GPIO | 说明 |
|---------|-------------------|------|
| VCC | 3V3 | 屏电源(确认屏是 3.3V,5V 屏要加电平转换) |
| GND | GND | 共地 |
| SCL/SCK | IO12 | SPI 时钟 |
| SDA/MOSI | IO11 | SPI 数据 |
| CS | IO10 | 片选 |
| DC | IO13 | 数据/命令 |
| RST | IO14 | 复位 |
| BLK/背光 | IO9 | 背光(高电平亮) |

> 避开 IO26~IO32:这些脚被 S3 内部 Flash/PSRAM 占用,接屏会冲突。若你的是 N16R8(16MB+8MB PSRAM),当前配置也能跑(只是没启用 PSRAM,固件用不到)。换 1.47 寸 172x320 屏时,把 `s3mini_spi` 段的 `TFT_WIDTH=240` / `TFT_HEIGHT=320` 两个值对调即可。

### 0.96 寸 IIC OLED(SSD1306 128x64)接线

只要数值的话这块最省事——**4 根线**,且 IIC 只占两个 GPIO。默认 SDA=IO8 / SCL=IO9(可在 `platformio.ini` 的 `s3mini_oled_096` 段改 `OLED_SDA` / `OLED_SCL`):

| OLED 引脚 | 接 S3-MINI 板 GPIO |
|----------|-------------------|
| VCC | 3V3 |
| GND | GND |
| SCL | IO9 |
| SDA | IO8 |

> 注意:0.96 寸 OLED 必须是 **SSD1306 驱动、128x64、IIC(I2C)接口**;同样是 0.96 寸的 SH1106 驱动需改构造函数(把 `U8G2_SSD1306...` 换成 `U8G2_SH1106...`)。SPI 接口的 OLED 用不了这套配置。此版界面为纯数值,不显示中文运营商(中文点阵会占满 Flash),频段与制式以 ASCII 显示。

## 使用步骤

1. 安装 [VSCode + PlatformIO 插件](https://platformio.org/install/ide)(或命令行 `pip3 install platformio`);
2. 修改 `include/config.h`,填入 WiFi 名称/密码、路由器地址/账号/密码(和 App 主界面填的一样);
3. USB 连接开发板,执行 `pio run -t upload`(VSCode 里点底部 "Upload");
4. 烧录完成自动重启,屏幕依次显示「启动中 → 连接 WiFi → 连接路由器 → 获取模组信息」,随后进入仪表盘。

## 屏幕内容

- **顶栏**:QModem 助手 + WiFi 信号强度
- **运营商 + 制式徽标**:如「中国移动」「5G SA」(徽标颜色按制式区分)
- **频段行**:如 `B3 · 1864.8 MHz`
- **四个信号块**(2x2,数值颜色 = 信号评级):RSRP(dBm)/ RSRQ(dB)/ SINR(dB)/ RSSI(dBm),每块带信号条
- **详情行**:PCI / EARFCN / 模组温度
- **底栏**:左「实时刷新中(10 秒)」或「AT 不可用,已降级 30 秒全量」;右为距上次成功刷新的秒数

信号评级阈值与 App 一致:RSRP ≥-80 优 / ≥-90 良 / ≥-100 中 / 更差为弱。

## 已知限制与扩展方向

- 暂不支持旧版 qmodem 的 Lua HTTP 端点(App 支持);如需可参照 App `LuciClient.kt` 的 `legacyCall` 补充
- 暂无触摸交互(App 的 AT 调试、制式偏好设置建议继续用 App 操作);后续可在触摸屏上加简易开关页
- 中文字体为 U8g2 常用字集(chinese2),个别生僻运营商名可能缺字(回落显示 MCC-MNC 原码)
- 屏幕型号更换:改 `platformio.ini` 里的驱动宏与引脚即可(TFT_eSPI 支持列表内)
