#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
微雪 ESP32-S3-LCD-1.47(B) —— 全封闭桌面外壳 生成器
输出: case_base.stl(底壳) / case_lid.stl(上盖) / case_button.stl(按键帽) /
      case_preview.png(预览)

板子(来自官方尺寸图): PCB 36.37 x 20.32 mm, 四角 M2 孔, Type-C 在短边一端,
BOOT/RST 贴片按键在 Type-C 端、分列短边两侧, 1.47" 172x320 屏贴正面。

坐标: X=短边(20.32), Y=长边(36.37), Type-C 在 Y- 端, Z 向上。
"""
import os
import numpy as np
import trimesh
from trimesh.transformations import translation_matrix, rotation_matrix

HERE = os.path.dirname(os.path.abspath(__file__))

# ---------------------- 参数(mm) ----------------------
BOARD_W, BOARD_H = 20.32, 36.37      # PCB 外形
CLEAR = 0.65                          # 单边装配间隙
INNER_W = BOARD_W + 2 * CLEAR         # 21.62
INNER_H = BOARD_H + 2 * CLEAR         # 37.67
WALL = 2.20                           # 壁厚
OUT_W = INNER_W + 2 * WALL            # 26.02
OUT_H = INNER_H + 2 * WALL            # 42.07

BASE_H = 6.50                         # 底壳总高
BASE_T = 1.50                         # 底板厚
PILLAR_R = 1.55                       # PCB 支柱半径
PCB_HOLE_X, PCB_HOLE_Y = 8.89, 16.215 # PCB 四角孔中心

SCREW_X = OUT_W / 2 - 1.90            # 外壳螺丝柱中心 X
SCREW_Y = OUT_H / 2 - 1.90            # 外壳螺丝柱中心 Y
SCREW_R = 1.00                        # M2 自攻底孔

USB_W = 9.00                          # Type-C 开口
USB_Z0, USB_Z1 = 1.90, 4.60
SD_W = 12.00                          # TF 卡开口(Y+ 端)
SD_Z0, SD_Z1 = 1.90, 4.20

LID_T = 2.20                          # 上盖厚
LID_SCREW_R = 1.15                    # 上盖螺丝通孔

# 屏幕窗(圆角矩形) —— 偏 Y+ 端, 给 Type-C 端留出按键边条
WIN_W, WIN_H, WIN_R = 18.00, 30.00, 1.60
WIN_CY = 2.00                         # 屏幕窗中心 Y
# 按键孔(正面) —— Type-C 端两个贴片按键, 分列 X 两侧
BTN_X = 7.00
BTN_Y = -16.00
BTN_R = 2.25                          # Φ4.5, 覆盖按键位置公差
# 指示灯(RGB 导光孔)
LED_X, LED_Y, LED_R = 0.00, 19.00, 1.25
# 散热格栅
# 上盖 Y+ 端边框只有 ~4mm, 只能放短装饰槽
GRILL_N = 5
GRILL_LEN = 3.60
GRILL_W = 0.90
GRILL_ANGLE = np.deg2rad(28)
GRILL_Y0 = 19.00
GRILL_PITCH = 2.00
# 底壳底面大格栅(真正散热)
BGILL_N = 7
BGILL_LEN = 22.00
BGILL_W = 1.60
BGILL_ANGLE = np.deg2rad(30)
BGILL_PITCH = 2.90

# 按键帽(粘在板子贴片按键上, 穿过上盖孔伸出)
CAP_R = 2.05
CAP_H = 5.60
CAP_FLANGE_R = 2.60
CAP_FLANGE_H = 0.60


def box(cx, cy, cz, sx, sy, sz):
    return trimesh.creation.box(
        extents=(sx, sy, sz),
        transform=translation_matrix((cx, cy, cz)))


def cyl_z(cx, cy, cz, radius, height):
    return trimesh.creation.cylinder(
        radius=radius, height=height,
        transform=translation_matrix((cx, cy, cz)))


def cyl_x(cx, cy, cz, radius, height):
    c = trimesh.creation.cylinder(radius=radius, height=height)
    c.apply_transform(rotation_matrix(np.pi / 2, (0, 1, 0)))
    c.apply_transform(translation_matrix((cx, cy, cz)))
    return c


def rounded_rect(cx, cy, cz, w, h, r, depth):
    """圆角矩形刀具"""
    parts = [box(cx, cy, cz, w - 2 * r, h, depth),
             box(cx, cy, cz, w, h - 2 * r, depth)]
    for sx in (-1, 1):
        for sy in (-1, 1):
            parts.append(cyl_z(cx + sx * (w / 2 - r),
                               cy + sy * (h / 2 - r), cz, r, depth))
    out = parts[0]
    for p in parts[1:]:
        out = out.union(p)
    return out


def make_base():
    m = box(0, 0, BASE_H / 2, OUT_W, OUT_H, BASE_H)
    # 内腔(顶部开口)
    top = BASE_H + 0.4
    m = m.difference(box(0, 0, (BASE_T + top) / 2,
                         INNER_W, INNER_H, top - BASE_T))
    # PCB 支柱
    for sx in (-1, 1):
        for sy in (-1, 1):
            m = m.union(cyl_z(sx * PCB_HOLE_X, sy * PCB_HOLE_Y,
                              BASE_T + PILLAR_R / 2, PILLAR_R, PILLAR_R))
    # 四角螺丝孔
    for sx in (-1, 1):
        for sy in (-1, 1):
            m = m.difference(cyl_z(sx * SCREW_X, sy * SCREW_Y,
                                   BASE_H / 2, SCREW_R, BASE_H + 0.4))
    # Type-C 口(Y- 墙)
    m = m.difference(box(0, -OUT_H / 2 - 0.5, (USB_Z0 + USB_Z1) / 2,
                         USB_W, 2.5, USB_Z1 - USB_Z0))
    # TF 卡口(Y+ 墙)
    m = m.difference(box(0, OUT_H / 2 + 0.5, (SD_Z0 + SD_Z1) / 2,
                         SD_W, 2.5, SD_Z1 - SD_Z0))
    # 底面大散热格栅(斜槽阵列)
    for i in range(BGILL_N):
        cx = (i - (BGILL_N - 1) / 2) * BGILL_PITCH
        slot = box(0, 0, BASE_T / 2, BGILL_W, BGILL_LEN, BASE_T + 0.4)
        slot.apply_transform(rotation_matrix(-BGILL_ANGLE, (0, 0, 1)))
        slot.apply_transform(translation_matrix((cx, 0, 0)))
        m = m.difference(slot)
    return m


def make_lid():
    z0 = BASE_H
    m = box(0, 0, z0 + LID_T / 2, OUT_W, OUT_H, LID_T)
    # 屏幕窗(圆角)
    win = rounded_rect(0, WIN_CY, z0 + LID_T / 2,
                       WIN_W, WIN_H, WIN_R, LID_T + 0.4)
    m = m.difference(win)
    # 正面按键孔 x2
    for sx in (-1, 1):
        m = m.difference(cyl_z(sx * BTN_X, BTN_Y,
                               z0 + LID_T / 2, BTN_R, LID_T + 0.4))
    # 指示灯孔
    m = m.difference(cyl_z(LED_X, LED_Y,
                           z0 + LID_T / 2, LED_R, LID_T + 0.4))
    # 散热格栅(斜槽阵列)
    for i in range(GRILL_N):
        cx = (i - (GRILL_N - 1) / 2) * GRILL_PITCH
        cy = GRILL_Y0 + cx * np.tan(GRILL_ANGLE) * 0.0  # 平行斜槽
        slot = box(0, 0, z0 + LID_T / 2, GRILL_W, GRILL_LEN, LID_T + 0.4)
        slot.apply_transform(rotation_matrix(-GRILL_ANGLE, (0, 0, 1)))
        slot.apply_transform(translation_matrix((cx, cy, 0)))
        m = m.difference(slot)
    # 四角螺丝孔
    for sx in (-1, 1):
        for sy in (-1, 1):
            m = m.difference(cyl_z(sx * SCREW_X, sy * SCREW_Y,
                                   z0 + LID_T / 2, LID_SCREW_R, LID_T + 0.4))
    return m


def make_button_cap():
    """按键帽: 带法兰的圆柱, 打印 2 个"""
    m = cyl_z(0, 0, CAP_H / 2, CAP_R, CAP_H)
    m = m.union(cyl_z(0, 0, CAP_FLANGE_H / 2,
                      CAP_FLANGE_R, CAP_FLANGE_H))
    return m


def render_preview(base, lid, cap, path):
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    from matplotlib import font_manager
    from mpl_toolkits.mplot3d.art3d import Poly3DCollection

    font_manager.fontManager.addfont(
        '/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc')
    plt.rcParams['font.family'] = 'Noto Sans CJK JP'
    plt.rcParams['axes.unicode_minus'] = False

    fig = plt.figure(figsize=(15, 6.6), facecolor='white')
    views = [
        (lid,  '① 上盖正面（屏幕窗 + 按键孔 + 散热槽）', '#e8833a', 32),
        (base, '② 底壳内部（PCB 放入，四壁封闭）', '#3b82c4', 32),
        (base, '③ 底壳底面（斜条散热格栅）', '#3b82c4', -32),
    ]
    for idx, (mesh, title, color, elev) in enumerate(views):
        ax = fig.add_subplot(1, 3, idx + 1, projection='3d')
        tris = mesh.vertices[mesh.faces]
        ax.add_collection3d(Poly3DCollection(
            tris, facecolors=color, edgecolors='#22313f',
            linewidths=0.10, alpha=0.95))
        ax.set_xlim(-OUT_W / 2 - 2, OUT_W / 2 + 2)
        ax.set_ylim(-OUT_H / 2 - 2, OUT_H / 2 + 2)
        ax.set_zlim(-1, BASE_H + LID_T + 2)
        ax.set_box_aspect((OUT_W + 6, OUT_H + 6, BASE_H + LID_T + 4))
        ax.view_init(elev=elev, azim=-58)
        ax.set_title(title, fontsize=11.5, pad=4)
        ax.axis('off')
    fig.text(0.5, 0.03,
             '外形 26.0 x 42.1 x 8.7 mm  |  屏幕窗 18 x 30（圆角）  |  '
             '正面 BOOT/RST 按键孔 x2  |  RGB 导光孔  |  Type-C / TF 卡侧开口',
             ha='center', fontsize=10.5, color='#333')
    plt.subplots_adjust(bottom=0.11, wspace=0.02)
    plt.savefig(path, dpi=160, facecolor='white')
    plt.close(fig)


def main():
    base = make_base()
    lid = make_lid()
    cap = make_button_cap()

    for name, m, fn in [
        ('底壳', base, 'case_base.stl'),
        ('上盖', lid, 'case_lid.stl'),
        ('按键帽', cap, 'case_button.stl'),
    ]:
        m.export(os.path.join(HERE, fn))
        b = m.bounds
        print(f'{name}: 闭合={m.is_watertight}  '
              f'尺寸=({b[1][0]-b[0][0]:.2f} x {b[1][1]-b[0][1]:.2f} x '
              f'{b[1][2]-b[0][2]:.2f}) mm  三角面={len(m.faces)}')
    render_preview(base, lid, cap, os.path.join(HERE, 'case_preview.png'))


if __name__ == '__main__':
    main()
