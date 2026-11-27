#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
QModem OLED HAT — ESP32-S3 SuperMini 专用 0.96" IIC OLED 扩展板
生成标准 RS-274X Gerber + Excellon 钻孔 + 预览图。

板型(以用户实物为准,板面丝印 "ESP32-S3 Super Mini"):
  外形 22.52 x 18.00 mm,双排各 9 孔,孔距 2.54 mm,排距 15.24 mm
  上排(Y=-7.62,左->右): 5V GND 3V3 13 12 11 10 9 8
  下排(Y=+7.62,左->右): TX RX 1 2 3 4 5 6 7

扩展板设计(屏幕平叠 + 加长板尾引出配网键):
  - 板子加长到 22.52 x 42 mm:板顶放 OLED 焊盘,板尾加长段放配网键
  - 屏幕平叠:OLED 用直排针焊在板顶焊盘,屏体平躺在扩展板正面上方,
    显示面朝上;屏体覆盖 Y=-19~+8,不碰到板尾的配网键
  - 配网键:板尾(Y=+15)焊一颗 6x6 轻触开关 K1,接 IO4(排针已引出)+GND,
    单击进 WiFi 配网;复位靠断电(拔电/断电重启)实现,不设 RESET 按键
  - 底板 BOOT(GPIO0)/RESET(EN) 未引到排针,故不使用底板按键
  - OLED 接线: VCC<-3V3, GND<-GND, SCL<-IO9, SDA<-IO8 (与固件 s3mini_oled_096 一致)
"""
import os
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.patches import Circle, Rectangle
from matplotlib import font_manager

# 加载中文字体(预览图用)
for fp in ['/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc',
           '/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc']:
    if os.path.exists(fp):
        font_manager.fontManager.addfont(fp)
        plt.rcParams['font.family'] = font_manager.FontProperties(fname=fp).get_name()
        break

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, 'generated')
os.makedirs(OUT, exist_ok=True)

# ---------- 几何参数(mm) ----------
BW, BH = 22.52, 42.00         # 扩展板宽(X,对底板)×高(Y,加长到42:板顶放屏/板尾放键)
ROWS, PITCH = 9, 2.54
HY = 7.62                      # 排针行距的一半(15.24/2)
X0 = (ROWS - 1) * PITCH / 2    # 10.16,最左/最右孔 X
XS = [round(-X0 + i * PITCH, 2) for i in range(ROWS)]

TOP = ['5V','GND','3V3','13','12','11','10','9','8']   # Y=-7.62
BOT = ['TX','RX','1','2','3','4','5','6','7']          # Y=+7.62
headers = [(x, -HY, TOP[i]) for i, x in enumerate(XS)] + \
          [(x, +HY, BOT[i]) for i, x in enumerate(XS)]

# OLED 4 焊盘(板顶 Y=-18,2.54 间距),直排针把屏平叠在板上,显示面朝上
oled = [(-3.81, -18.00, 'GND'), (-1.27, -18.00, '3V3'),
        (1.27, -18.00, 'SCL'), (3.81, -18.00, 'SDA')]
PAD_R, HOLE_R = 0.85, 0.50     # 焊盘半径 / 孔半径
# 屏体平叠覆盖区(示意,27x27):Y=-19~+8,比 X 板宽略宽(屏幕模块本来就比板宽)
SCREEN_BOX = (-13.5, -19.0, 27.0, 27.0)

# 配网键 K1:板尾 Y=+15 一颗 6x6 轻触开关 4 焊盘(同侧两脚为同一电极)
#   下侧两脚(GND) / 上侧两脚(SIG->IO4);按下时两组接通
K1 = [(-2.0, 13.8, 'GND'), (2.0, 13.8, 'GND'),
      (-2.0, 16.2, 'SIG'), (2.0, 16.2, 'SIG')]
K1_PAD_R = 0.95                 # 开关焊盘半径
IO4_PIN = (2.54, 7.62)          # 下排 IO4(配网键信号,排针已引出)
GND_PIN = (-7.62, -7.62)        # 上排 GND

# 底层走线:GND/3V3/SCL/SDA(OLED) + K1(SIG/GND),均绕开排针孔
routes = [
    ('GND', [(-7.62, -7.62), (-3.81, -18.00)]),
    ('3V3', [(-5.08, -7.62), (-1.27, -18.00)]),
    ('SCL', [(7.62, -7.62), (1.27, -18.00)]),
    ('SDA', [(10.16, -7.62), (3.81, -18.00)]),
    ('GND', [(-2.0, 13.8), (2.0, 13.8)]),                                # K1 下侧两脚同电极
    ('GND', [(-7.62, -7.62), (-9.0, -7.62), (-9.0, 13.8), (-2.0, 13.8)]),  # GND 沿左边缘到板尾
    ('SIG', [(-2.0, 16.2), (2.0, 16.2)]),                                # K1 上侧两脚同电极
    ('SIG', [(2.54, 7.62), (2.0, 7.62), (2.0, 16.2)]),                    # IO4 到 K1
]

# ---------- Gerber 基础(FSLAX46Y46,单位 mm) ----------
def gc(x, y):
    return 'X%dY%d' % (int(round(x * 1e6)), int(round(y * 1e6)))

def header(apertures):
    s = '%FSLAX46Y46*%\n%MOMM*%\n'
    for code, dia in apertures:
        s += '%%ADD%dC,%.3f*%%\n' % (code, dia)
    s += 'G01*\n'
    return s

def flash(x, y, ap):
    return 'D%d*\n%sD03*\n' % (ap, gc(x, y))

def segment(x1, y1, x2, y2, ap):
    return 'D%d*\n%sD02*\n%sD01*\n' % (ap, gc(x1, y1), gc(x2, y2))

def write(name, content):
    if name.endswith('.gbr'):
        content = content.rstrip('\n') + '\nM02*\n'   # Gerber 标准结束符
    with open(os.path.join(OUT, name), 'w', encoding='utf-8') as f:
        f.write(content)

# 圆角矩形板框(倒角 0.8mm)
c = 0.8
outline = [(-BW/2+c, -BH/2), (BW/2-c, -BH/2), (BW/2, -BH/2+c),
           (BW/2, BH/2-c), (BW/2-c, BH/2), (-BW/2+c, BH/2),
           (-BW/2, BH/2-c), (-BW/2, -BH/2+c), (-BW/2+c, -BH/2)]

# 简易矢量字库(线段),供丝印标注
FONT = {
 'G':[((.65,.85),(.15,.9)),((.15,.9),(.05,.7)),((.05,.7),(.05,.3)),((.05,.3),(.15,.1)),((.15,.1),(.65,.1)),((.65,.1),(.65,.5)),((.65,.5),(.38,.5))],
 'N':[((.05,.1),(.05,.9)),((.05,.9),(.65,.1)),((.65,.1),(.65,.9))],
 'D':[((.05,.1),(.05,.9)),((.05,.9),(.38,.9)),((.38,.9),(.65,.7)),((.65,.7),(.65,.3)),((.65,.3),(.38,.1)),((.38,.1),(.05,.1))],
 'V':[((.05,.9),(.35,.1)),((.35,.1),(.65,.9))],
 'C':[((.65,.8),(.38,.9)),((.38,.9),(.12,.7)),((.12,.7),(.12,.3)),((.12,.3),(.38,.1)),((.38,.1),(.65,.2))],
 'S':[((.65,.8),(.4,.9)),((.4,.9),(.15,.75)),((.15,.75),(.2,.55)),((.2,.55),(.5,.5)),((.5,.5),(.6,.3)),((.6,.3),(.4,.1)),((.4,.1),(.1,.2))],
 'L':[((.05,.9),(.05,.1)),((.05,.1),(.6,.1))],
 'A':[((.05,.1),(.35,.9)),((.35,.9),(.65,.1)),((.18,.38),(.52,.38))],
 'F':[((.05,.9),(.05,.1)),((.05,.9),(.6,.9)),((.05,.5),(.5,.5))],
 '3':[((.1,.85),(.55,.85)),((.55,.85),(.6,.55)),((.6,.55),(.3,.5)),((.3,.5),(.6,.45)),((.6,.45),(.55,.15)),((.55,.15),(.1,.15))],
 'Q':[((.35,.9),(.6,.75)),((.6,.75),(.6,.35)),((.6,.35),(.35,.1)),((.35,.1),(.1,.35)),((.1,.35),(.1,.75)),((.1,.75),(.35,.9)),((.45,.3),(.68,.05))],
 'M':[((.05,.1),(.05,.9)),((.05,.9),(.35,.5)),((.35,.5),(.65,.9)),((.65,.9),(.65,.1))],
 'O':[((.35,.9),(.6,.75)),((.6,.75),(.6,.3)),((.6,.3),(.35,.1)),((.35,.1),(.1,.3)),((.1,.3),(.1,.75)),((.1,.75),(.35,.9))],
 'E':[((.6,.9),(.05,.9)),((.05,.9),(.05,.1)),((.05,.1),(.6,.1)),((.05,.5),(.5,.5))],
 'H':[((.05,.1),(.05,.9)),((.65,.1),(.65,.9)),((.05,.5),(.65,.5))],
 'T':[((.05,.9),(.65,.9)),((.35,.9),(.35,.1))],
 'R':[((.05,.1),(.05,.9)),((.05,.9),(.4,.9)),((.4,.9),(.65,.7)),((.65,.7),(.4,.5)),((.4,.5),(.05,.5)),((.35,.5),(.65,.1))],
 'B':[((.05,.1),(.05,.9)),((.05,.9),(.4,.9)),((.4,.9),(.65,.75)),((.65,.75),(.6,.6)),((.6,.6),(.4,.5)),((.4,.5),(.65,.38)),((.65,.38),(.6,.22)),((.6,.22),(.4,.1)),((.4,.1),(.05,.1))],
 '1':[((.2,.7),(.35,.9)),((.35,.9),(.35,.1)),((.15,.1),(.55,.1))],
 '2':[((.1,.75),(.25,.9)),((.25,.9),(.55,.9)),((.55,.9),(.6,.7)),((.6,.7),(.1,.1)),((.1,.1),(.6,.1))],
 '0':[((.35,.9),(.6,.75)),((.6,.75),(.6,.3)),((.6,.3),(.35,.1)),((.35,.1),(.1,.3)),((.1,.3),(.1,.75)),((.1,.75),(.35,.9))],
}

def text_segs(text, x, y, size=0.8, adv=0.92):
    segs = []
    cx = x
    for ch in text.upper():
        for (x1, y1), (x2, y2) in FONT.get(ch, []):
            segs.append(((cx + x1*size, y + y1*size), (cx + x2*size, y + y2*size)))
        cx += adv * size
    return segs

# ---------- F.Cu / B.Cu ----------
f_cu = header([(10, PAD_R*2)])
all_pads = [(x, y) for x, y, _ in headers] + [(x, y) for x, y, _ in oled]
for x, y in all_pads:
    f_cu += flash(x, y, 10)
write('QModem_OLED_HAT-F_Cu.gbr', f_cu)

b_cu = header([(10, PAD_R*2), (11, 0.30), (15, K1_PAD_R*2)])
for x, y in all_pads:
    b_cu += flash(x, y, 10)
for x, y, _ in K1:
    b_cu += flash(x, y, 15)
for name, pts in routes:
    for a, b in zip(pts, pts[1:]):
        b_cu += segment(a[0], a[1], b[0], b[1], 11)
write('QModem_OLED_HAT-B_Cu.gbr', b_cu)

# ---------- F.Mask ----------
mask = header([(12, PAD_R*2 + 0.20), (16, K1_PAD_R*2 + 0.20)])
for x, y in all_pads:
    mask += flash(x, y, 12)
for x, y, _ in K1:
    mask += flash(x, y, 16)
write('QModem_OLED_HAT-F_Mask.gbr', mask)

# ---------- F.SilkS ----------
silk = header([(13, 0.15)])
for a, b in zip(outline, outline[1:]):
    silk += segment(a[0], a[1], b[0], b[1], 13)
# OLED 焊盘标注
for label, (px, py, name) in zip(['GND','3V3','SCL','SDA'], oled):
    for (x1, y1), (x2, y2) in text_segs(label, px - 1.1, py - 2.2, size=0.62):
        silk += segment(x1, y1, x2, y2, 13)
# 配网键标注 CFG(放在 K1 上方)
for (x1, y1), (x2, y2) in text_segs('CFG', -1.6, 18.0, size=0.72):
    silk += segment(x1, y1, x2, y2, 13)
# 排针丝印(上排/下排,沿板外方向排布)
for x, y, n in headers:
    ny = y - 1.35 if y < 0 else y + 0.55
    for (x1, y1), (x2, y2) in text_segs(n, x - 0.9, ny, size=0.50):
        silk += segment(x1, y1, x2, y2, 13)
for (x1, y1), (x2, y2) in text_segs('QM HAT S3', -4.2, 19.5, size=0.55, adv=0.9):
    silk += segment(x1, y1, x2, y2, 13)
write('QModem_OLED_HAT-F_SilkS.gbr', silk)

# ---------- Edge.Cuts ----------
edge = header([(14, 0.10)])
for a, b in zip(outline, outline[1:]):
    edge += segment(a[0], a[1], b[0], b[1], 14)
write('QModem_OLED_HAT-Edge_Cuts.gbr', edge)

# ---------- Excellon 钻孔(单一孔径:排针18+OLED4+K1焊盘4=26) ----------
drl = ('M48\nMETRIC\n%%FSLAX33Y33*%%\n'
       'T01C%.3f\n%%\n' % (HOLE_R*2))
drl += 'T01\n'
for x, y in all_pads:
    drl += 'X%dY%d\n' % (int(round(x*1000)), int(round(y*1000)))
for x, y, _ in K1:
    drl += 'X%dY%d\n' % (int(round(x*1000)), int(round(y*1000)))
drl += 'M30\n'
write('QModem_OLED_HAT.drl', drl)

# ---------- 预览图:顶层布线 ----------
fig, ax = plt.subplots(figsize=(8.0, 10.5))
poly = outline + [outline[0]]
ax.plot([p[0] for p in poly], [p[1] for p in poly], color='#222', lw=2)
# 底板轮廓(虚线,示意覆盖区)
ax.add_patch(Rectangle((-BW/2, -9), BW, 18, fill=False, edgecolor='#999',
                       lw=1.2, ls=':', label='底板 ESP32-S3 SuperMini'))
# 屏幕平叠覆盖区(虚线框,平躺在板正面上方)
sx, sy, sw, sh = SCREEN_BOX
ax.add_patch(Rectangle((sx, sy), sw, sh, fill=False,
                       edgecolor='#c0392b', lw=1.6, ls='--'))
ax.text(0, -4.0, '0.96" OLED 平叠在板上（显示面朝上）',
        fontsize=9, ha='center', color='#c0392b', fontweight='bold')
colors = {'GND':'#888', '3V3':'#e08a1e', 'SCL':'#2a9d8f', 'SDA':'#457b9d', 'SIG':'#c0392b'}
for name, pts in routes:
    ax.plot([p[0] for p in pts], [p[1] for p in pts], color=colors[name], lw=1.9,
            ls='--', alpha=0.9, label=name if name not in ('GND','SIG') else None)
# 排针孔
for x, y, n in headers:
    ax.add_patch(Circle((x, y), PAD_R, facecolor='#f5c542', edgecolor='#7a5c00', lw=1))
    ax.add_patch(Circle((x, y), HOLE_R, facecolor='white', edgecolor='#7a5c00', lw=0.8))
    ha = 'left' if x < 0 else 'right'
    dx = 1.0 if x < 0 else -1.0
    ax.text(x + dx, y - 1.25 if y < 0 else y + 1.05, n,
            fontsize=6.0, ha=ha, va='center', color='#333')
# OLED 焊盘
for x, y, n in oled:
    ax.add_patch(Circle((x, y), PAD_R, facecolor='#e63946', edgecolor='#7a0010', lw=1))
    ax.add_patch(Circle((x, y), HOLE_R, facecolor='white', edgecolor='#7a0010', lw=0.8))
    ax.text(x, y - 1.25, n, fontsize=7.5, ha='center', color='#b00020', fontweight='bold')
# 配网键 K1 焊盘(方块)
for x, y, n in K1:
    ax.add_patch(Rectangle((x - K1_PAD_R, y - K1_PAD_R), K1_PAD_R*2, K1_PAD_R*2,
                           facecolor='#ff9b9b', edgecolor='#a00000', lw=1.2))
    ax.add_patch(Circle((x, y), HOLE_R, facecolor='white', edgecolor='#a00000', lw=0.8))
    ax.text(x, y, n, fontsize=6.2, ha='center', va='center', color='#a00000', fontweight='bold')
ax.text(0, 18.2, 'K1 配网键（板尾，屏幕盖不到）', fontsize=9, ha='center', color='#a00000', fontweight='bold')
ax.set_title('QModem OLED HAT — 顶层 / 底层走线（虚线；屏幕平叠、按键在板尾）', fontsize=11, pad=10)
ax.set_xlim(-15, 15); ax.set_ylim(-23, 22)
ax.set_aspect('equal'); ax.axis('off')
ax.legend(loc='lower center', ncol=4, fontsize=7, frameon=False, bbox_to_anchor=(0.5, -0.01))
plt.tight_layout()
plt.savefig(os.path.join(HERE, 'preview_top.png'), dpi=170, facecolor='white')
plt.close()

# ---------- 预览图:装配侧视(横轴=板长方向:左板顶/右板尾) ----------
fig, ax = plt.subplots(figsize=(11.5, 5.8))
# 横轴:板 Y -21~+21 映射到图上 0~42(1mm=1单位)
# 底板 S3 SuperMini:覆盖 Y=-9~+9 => x 12~30
ax.add_patch(Rectangle((12, 0), 18, 1.1, facecolor='#2f4f4f', edgecolor='#222', lw=1))
ax.text(21, 0.55, 'S3 SuperMini 22.52×18mm', fontsize=7.8,
        ha='center', va='center', color='white')
# 排针(两处,对应排针行)
for px in [12, 30]:
    ax.add_patch(Rectangle((px - 0.35, 1.1), 0.7, 2.44, facecolor='#c9a227', edgecolor='#7a5c00'))
# 扩展板 HAT:板 Y -21~+21 => x 0~42
ax.add_patch(Rectangle((0, 3.54), 42, 1.0, facecolor='#3b82c4', edgecolor='#222', lw=1))
ax.text(21, 4.04, 'OLED HAT（本扩展板，已加长）', fontsize=8, ha='center', va='center', color='white')
# OLED 直排针(板顶,图上 x≈3)
ax.add_patch(Rectangle((2.6, 4.54), 0.7, 1.4, facecolor='#c9a227', edgecolor='#7a5c00'))
# 屏幕平躺叠在板上:覆盖 Y=-19~+8 => x 2~29, z=5.94~7.14
ax.add_patch(Rectangle((2, 5.94), 27, 1.2, facecolor='#1a1a1a', edgecolor='#000', lw=1))
ax.add_patch(Rectangle((2, 6.14), 27, 0.82, facecolor='#103a5c', edgecolor='#0a2740'))
ax.annotate('', xy=(15, 8.6), xytext=(15, 7.3),
            arrowprops=dict(arrowstyle='-|>', color='#c0392b', lw=1.6))
ax.text(15, 8.9, '显示面朝上（平叠在板上）', fontsize=8.2, ha='center', color='#c0392b')
# 配网键 K1:板尾 Y=+15 => x≈36,焊在扩展板正面,向下按
bx = 36
ax.add_patch(Rectangle((bx - 1.6, 4.54), 3.2, 1.1, facecolor='#ff6b6b', edgecolor='#a00000'))
ax.text(bx, 5.1, 'K1', fontsize=7.6, ha='center', va='center', color='white', fontweight='bold')
ax.annotate('', xy=(bx, 6.9), xytext=(bx, 5.7),
            arrowprops=dict(arrowstyle='-|>', color='#a00000', lw=1.6))
ax.text(bx, 7.2, '配网键 单击', fontsize=7.6, ha='center', color='#a00000')
ax.text(bx, 3.0, '板尾加长段\n（屏幕盖不到）', fontsize=7.2, ha='center', va='top', color='#555')
# 说明
ax.text(21, -1.4, '屏幕平叠在板上（显示面朝上）· 板尾加长引出 K1 配网键 · 复位直接断电',
        fontsize=8.8, ha='center', color='#333')
ax.set_xlim(-4, 46); ax.set_ylim(-2.2, 10.2)
ax.set_aspect('equal'); ax.axis('off')
ax.set_title('装配侧视 — 屏幕平叠 / 板尾引出配网键', fontsize=11, pad=8)
plt.tight_layout()
plt.savefig(os.path.join(HERE, 'preview_assembly.png'), dpi=170, facecolor='white')
plt.close()

print('生成完成:')
for f in sorted(os.listdir(OUT)):
    print('  generated/' + f)
print('  preview_top.png, preview_assembly.png')
