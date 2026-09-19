#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""程序化生成三只宠物 × 三个进化阶段的像素精灵（TECH_DESIGN 8.1）。

设计要点
--------
* 逻辑画布 32×32，最后用最近邻放大 2 倍输出 64×64。
  16-bit 掌机的精灵本来就是 32×32 上下，先在逻辑网格上作画再整数倍放大，
  既保住了硬边像素（全程不产生抗锯齿），也让每个像素在 128px 的展示尺寸下
  正好占 4 个屏幕像素 —— 这正是"像素风"该有的颗粒感。
* 全部图形只用矩形和多边形填充，Pillow 的这两个操作默认不带抗锯齿。
* 20 色统一调色板，一次定死，三个物种和三个阶段共用。
* 固定随机种子（``SEED``），脚本重复执行生成的文件逐字节一致。
* 生成物直接写入 ``pet-web/src/assets/pets/``，**不要手改**，
  要调整就改这个脚本再跑一次。

运行：

    py -3 scripts/generate_sprites.py
"""

from __future__ import annotations

import random
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

# ---------------------------------------------------------------- 常量

SEED = 20260917
"""固定随机种子。阶段 2 的星点装饰用它取样，保证重复执行结果一致。"""

LOGICAL = 32
"""逻辑像素网格边长。"""

UPSCALE = 2
"""最近邻放大倍数，输出 64×64。"""

OUT_SIZE = LOGICAL * UPSCALE

ANCHOR = (16.0, 27.0)
"""缩放锚点 = 底部中心。三个阶段绕它缩放，踩在同一条地平线上。"""

BOTTOM_SHIFT = 1
"""整体下移几个逻辑像素。

锚点是缩放的不动点，改锚点不会让宠物动地方。落笔时脚在 y=27，
离画布底还空着 4 行 —— 渲染出来就是宠物浮在地面上方，
和界面里的椭圆影子对不上。下移 1 行之后，描边正好压到画布底边。
"""

REPO_ROOT = Path(__file__).resolve().parents[1]
OUT_DIR = REPO_ROOT / "pet-web" / "src" / "assets" / "pets"

SPECIES = ("cat", "dog", "dragon", "rabbit")
"""物种顺序 = 渲染顺序 = 共享随机数的消耗顺序。

**新物种只能追加到末尾，不能插在中间。** ``main()`` 里只有一个
``random.Random(SEED)``，按这个元组的顺序依次喂给每个 ``render()``；
阶段 2 的星点会用掉它。插在中间的话，它后面所有物种的星点落点全部改变 ——
也就是说"加了个物种"会顺手把已入库的旧精灵图改掉，而且 diff 里看不出来是为什么。
追加在末尾则前面物种消耗的仍是同一段序列，旧图逐字节不变。
"""

STAGE_SCALES = (0.68, 0.84, 1.0)
"""阶段 0 幼年 / 1 成长 / 2 最终。体型差异直接画进图里，界面不再二次缩放。"""

STAGE_TONES = (
    ("light", "base"),  # 阶段 0：整体偏浅
    ("base", "deep"),   # 阶段 1：正色 + 深色暗部
    ("base", "deep"),   # 阶段 2：正色 + 深色暗部 + 发光轮廓
)
"""阶段 → (body 明度, 暗部明度) 的调色板后缀。"""

# ---------------------------------------------------------------- 调色板
#
# 20 色，落在 TECH_DESIGN 要求的 16–24 区间。
# 主色沿用 theme.css：墨绿 / 米黄 / 暖橙 / 珊瑚红 / 天空蓝。

PALETTE: dict[str, str] = {
    # 通用
    "outline": "#14261a",      # 描边，与 --color-ink-green-dark 一致
    "highlight": "#ffffff",    # 眼睛高光
    "glow": "#fff3c4",         # 阶段 2 的发光轮廓
    "eye": "#14261a",
    "nose": "#d9534f",
    "inner_ear": "#f0b8b0",  # 内耳用柔粉；直接用珊瑚红会像挂了彩
    "belly": "#fdf6e3",
    # 猫：暖橙
    "cat_light": "#f2b26b",
    "cat_base": "#e08a3c",
    "cat_deep": "#b96a26",
    # 狗：茶褐。
    # 原来取的是米黄，结果精灵和 --color-screen(#fdf6e3) 几乎同色，
    # 在舞台上看就是一坨白影子；换成偏褐的中间调，和猫的暖橙也拉开了。
    "dog_light": "#e6cfa0",
    "dog_base": "#c9a86f",
    "dog_deep": "#8a6a42",
    # 龙：天空蓝
    "dragon_light": "#86bceb",
    "dragon_base": "#4a90d9",
    "dragon_deep": "#2f6dab",
    # 兔子：灰紫。
    # 不能取白或米 —— belly(#fdf6e3) 和 highlight(#ffffff) 已经占了那个区间，
    # 狗当年就栽在这上面（见上）。灰紫和狗的茶褐、belly 的米黄都不撞，
    # 和龙的天空蓝也拉得开：那个是饱和蓝，这个是低彩度的紫灰。
    "rabbit_light": "#cdc6da",
    "rabbit_base": "#a49bb8",
    "rabbit_deep": "#6f6884",
    # 配饰
    "scarf": "#d9534f",
    "scarf_dark": "#a83b38",
    "accent": "#f2c14e",
    "accent_dark": "#b8862a",
}

assert 16 <= len(PALETTE) <= 24, f"调色板必须落在 16–24 色，当前 {len(PALETTE)}"


def _rgba(name: str, alpha: int = 255) -> tuple[int, int, int, int]:
    value = PALETTE[name].lstrip("#")
    return (int(value[0:2], 16), int(value[2:4], 16), int(value[4:6], 16), alpha)


COLOR = {name: _rgba(name) for name in PALETTE}


def tone(species: str, shade: str) -> tuple[int, int, int, int]:
    return COLOR[f"{species}_{shade}"]


# ---------------------------------------------------------------- 画笔


class Painter:
    """把"作者坐标"（阶段 2 的全尺寸，32×32 网格）换算到当前缩放的画笔。

    所有图形都按阶段 2 的尺寸写死，缩放交给这里统一处理 ——
    这样调体型只要改 ``STAGE_SCALES``，不用把每个物种的坐标再抄三遍。
    """

    def __init__(self, scale: float) -> None:
        self.scale = scale
        self.img = Image.new("RGBA", (LOGICAL, LOGICAL), (0, 0, 0, 0))
        self.draw = ImageDraw.Draw(self.img)

    def _x(self, x: float) -> int:
        return round(ANCHOR[0] + (x - ANCHOR[0]) * self.scale)

    def _y(self, y: float) -> int:
        return round(ANCHOR[1] + (y - ANCHOR[1]) * self.scale + BOTTOM_SHIFT)

    def box(self, x0: float, y0: float, x1: float, y1: float, color) -> None:
        """闭区间矩形，坐标按作者坐标给出。"""
        self.draw.rectangle([self._x(x0), self._y(y0), self._x(x1), self._y(y1)], fill=color)

    def poly(self, points, color) -> None:
        self.draw.polygon([(self._x(x), self._y(y)) for x, y in points], fill=color)

    def line(self, points, color) -> None:
        self.draw.line([(self._x(x), self._y(y)) for x, y in points], fill=color)

    def dot(self, x: float, y: float, color) -> None:
        self.draw.point((self._x(x), self._y(y)), fill=color)

    def occupied(self, x: float, y: float) -> bool:
        return self.img.getpixel((self._x(x), self._y(y)))[3] != 0


def add_ring(img: Image.Image, color) -> None:
    """在现有轮廓外面加一圈 1 像素的环（描边和发光都用它）。

    先对整个图像做快照，再根据快照决定往哪些**透明**像素写色 ——
    否则新写的像素会被自己当成邻居，一圈变一片。
    """
    width, height = img.size
    source = img.copy().load()
    target = img.load()
    ring: list[tuple[int, int]] = []

    for y in range(height):
        for x in range(width):
            if source[x, y][3] != 0:
                continue
            for dy in (-1, 0, 1):
                for dx in (-1, 0, 1):
                    nx, ny = x + dx, y + dy
                    if 0 <= nx < width and 0 <= ny < height and source[nx, ny][3] != 0:
                        ring.append((x, y))
                        break
                else:
                    continue
                break

    for x, y in ring:
        target[x, y] = color


# ---------------------------------------------------------------- 各物种
#
# 坐标是"阶段 2 全尺寸"下的作者坐标，骨架固定如下：
#
#     耳朵 / 角   y  3.. 9      头      x  8..23, y 7..17
#     身体        x  9..22, y 18..27      脚 y 25..27
#
# 三个物种共用同一副骨架，靠耳朵、口鼻、尾巴和翅膀区分轮廓 ——
# 这正是 TECH_DESIGN 8.1 点的四处差异。
#
# 两条经验：
# 1. **头和身体之间要有一道深色分界线**，否则整只宠物糊成一个方块。
# 2. **耳朵要用暗部色而不是正色**，同色系的三角形贴在头上是看不出来的。

HEAD_TOP = 7
HEAD_BOTTOM = 17
BODY_TOP = 18
BODY_BOTTOM = 27


def draw_torso(p: Painter, species: str, shade: str, shadow: str, *, wide: bool = False) -> None:
    """共用的躯干：肩窄臀圆的身体 + 浅色肚皮 + 两只脚。"""
    base = tone(species, shade)
    dark = tone(species, shadow)

    left, right = (8, 23) if wide else (9, 22)

    # 分三段画，肩膀收进去 1 像素，比一个方块更像小动物
    p.box(left + 1, BODY_TOP, right - 1, BODY_TOP + 1, base)
    p.box(left, BODY_TOP + 2, right, BODY_BOTTOM - 2, base)
    p.box(left + 1, BODY_BOTTOM - 1, right - 1, BODY_BOTTOM, base)

    # 肚皮
    p.box(left + 3, BODY_TOP + 3, right - 3, BODY_BOTTOM - 3, COLOR["belly"])

    # 脚：压在身体下缘。这里固定用最深的那个明度，
    # 因为阶段 0 的 body 和暗部只差一档，用 shadow 的话脚是完全看不出来的。
    feet = tone(species, "deep")
    p.box(left + 1, BODY_BOTTOM - 2, left + 4, BODY_BOTTOM, feet)
    p.box(right - 4, BODY_BOTTOM - 2, right - 1, BODY_BOTTOM, feet)


def draw_head(p: Painter, species: str, shade: str, *, left: int = 8, right: int = 23) -> None:
    p.box(left, HEAD_TOP, right, HEAD_BOTTOM, tone(species, shade))
    # 下颌阴影：不然头和身体糊成一整块
    p.box(left + 1, HEAD_BOTTOM, right - 1, HEAD_BOTTOM, COLOR["outline"])


def draw_face(p: Painter, *, eye_x: tuple[int, int] = (11, 19), nose_y: int = 14) -> None:
    """两只眼睛 + 鼻子。眼睛是 2×2，左上角一个白点当高光。"""
    for x in eye_x:
        p.box(x, 11, x + 1, 12, COLOR["eye"])
        p.dot(x, 11, COLOR["highlight"])
    p.box(15, nose_y, 16, nose_y, COLOR["nose"])


# ---- 猫 -------------------------------------------------------------------


def draw_cat(p: Painter, shade: str, shadow: str) -> None:
    draw_torso(p, "cat", shade, shadow)
    base = tone("cat", shade)
    dark = tone("cat", shadow)

    draw_head(p, "cat", shade)

    # 尖耳：暗色的三角形，压在头顶两角
    p.poly([(8, 9), (13, 9), (10, 3)], dark)
    p.poly([(18, 9), (23, 9), (21, 3)], dark)
    # 内耳，左右各一（曾经只画了左边，一只耳朵带红点，像挂了彩）
    p.poly([(10, 9), (12, 9), (11, 6)], COLOR["inner_ear"])
    p.poly([(20, 9), (22, 9), (21, 6)], COLOR["inner_ear"])

    draw_face(p)

    # 尾巴：从身体右侧绕上去，尾尖提亮
    p.box(23, 23, 25, 26, base)
    p.box(25, 20, 27, 25, base)
    p.box(26, 17, 28, 21, base)
    p.box(25, 14, 27, 18, base)
    p.box(25, 14, 27, 15, tone("cat", "light"))


def whiskers_cat(p: Painter) -> None:
    """猫的胡须。在描边之后画，否则 1 像素的线会被描成 3 像素。"""
    ink = COLOR["outline"]
    for dy in (-1, 1, 3):
        p.line([(7, 12 + dy), (3, 11 + dy)], ink)
        p.line([(24, 12 + dy), (28, 11 + dy)], ink)


# ---- 狗 -------------------------------------------------------------------


def draw_dog(p: Painter, shade: str, shadow: str) -> None:
    draw_torso(p, "dog", shade, shadow, wide=True)
    base = tone("dog", shade)
    dark = tone("dog", shadow)

    draw_head(p, "dog", shade)

    # 垂耳：挂在头两侧的深色长条，比头低一截
    p.box(6, 8, 9, 18, dark)
    p.box(7, 18, 9, 19, dark)
    p.box(22, 8, 25, 18, dark)
    p.box(22, 18, 24, 19, dark)

    # 圆口鼻：一块浅色坐在脸的下半部。
    # 只占 14..16 三行 —— 拉满到下颌线的话会和肚皮连成一整块白。
    p.box(12, 14, 19, 16, COLOR["belly"])
    draw_face(p, nose_y=14)
    # 鼻子下面一道嘴缝
    p.box(15, 16, 16, 16, COLOR["outline"])

    # 短尾巴，往外翘
    p.box(23, 21, 25, 24, base)
    p.box(25, 18, 27, 23, base)
    p.box(25, 17, 27, 18, tone("dog", "light"))


# ---- 龙 -------------------------------------------------------------------


def draw_dragon(p: Painter, shade: str, shadow: str) -> None:
    base = tone("dragon", shade)
    light = tone("dragon", "light")

    # 翅膀：画在躯干之前，才像是从背后长出来的。
    # 翼尖扬到 y=11 就打住 —— 再往上和脑袋连成一片，整只龙就成了个菱形色块。
    p.poly([(12, 22), (5, 11), (1, 17), (8, 23)], light)
    p.poly([(19, 22), (26, 11), (30, 17), (23, 23)], light)

    draw_torso(p, "dragon", shade, shadow)

    # 翅膀和身体之间补一道深色缝。描边只描外轮廓，
    # 不补这一笔的话翅膀就是身体上鼓出来的两块，看不出是背后的翅膀。
    p.box(9, 20, 9, 23, COLOR["outline"])
    p.box(22, 20, 22, 23, COLOR["outline"])

    # 尾巴 + 尖刺
    p.box(22, 23, 24, 26, base)
    p.box(24, 20, 26, 24, base)
    p.poly([(26, 17), (30, 21), (25, 23)], COLOR["accent"])

    # 角
    p.poly([(9, 9), (12, 9), (10, 3)], COLOR["accent"])
    p.poly([(19, 9), (22, 9), (21, 3)], COLOR["accent"])

    draw_head(p, "dragon", shade)
    # 长口鼻
    p.box(13, 13, 19, 16, light)
    draw_face(p, nose_y=13)

    # 腹甲：三条横纹
    for y in (22, 24, 26):
        p.box(13, y, 18, y, COLOR["accent_dark"])


# ---- 兔子 -----------------------------------------------------------------


def draw_rabbit(p: Painter, shade: str, shadow: str) -> None:
    draw_torso(p, "rabbit", shade, shadow)
    base = tone("rabbit", shade)
    dark = tone("rabbit", shadow)

    draw_head(p, "rabbit", shade)

    # 长耳：又高又窄，一直立到画布上缘。
    # 猫的尖耳是 6 像素宽、6 像素高，兔子是 4 像素宽、9 像素高 ——
    # 靠「高瘦」而不是「宽大」拉开剪影。宽度再收就成两根线了，
    # 再放宽又会变成猫耳朵的翻版。
    p.poly([(10, 9), (13, 9), (11, 0)], dark)
    p.poly([(18, 9), (21, 9), (20, 0)], dark)
    # 内耳一路画到接近耳尖。只画根部的话，长耳朵会读成两根柱子。
    p.poly([(11, 8), (12, 8), (11, 2)], COLOR["inner_ear"])
    p.poly([(19, 8), (20, 8), (20, 2)], COLOR["inner_ear"])

    # 小口鼻：一块浅色坐在鼻子周围。
    # 不加这一块的话，兔子就是「猫脸换个颜色」—— 两者骨架本来就一样，
    # 剪影之外必须再给脸部一个差异点。尺寸取在猫（无）和狗（横贯半张脸）之间。
    p.box(14, 13, 17, 15, COLOR["belly"])
    draw_face(p, nose_y=14)

    # 短尾绒球：一小团圆球贴在臀侧。
    # 猫的尾巴是一路甩到 y=14 的长弧，狗的是往外翘的短棒，
    # 兔子用一团不伸出去的球 —— 三种尾巴一眼就能分开。
    p.box(23, 22, 25, 24, base)
    p.dot(24, 21, tone("rabbit", "light"))


DRAW_BODY = {"cat": draw_cat, "dog": draw_dog, "dragon": draw_dragon, "rabbit": draw_rabbit}


# ---------------------------------------------------------------- 配饰


def draw_accessory(p: Painter, species: str, stage: int) -> None:
    """阶段 1 加围巾，阶段 2 再加专属头饰（TECH_DESIGN 8.1）。"""
    if stage < 1:
        return

    # 围巾：横过脖子，右边垂下一条
    p.box(9, 17, 22, 18, COLOR["scarf"])
    p.box(9, 19, 22, 19, COLOR["scarf_dark"])
    p.box(19, 19, 22, 23, COLOR["scarf"])

    if stage < 2:
        return

    if species == "dragon":
        # 龙用第三只角当冠
        p.poly([(13, 8), (16, 2), (19, 8)], COLOR["accent"])
    else:
        # 猫、狗、兔子戴小王冠，位置卡在两只耳朵中间
        p.box(13, 5, 18, 6, COLOR["accent"])
        p.box(13, 6, 18, 6, COLOR["accent_dark"])
        for x in (13, 15, 17):
            p.poly([(x, 5), (x + 1, 5), (x, 2), ], COLOR["accent"])


def draw_sparkles(p: Painter, rng: random.Random) -> None:
    """阶段 2 的星点。用固定种子取样，重复执行落点一样。"""
    for _ in range(6):
        x = rng.randint(3, 29)
        y = rng.randint(3, 24)
        if p.occupied(x, y):
            continue  # 落在宠物身上就跳过，避免变成脸上的噪点
        p.dot(x, y, COLOR["glow"])


# ---------------------------------------------------------------- 组装


def render(species: str, stage: int, rng: random.Random) -> Image.Image:
    shade, shadow = STAGE_TONES[stage]
    p = Painter(STAGE_SCALES[stage])

    DRAW_BODY[species](p, shade, shadow)
    draw_accessory(p, species, stage)

    # 深色描边：所有阶段都有，否则精灵在浅色内屏上会糊掉
    add_ring(p.img, COLOR["outline"])
    if stage == 2:
        # 发光轮廓：一层实色 + 一层半透明，读起来像在发光
        add_ring(p.img, _rgba("glow"))
        add_ring(p.img, _rgba("glow", 96))
        draw_sparkles(p, rng)

    # 细线放在描边之后，才不会被描粗
    if species == "cat":
        whiskers_cat(p)

    return p.img.resize((OUT_SIZE, OUT_SIZE), Image.NEAREST)


def build_contact_sheet(sprites: dict[tuple[str, int], Image.Image]) -> Image.Image:
    """一致性检查用的大图：3 物种 × 3 阶段，衬着棋盘格看透明区域。"""
    gutter = 24  # 左侧留给物种名，免得文字压在精灵上
    cell = OUT_SIZE + 12
    header = 20
    swatch_h = 34
    width = gutter + cell * len(STAGE_SCALES) + 8
    height = header + cell * len(SPECIES) + swatch_h

    sheet = Image.new("RGBA", (width, height), (38, 38, 46, 255))

    # 棋盘格底板，透明像素一眼可见
    grid = Image.new("RGBA", (width, height), (52, 52, 62, 255))
    for y in range(height):
        for x in range(width):
            if (x // 8 + y // 8) % 2 == 0:
                grid.putpixel((x, y), (38, 38, 46, 255))
    sheet.paste(grid, (0, 0))

    # 用 Pillow 自带的位图字体：不引入任何外部字体文件，也就没有授权问题（PRD 4.1）
    font = ImageFont.load_default()
    pen = ImageDraw.Draw(sheet)

    for column, _ in enumerate(STAGE_SCALES):
        pen.text((gutter + column * cell, 5), f"stage {column}", font=font, fill=(240, 240, 240, 255))

    for row, species in enumerate(SPECIES):
        top = header + row * cell
        pen.text((4, top + cell // 2 - 4), species, font=font, fill=(240, 240, 240, 255))
        for column in range(len(STAGE_SCALES)):
            sprite = sprites[(species, column)]
            sheet.paste(sprite, (gutter + column * cell, top + 6), sprite)

    # 底部色板，用来核对颜色数量
    swatch_y = header + cell * len(SPECIES) + 14
    pen.text((4, swatch_y - 11), f"palette {len(PALETTE)} colors", font=font, fill=(230, 230, 230, 255))
    for index, name in enumerate(PALETTE):
        sheet.paste(Image.new("RGB", (14, 14), _rgba(name)[:3]), (4 + index * 16, swatch_y))

    return sheet.convert("RGB")


def verify(sprites: dict[tuple[str, int], Image.Image]) -> None:
    """自检：尺寸、模式、颜色数量都对不上就直接失败，别把坏图当成品。"""
    expected = len(SPECIES) * len(STAGE_SCALES)
    assert len(sprites) == expected, f"应有 {expected} 张精灵，实际 {len(sprites)}"

    used: set[tuple[int, int, int]] = set()
    for (species, stage), image in sprites.items():
        assert image.size == (OUT_SIZE, OUT_SIZE), f"{species}-stage{stage} 尺寸是 {image.size}"
        assert image.mode == "RGBA", f"{species}-stage{stage} 不是 RGBA"
        assert image.getextrema()[3][0] == 0, f"{species}-stage{stage} 没有透明像素，八成画满了"
        for pixel in image.getdata():
            if pixel[3] != 0:
                used.add(pixel[:3])

    assert len(used) <= 24, f"实际用到 {len(used)} 种颜色，超出 24 色上限"
    print(f"生成 {expected} 张 {OUT_SIZE}×{OUT_SIZE} 精灵 → {OUT_DIR}")
    print(f"调色板 {len(PALETTE)} 色，实际用到 {len(used)} 色")
    print("联系表 →", OUT_DIR / "contact-sheet.png")


def use_utf8_output() -> None:
    """重定向输出时也写 UTF-8。

    中文 Windows 上 Python 重定向到文件/管道时会按 cp936 编码，
    日志和 CI 里按 UTF-8 一读就是乱码。直接接在控制台上时 Python 报的就是
    utf-8（走的是 Windows 控制台 API），所以这里只对重定向生效，不影响终端显示。
    """
    encoding = (sys.stdout.encoding or "").lower()
    if encoding not in ("utf-8", "utf8"):
        try:
            sys.stdout.reconfigure(encoding="utf-8")
        except (AttributeError, OSError):
            pass  # 老版本 Python 或者特殊流，保持原样就好


def main() -> None:
    use_utf8_output()
    rng = random.Random(SEED)
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    sprites: dict[tuple[str, int], Image.Image] = {
        (species, stage): render(species, stage, rng)
        for species in SPECIES
        for stage in range(len(STAGE_SCALES))
    }

    for (species, stage), image in sprites.items():
        image.save(OUT_DIR / f"{species}-stage{stage}.png")

    build_contact_sheet(sprites).save(OUT_DIR / "contact-sheet.png")
    verify(sprites)


if __name__ == "__main__":
    main()
