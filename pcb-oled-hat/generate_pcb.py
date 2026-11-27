#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
QModem OLED HAT — ESP32-S3 SuperMini 专用 0.96" IIC OLED 扩展板
生成标准 RS-274X Gerber + Excellon 钻孔 + 预览图 + 按键帽 STL。

板型(以用户实物为准,板面丝印 "ESP32-S3 Super Mini"):
  外形 22.52 x 18.00 mm,双排各 9 孔,孔距 2.54 mm,排距 15.24 mm
  上排(Y=-7.62,左->右): 5V GND 3V3 13 12 11 10 9 8
  下排(Y=+7.62,左->右): TX RX 1 2 3 4 5 6 7
  注: 数字即 GPIO 号(IO1~IO13),TX/RX 为 USB 转串口占用,避免占用

扩展板设计(屏幕立式,只引出一个配网键):
  - 覆盖底板的 22.52x18 主体 + Y-端延伸 4mm 作为 OLED 立式焊盘区
  - OLED 用 90° 弯排针竖立在板边,显示面朝板外(水平方向可见),
    屏幕不再平铺在正面,按键区完全露出不被遮挡
  - 复位靠断电(拔电/断电重启)实现,不设 RESET 按键
  - BOOT(GPIO0) 未引到排针,故开 1 个 Φ3.4 孔对准板上贴片 BOOT 按键,
    附按键帽 STL 粘在贴片按键上穿出孔,单击即进 WiFi 配网
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
BW, BH = 22.52, 26.00          # 扩展板宽(X,对底板)×高(Y,底部多8mm放OLED焊盘区)
ROWS, PITCH = 9, 2.54
HY = 7.62                      # 排针行距的一半(15.24/2)
X0 = (ROWS - 1) * PITCH / 2    # 10.16,最左/最右孔 X
XS = [round(-X0 + i * PITCH, 2) for i in range(ROWS)]

TOP = ['5V','GND','3V3','13','12','11','10','9','8']   # Y=-7.62
BOT = ['TX','RX','1','2','3','4','5','6','7']          # Y=+7.62
headers = [(x, -HY, TOP[i]) for i, x in enumerate(XS)] + \
          [(x, +HY, BOT[i]) for i, x in enumerate(XS)]

# 板上贴片按键中心(由尺寸网格图实测,板中心为原点)
# 只引 BOOT 一个键作配网用;RESET 复位靠断电实现,不设按键孔
BTN = [(-3.90, +4.20, 'CFG')]
BTN_R = 1.70                   # Φ3.4 按键孔

# OLED 4 焊盘(顶部延伸区,2.54 间距),用 90° 弯排针立式安装,显示面朝板外
oled = [(-3.81, -9.80, 'GND'), (-1.27, -9.80, '3V3'),
        (1.27, -9.80, 'SCL'), (3.81, -9.80, 'SDA')]
PAD_R, HOLE_R = 0.85, 0.50     # 焊盘半径 / 孔半径

# 底层走线:GND/3V3/SCL/SDA 从排针引到 OLED,均绕开按键孔
routes = [
    ('GND', [(-7.62, -7.62), (-7.62, -9.00), (-3.81, -9.00), (-3.81, -9.80)]),
    ('3V3', [(-5.08, -7.62), (-5.08, -8.60), (-1.27, -8.60), (-1.27, -9.80)]),
    ('SCL', [(7.62, -7.62), (7.62, -9.40), (1.27, -9.40), (1.27, -9.80)]),
    ('SDA', [(10.16, -7.62), (10.16, -10.20), (3.81, -10.20), (3.81, -9.80)]),
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

b_cu = header([(10, PAD_R*2), (11, 0.30)])
for x, y in all_pads:
    b_cu += flash(x, y, 10)
for name, pts in routes:
    for a, b in zip(pts, pts[1:]):
        b_cu += segment(a[0], a[1], b[0], b[1], 11)
write('QModem_OLED_HAT-B_Cu.gbr', b_cu)

# ---------- F.Mask ----------
mask = header([(12, PAD_R*2 + 0.20)])
for x, y in all_pads:
    mask += flash(x, y, 12)
write('QModem_OLED_HAT-F_Mask.gbr', mask)

# ---------- F.SilkS ----------
silk = header([(13, 0.15)])
for a, b in zip(outline, outline[1:]):
    silk += segment(a[0], a[1], b[0], b[1], 13)
# OLED 焊盘标注
for label, (px, py, name) in zip(['GND','3V3','SCL','SDA'], oled):
    for (x1, y1), (x2, y2) in text_segs(label, px - 1.1, py - 2.2, size=0.62):
        silk += segment(x1, y1, x2, y2, 13)
# 按键孔标注:用 name(配网键 CFG)
for (bx, by, name) in BTN:
    for (x1, y1), (x2, y2) in text_segs(name, bx - 1.3, by + 2.3, size=0.58):
        silk += segment(x1, y1, x2, y2, 13)
# 排针丝印(上排/下排,沿板外方向排布)
for x, y, n in headers:
    ny = y - 1.35 if y < 0 else y + 0.55
    for (x1, y1), (x2, y2) in text_segs(n, x - 0.9, ny, size=0.50):
        silk += segment(x1, y1, x2, y2, 13)
for (x1, y1), (x2, y2) in text_segs('QM HAT S3', -4.2, 10.2, size=0.55, adv=0.9):
    silk += segment(x1, y1, x2, y2, 13)
write('QModem_OLED_HAT-F_SilkS.gbr', silk)

# ---------- Edge.Cuts ----------
edge = header([(14, 0.10)])
for a, b in zip(outline, outline[1:]):
    edge += segment(a[0], a[1], b[0], b[1], 14)
write('QModem_OLED_HAT-Edge_Cuts.gbr', edge)

# ---------- Excellon 钻孔(两种孔径) ----------
drl = ('M48\nMETRIC\n%%FSLAX33Y33*%%\n'
       'T01C%.3f\nT02C%.3f\n%%\n' % (HOLE_R*2, BTN_R*2))
drl += 'T01\n'
for x, y in all_pads:
    drl += 'X%dY%d\n' % (int(round(x*1000)), int(round(y*1000)))
drl += 'T02\n'
for x, y, _ in BTN:
    drl += 'X%dY%d\n' % (int(round(x*1000)), int(round(y*1000)))
drl += 'M30\n'
write('QModem_OLED_HAT.drl', drl)

# ---------- 按键帽 STL ----------
try:
    import trimesh
    cap = trimesh.creation.cylinder(radius=1.60, height=4.50, sections=32)
    flange = trimesh.creation.cylinder(radius=2.00, height=0.60, sections=32)
    flange.apply_translation([0, 0, -1.95])
    cap = trimesh.util.concatenate([flange, cap])
    cap.export(os.path.join(HERE, 'button_cap.stl'))
    print('button_cap.stl 已生成')
except Exception as e:
    print('按键帽生成跳过:', e)

# ---------- 预览图:顶层布线 ----------
fig, ax = plt.subplots(figsize=(7.6, 9))
poly = outline + [outline[0]]
ax.plot([p[0] for p in poly], [p[1] for p in poly], color='#222', lw=2)
# 底板轮廓(虚线,示意覆盖区)
ax.add_patch(Rectangle((-BW/2, -9), BW, 18, fill=False, edgecolor='#999',
                       lw=1.2, ls=':', label='底板 ESP32-S3 SuperMini'))
colors = {'GND':'#888', '3V3':'#e08a1e', 'SCL':'#2a9d8f', 'SDA':'#457b9d'}
for name, pts in routes:
    ax.plot([p[0] for p in pts], [p[1] for p in pts], color=colors[name], lw=2.0,
            ls='--', alpha=0.9, label=name)
# 排针孔
for x, y, n in headers:
    ax.add_patch(Circle((x, y), PAD_R, facecolor='#f5c542', edgecolor='#7a5c00', lw=1))
    ax.add_patch(Circle((x, y), HOLE_R, facecolor='white', edgecolor='#7a5c00', lw=0.8))
    ha = 'left' if x < 0 else 'right'
    dx = 1.0 if x < 0 else -1.0
    ax.text(x + dx, y - 1.25 if y < 0 else y + 1.05, n,
            fontsize=6.0, ha=ha, va='center', color='#333')
# 按键孔(红色醒目)
for x, y, n in BTN:
    ax.add_patch(Circle((x, y), BTN_R, facecolor='#ff6b6b', edgecolor='#a00000', lw=1.2, alpha=0.8))
    ax.add_patch(Circle((x, y), BTN_R - 0.25, facecolor='white', edgecolor='#a00000', lw=0.8))
    ax.text(x + 2.0, y, n, fontsize=7.0, ha='left', va='center', color='#a00000', fontweight='bold')
# OLED 焊盘
for x, y, n in oled:
    ax.add_patch(Circle((x, y), PAD_R, facecolor='#e63946', edgecolor='#7a0010', lw=1))
    ax.add_patch(Circle((x, y), HOLE_R, facecolor='white', edgecolor='#7a0010', lw=0.8))
    ax.text(x, y - 1.25, n, fontsize=7.5, ha='center', color='#b00020', fontweight='bold')
ax.set_title('QModem OLED HAT — S3 SuperMini 顶层 / 底层走线（虚线，OLED 立式焊盘在顶部）', fontsize=11, pad=10)
ax.text(0, 11.6, 'QM HAT S3', fontsize=8, ha='center', color='#555')
ax.set_xlim(-13, 13); ax.set_ylim(-13, 13.5)
ax.set_aspect('equal'); ax.axis('off')
ax.legend(loc='lower center', ncol=3, fontsize=7, frameon=False, bbox_to_anchor=(0.5, -0.01))
plt.tight_layout()
plt.savefig(os.path.join(HERE, 'preview_top.png'), dpi=170, facecolor='white')
plt.close()

# ---------- 预览图:装配侧视(屏幕立式,显示面朝板外,按键朝上) ----------
fig, ax = plt.subplots(figsize=(10.5, 6.5))
# 底板 S3 SuperMini
ax.add_patch(Rectangle((3, 0), 19, 1.1, facecolor='#2f4f4f', edgecolor='#222', lw=1))
ax.text(12.5, 0.55, 'S3 SuperMini 22.52×18mm（按键在正面）', fontsize=7.8,
        ha='center', va='center', color='white')
# 排针(两处)
for px in [9.5, 17.5]:
    ax.add_patch(Rectangle((px, 1.1), 0.7, 2.44, facecolor='#c9a227', edgecolor='#7a5c00'))
# 扩展板 HAT
ax.add_patch(Rectangle((3, 3.54), 19, 1.0, facecolor='#3b82c4', edgecolor='#222', lw=1))
ax.text(12.5, 4.04, 'OLED HAT（本扩展板）', fontsize=8, ha='center', va='center', color='white')
# 配网键帽:从底板 BOOT 贴片按键穿出扩展板孔,朝上露出(复位靠断电,不设 RESET 键)
px = 13.0
ax.add_patch(Rectangle((px, 1.1), 1.1, 3.9, facecolor='#ff6b6b', edgecolor='#a00000'))
ax.add_patch(Rectangle((px - 0.15, 5.0), 1.4, 0.55, facecolor='#ff9b9b', edgecolor='#a00000'))
ax.annotate('', xy=(px + 0.4, 7.1), xytext=(px + 0.4, 5.7),
            arrowprops=dict(arrowstyle='-|>', color='#a00000', lw=1.4))
ax.text(px + 0.4, 7.3, '配网键 单击', fontsize=7.4, ha='center', color='#a00000')
# OLED 立式屏:90°弯排针焊在板左端焊盘,屏体竖立,显示面朝左(板外/前方)
ax.add_patch(Rectangle((3, 4.54), 1.6, 15.5, facecolor='#1a1a1a', edgecolor='#000', lw=1))
ax.add_patch(Rectangle((3, 6.4), 1.6, 11.7, facecolor='#103a5c', edgecolor='#0a2740'))  # 有效显示区
ax.annotate('', xy=(0.6, 12.3), xytext=(2.7, 12.3),
            arrowprops=dict(arrowstyle='->', color='#c0392b', lw=1.8))
ax.text(0.3, 13.4, '显示面朝前', fontsize=8, ha='center', va='bottom', color='#c0392b')
ax.text(0.3, 10.9, '0.96" OLED 立式', fontsize=7.6, ha='center', va='top', color='#c0392b')
ax.text(2.9, -0.2, '90°弯排针', fontsize=7, ha='left', va='top', color='#7a5c00')
# 说明
ax.text(12.5, -1.3, '屏幕立式朝前显示；配网键单击进配网；复位直接断电，无需 RESET 键',
        fontsize=8.6, ha='center', color='#333')
ax.set_xlim(-9, 26); ax.set_ylim(-2.0, 21.5)
ax.set_aspect('equal'); ax.axis('off')
ax.set_title('装配侧视 — 屏幕立式 / 单个配网键', fontsize=11, pad=8)
plt.tight_layout()
plt.savefig(os.path.join(HERE, 'preview_assembly.png'), dpi=170, facecolor='white')
plt.close()

print('生成完成:')
for f in sorted(os.listdir(OUT)):
    print('  generated/' + f)
print('  preview_top.png, preview_assembly.png, button_cap.stl')
