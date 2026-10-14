"""Desktop layout illustration from actual assets; never a phone screenshot."""
from pathlib import Path
import json
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / 'android/app/src/main/assets/art'
FONT = ROOT / 'android/app/src/main/assets/fonts/campus-sans.ttf'
CAT = json.loads((ASSETS / 'catalog.json').read_text(encoding='utf-8'))
INK = (36, 46, 69)
MUTED = (91, 106, 128)
ACCENT = (82, 103, 155)
CANVAS = (245, 246, 251)
PALE = (234, 239, 250)
S = 2
SEED = 4


def font(size):
    return ImageFont.truetype(str(FONT), round(size * S))


def box(im, rect, color, radius=18):
    overlay = Image.new('RGBA', im.size)
    ImageDraw.Draw(overlay).rounded_rectangle(
        tuple(round(v * S) for v in rect), round(radius * S), fill=color)
    im.alpha_composite(overlay)


def text(im, xy, content, size=14, color=INK):
    ImageDraw.Draw(im).text(tuple(round(v * S) for v in xy), content,
                           font=font(size), fill=color)


def wrap(im, xy, content, size, width, color):
    draw = ImageDraw.Draw(im)
    x, y = xy
    line = ''
    for char in content:
        if char == '\n' or draw.textlength(line + char, font=font(size)) > width * S:
            text(im, (x, y), line, size, color)
            y += size + 6
            line = '' if char == '\n' else char
        else:
            line += char
    if line:
        text(im, (x, y), line, size, color)
        y += size + 6
    return y


def paste_fit(im, source, rect):
    x, y, w, h = rect
    pic = source.convert('RGBA')
    pic.thumbnail((round(w * S), round(h * S)), Image.Resampling.LANCZOS)
    im.alpha_composite(pic, (round((x + w / 2) * S - pic.width / 2),
                            round((y + h / 2) * S - pic.height / 2)))


def backdrop(page):
    # Match ArtImageView: cover a fixed screen, favoring the top 20% of crop.
    wall = Image.open(ASSETS / CAT['backgrounds'][(SEED + page) % len(CAT['backgrounds'])]).convert('RGBA')
    scale = max(360 * S / wall.width, 784 * S / wall.height)
    wall = wall.resize((round(wall.width * scale), round(wall.height * scale)),
                       Image.Resampling.LANCZOS)
    left = (wall.width - 360 * S) / 2
    top = (wall.height - 784 * S) * .2
    return wall.crop((round(left), round(top), round(left + 360 * S),
                      round(top + 784 * S)))


def module(im, y, height, title, index, running=False):
    box(im, (16, y, 344, y + height), (255, 255, 255, 210), 22)
    text(im, (34, y + 39), title, 18)
    pair = CAT['pairs'][(SEED + index * 7) % len(CAT['pairs'])]
    paste_fit(im, Image.open(ASSETS / pair['running' if running else 'normal']),
              (251.6, y + 12, 74.4, 81.6))
    return y + 100


def button(im, y, label, primary=False):
    box(im, (34, y, 326, y + 50), ACCENT if primary else PALE, 14)
    width = ImageDraw.Draw(im).textlength(label, font=font(14)) / S
    text(im, (180 - width / 2, y + 15), label, 14,
         (255, 255, 255) if primary else ACCENT)


def header(im, title, subtitle):
    box(im, (0, 0, 360, 136), (255, 255, 255, 220), 0)
    head = Image.open(ROOT / 'android/app/src/main/res/drawable-nodpi/brand_head.webp')
    head = head.convert('RGBA').resize((38 * S, 38 * S), Image.Resampling.LANCZOS)
    mask = Image.new('L', head.size)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, 38 * S, 38 * S), 12 * S, fill=255)
    head.putalpha(mask)
    im.alpha_composite(head, (22 * S, 12 * S))
    text(im, (70, 12), '华理跑步姬', 16)
    text(im, (70, 36), 'CAMPUS COMPANION', 9, MUTED)
    box(im, (267, 18, 338, 47), PALE, 14)
    text(im, (278, 25), '奉贤校区', 11, ACCENT)
    text(im, (22, 72), title, 24)
    text(im, (22, 107), subtitle, 12, MUTED)


def nav(im, selected):
    # Android clips each page to the content frame above the navigation bar.
    im.paste(backdrop(selected).crop((0, 686 * S, 360 * S, 784 * S)), (0, 686 * S))
    box(im, (0, 686, 360, 784), (255, 255, 255, 235), 0)
    d = ImageDraw.Draw(im)
    for i, label in enumerate(['模拟', '自动', '记录', '地图', '账号']):
        x = 12 + i * 67.2
        if i == selected:
            box(im, (x, 694, x + 67.2, 776), PALE, 18)
        color = ACCENT if i == selected else MUTED
        def line(points):
            d.line([(round(a * S), round(b * S)) for a, b in points],
                   fill=color, width=2 * S)
        if i in (0, 4):
            asset = ROOT / 'android/app/src/main/res/drawable-nodpi' / ('nav_running.webp' if i == 0 else 'nav_account.webp')
            size = 52 if i == 0 else 48 * .8
            paste_fit(im, Image.open(asset), (x + (67.2 - size) / 2, 694 + (56 - size) / 2, size, size))
        elif i == 1:
            d.ellipse(((x + 21) * S, 707 * S, (x + 46) * S, 732 * S),
                      outline=color, width=2 * S)
            line([(x + 33, 710), (x + 33, 720), (x + 39, 724)])
        elif i == 2:
            d.rounded_rectangle(((x + 24) * S, 706 * S, (x + 44) * S, 734 * S),
                                3 * S, outline=color, width=2 * S)
            for y in [713, 720, 727]:
                line([(x + 29, y), (x + 39, y)])
        elif i == 3:
            line([(x + 20, 709), (x + 29, 706), (x + 38, 709), (x + 47, 706),
                  (x + 47, 731), (x + 38, 734), (x + 29, 731), (x + 20, 734),
                  (x + 20, 709)])
            line([(x + 29, 706), (x + 29, 731)])
            line([(x + 38, 709), (x + 38, 734)])
        text(im, (x + 21.6, 753), label, 12, color)


def account():
    im = backdrop(4)
    header(im, '账号与工具', '登录会话、锁屏自检与后台排查')
    y = module(im, 152, 508, '登录与会话', 0)
    text(im, (38, y + 5), '尚未登录，可在手机上获取会话', 14, MUTED)
    button(im, y + 39, '手机登录并获取会话', True)
    button(im, y + 99, '选择会话文件')
    button(im, y + 159, '导出会话文件到手机')
    box(im, (34, y + 221, 326, y + 336), (244, 248, 253, 238), 14)
    text(im, (48, y + 232), '会话保存在本机', 12, ACCENT)
    wrap(im, (48, y + 258), '使用绑定手机号和短信验证码，或华体账号密码登录。需要会话文件时，可导出 JSON 保存到手机。',
         13, 264, (76, 92, 115))
    button(im, y + 346, '清除本机会话')
    y = module(im, 676, 230, '工具与排查', 6)
    button(im, y + 4, '一分钟锁屏自检')
    nav(im, 4)
    return im


def normal():
    im = backdrop(0)
    header(im, '模拟跑步', '先预览，再开始你的模拟记录')
    y = module(im, 152, 220, '模拟目标', 1)
    for x, value, label in [(38, '2.2 km', '默认目标'), (135, '15 min', '默认时长'),
                             (235, '3 个点', '必经点位')]:
        text(im, (x, y + 3), value, 18)
        text(im, (x, y + 32), label, 12, MUTED)
    button(im, y + 58, '查看当前记录')
    y = module(im, 388, 310, '01  起点与考核点', 2)
    for x, label in [(38, '起点纬度'), (184, '起点经度')]:
        text(im, (x, y + 8), label, 11, MUTED)
        box(im, (x, y + 34, x + 132, y + 86), CANVAS, 12)
    box(im, (34, y + 96, 326, y + 144), (244, 248, 253, 230), 14)
    text(im, (47, y + 108), '起点使用说明  ＋', 13, ACCENT)
    button(im, y + 130, '读取手机当前位置')
    module(im, 714, 350, '02  模拟参数', 3)
    nav(im, 0)
    return im


def active():
    im = backdrop(2)
    header(im, '跑步记录', '当前进度与服务器判定')
    y = module(im, 152, 630, '当前记录', 8, True)
    text(im, (38, y + 5), '模拟进行中', 13, ACCENT)
    text(im, (38, y + 40), '模拟里程 / km', 12, MUTED)
    text(im, (198, y + 40), '剩余时间', 12, MUTED)
    text(im, (38, y + 65), '1.32', 36)
    text(im, (198, y + 65), '06:00', 36)
    box(im, (38, y + 123, 322, y + 131), PALE, 4)
    box(im, (38, y + 123, 209, y + 131), ACCENT, 4)
    box(im, (38, y + 147, 322, y + 340), CANVAS, 14)
    # The App renders its real map with RoutePreviewView.
    wrap(im, (54, y + 204), '校园路线地图区域\n示例进度，仅用于设计预览', 13, 252, MUTED)
    text(im, (38, y + 355), '本地示例 · 不代表真实或已提交记录', 12, MUTED)
    button(im, y + 391, '暂停本次模拟')
    nav(im, 2)
    return im


def main():
    out = Image.new('RGBA', (1210 * S, 914 * S), (233, 237, 247, 255))
    text(out, (30, 18), '华理跑步姬 v2.0.4 · 清晰提示与轻量头像', 23)
    text(out, (30, 50), '设计预览，非手机截图 · 账号头像缩小 20% · 分组提示 · 保留全部功能', 13, MUTED)
    panels = [('账号页 · 小人进入功能模块', account()),
              ('模拟页 · 无独立插画模块', normal()),
              ('模拟中 · 对应跑步姿态', active())]
    for i, (label, panel) in enumerate(panels):
        x = 30 + i * 400
        text(out, (x, 82), label, 15)
        out.alpha_composite(panel, (x * S, 112 * S))
    path = ROOT / 'dist/ecust-running-girl-v2.0.4-preview.jpg'
    out.convert('RGB').save(path, quality=94)
    print('Design preview written (not an Android screenshot):', path)


if __name__ == '__main__':
    main()
