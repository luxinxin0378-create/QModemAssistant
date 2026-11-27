#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
微雪 ESP32-S3-LCD-1.47(B) 开发板 —— 3D 打印外壳生成器
输出: case_base.stl(底壳) / case_lid.stl(上盖) / case_preview.png(预览)

板子实测尺寸(来自用户提供的官方产品尺寸图):
  PCB 外形    : 36.37 x 20.32 mm (竖放: 宽 20.32, 高 36.37)
  M2 安装孔   : 横向孔距 32.43, 纵向孔距 17.78, 孔中心距长边 1.97, 距短边 1.27
  Type-C      : 位于短边一侧, 居中
  1.47" 屏幕  : 172x320, 有效显示区约 17.70 x 32.90 mm

设计说明:
  底壳内腔比 PCB 每边大 0.6mm, 底部 4 根支柱把 PCB 垫高 1.5mm,
  给背面元件(Type-C 金属壳/焊点)留出空间; 屏幕正面朝上, 上盖开窗。
  上下盖用 4 颗 M2 螺丝固定(Hole 已预钻, 可用自攻螺丝)。
"""
import os
import numpy as np
import trimesh
from trimesh.transformations import translation_matrix, rotation_matrix

HERE = os.path.dirname(os.path.abspath(__file__))

# ---------------------- 参数(mm) ----------------------
BOARD_W, BOARD_H = 20.32, 36.37      # PCB 外形(竖放)
SCREEN_W, SCREEN_H = 17.70, 32.90    # 屏幕有效显示区
CLEAR = 0.60                          # PCB 到内壁的单边间隙
INNER_W = BOARD_W + 2 * CLEAR         # 21.52
INNER_H = BOARD_H + 2 * CLEAR         # 37.57
WALL = 2.40                           # 外壳壁厚
OUT_W = INNER_W + 2 * WALL            # 26.32
OUT_H = INNER_H + 2 * WALL            # 42.37

BASE_H = 7.00                         # 底壳总高
BASE_T = 1.50                         # 底板厚
PILLAR_H = 1.50                       # 支柱高(PCB 背面元件空间)
PILLAR_R = 1.60                       # 支柱半径
PCB_HOLE_X, PCB_HOLE_Y = 8.89, 16.215 # PCB 四角孔中心(竖放坐标)

SCREW_X = OUT_W / 2 - 1.80            # 外壳螺丝柱中心 X = 11.36(离边 1.8mm, 留足壁)
SCREW_Y = OUT_H / 2 - 1.80            # 外壳螺丝柱中心 Y = 19.385
SCREW_R = 1.05                        # M2 底孔(自攻)

USB_W = 9.00                          # Type-C 开口宽
USB_Z0, USB_Z1 = 1.90, 5.00           # Type-C 开口高度范围
SD_W = 12.00                          # TF 卡座开口宽
SD_Z0, SD_Z1 = 1.90, 4.20
BTN_R = 1.50                          # BOOT/RST 侧面按键孔半径
BTN_Y = -12.00                        # 按键孔沿 Y 位置(靠近 Type-C 端)
BTN_Z = 3.60

LID_T = 2.00                          # 上盖厚
WIN_W = SCREEN_W + 0.40               # 屏幕开窗(每边留 0.2mm)
WIN_H = SCREEN_H + 0.40
LID_SCREW_R = 1.15                    # 上盖螺丝通孔(M2 穿入)


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


def make_base():
    # 外轮廓
    m = box(0, 0, BASE_H / 2, OUT_W, OUT_H, BASE_H)
    # 内腔(顶部开口)
    cavity_top = BASE_H + 0.4
    m = m.difference(box(0, 0, (BASE_T + cavity_top) / 2,
                         INNER_W, INNER_H, cavity_top - BASE_T))
    # PCB 支柱(4 根, 位于 PCB 安装孔处)
    for sx in (-1, 1):
        for sy in (-1, 1):
            m = m.union(cyl_z(sx * PCB_HOLE_X, sy * PCB_HOLE_Y,
                              BASE_T + PILLAR_H / 2, PILLAR_R, PILLAR_H))
    # 四角 M2 螺丝孔(贯穿底壳)
    for sx in (-1, 1):
        for sy in (-1, 1):
            m = m.difference(cyl_z(sx * SCREW_X, sy * SCREW_Y,
                                   BASE_H / 2, SCREW_R, BASE_H + 0.4))
    # Type-C 开口(Y- 侧墙)
    m = m.difference(box(0, -OUT_H / 2 - 0.5, (USB_Z0 + USB_Z1) / 2,
                         USB_W, 2.5, USB_Z1 - USB_Z0))
    # TF 卡座开口(Y+ 侧墙)
    m = m.difference(box(0, OUT_H / 2 + 0.5, (SD_Z0 + SD_Z1) / 2,
                         SD_W, 2.5, SD_Z1 - SD_Z0))
    # BOOT/RST 侧面按键孔(X 两侧)
    for sx in (-1, 1):
        m = m.difference(cyl_x(sx * (OUT_W / 2 + 0.5), BTN_Y,
                               BTN_Z, BTN_R, WALL + 2.0))
    return m  # manifold 布尔后已闭合, 无需 merge/process(会破坏 watertight)


def make_lid():
    z0 = BASE_H
    m = box(0, 0, z0 + LID_T / 2, OUT_W, OUT_H, LID_T)
    # 屏幕开窗
    m = m.difference(box(0, 0, z0 + LID_T / 2,
                         WIN_W, WIN_H, LID_T + 0.4))
    # 四角螺丝通孔
    for sx in (-1, 1):
        for sy in (-1, 1):
            m = m.difference(cyl_z(sx * SCREW_X, sy * SCREW_Y,
                                   z0 + LID_T / 2, LID_SCREW_R, LID_T + 0.4))
    return m  # manifold 布尔后已闭合, 无需 merge/process(会破坏 watertight)


def render_preview(base, lid, path):
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    from matplotlib import font_manager
    from mpl_toolkits.mplot3d.art3d import Poly3DCollection

    font_manager.fontManager.addfont(
        '/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc')
    plt.rcParams['font.family'] = 'Noto Sans CJK JP'  # ttc 注册名
    plt.rcParams['axes.unicode_minus'] = False

    fig = plt.figure(figsize=(12, 6.2), facecolor='white')
    for idx, (mesh, title, color) in enumerate([
        (base, '底壳 Base  (PCB 正面朝上放入)', '#3b82c4'),
        (lid,  '上盖 Lid   (屏幕开窗 18.1 x 33.3)', '#e8833a'),
    ]):
        ax = fig.add_subplot(1, 2, idx + 1, projection='3d')
        tris = mesh.vertices[mesh.faces]
        ax.add_collection3d(Poly3DCollection(
            tris, facecolors=color, edgecolors='#22313f',
            linewidths=0.12, alpha=0.92))
        ax.set_xlim(-OUT_W / 2 - 2, OUT_W / 2 + 2)
        ax.set_ylim(-OUT_H / 2 - 2, OUT_H / 2 + 2)
        ax.set_zlim(-1, BASE_H + LID_T + 2)
        ax.set_box_aspect((OUT_W + 6, OUT_H + 6, BASE_H + LID_T + 4))
        ax.view_init(elev=30, azim=-58)
        ax.set_title(title, fontsize=12, pad=4)
        ax.axis('off')
    fig.text(0.5, 0.02,
             '外形 26.3 x 42.4 mm  |  内腔 21.5 x 37.6 mm  |  '
             'M2 螺丝孔 4 个  |  Type-C / TF 卡 / 按键开孔已预留',
             ha='center', fontsize=10, color='#333')
    plt.subplots_adjust(bottom=0.10, wspace=0.05)
    plt.savefig(path, dpi=160, facecolor='white')
    plt.close(fig)


def main():
    base = make_base()
    lid = make_lid()

    base_path = os.path.join(HERE, 'case_base.stl')
    lid_path = os.path.join(HERE, 'case_lid.stl')
    base.export(base_path)
    lid.export(lid_path)

    render_preview(base, lid, os.path.join(HERE, 'case_preview.png'))

    for name, m in [('底壳', base), ('上盖', lid)]:
        b = m.bounds
        print(f'{name}: 闭合={m.is_watertight}  '
              f'尺寸=({b[1][0]-b[0][0]:.2f} x {b[1][1]-b[0][1]:.2f} x '
              f'{b[1][2]-b[0][2]:.2f}) mm  三角面={len(m.faces)}  '
              f'体积={m.volume:.0f} mm3')
    print('已输出:', base_path, lid_path)


if __name__ == '__main__':
    main()
