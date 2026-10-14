"""Design illustration of the actual splash assets and branded dialog styling.

This is a desktop design preview, not an Android screenshot or device validation.
"""
import math
import xml.etree.ElementTree as ET
from PIL import Image, ImageDraw, ImageFont
from module_preview import ROOT, ASSETS, CAT, S, font, text, wrap, box, paste_fit, normal, account


def gradient(size, colours, stops):
    out = Image.new('RGBA', size)
    draw = ImageDraw.Draw(out)
    for x in range(size[0]):
        t = x / max(1, size[0] - 1)
        k = next((i for i in range(len(stops) - 1) if stops[i] <= t <= stops[i + 1]), len(stops) - 2)
        f = (t - stops[k]) / (stops[k + 1] - stops[k])
        c = tuple(round(a + (b - a) * f) for a, b in zip(colours[k], colours[k + 1]))
        draw.line((x, 0, x, size[1]), fill=c)
    return out


def splash(scene):
    im = Image.new('RGBA', (360 * S, 784 * S), '#F5F6FB')
    picture = Image.open(ASSETS / scene).convert('RGBA')
    picture.thumbnail((360 * S, 598 * S), Image.Resampling.LANCZOS)
    im.alpha_composite(picture, ((360 * S - picture.width) // 2, (598 * S - picture.height) // 2))
    wash = gradient((202 * S, 598 * S), [(221, 239, 253, 248), (226, 242, 253, 222), (232, 245, 255, 0)], [0, .44, 1])
    im.alpha_composite(wash, (0, 0))
    for x, content, size, colour in [(30, '华理跑步姬—', 22, '#2C4766'), (65, '跑步记录模拟', 18, '#465F7C')]:
        for i, glyph in enumerate(content):
            center_y = 299 - 36 * 2.5 + i * 36
            if glyph == '—':
                ImageDraw.Draw(im).line((x * S, (center_y - 8) * S, x * S, (center_y + 8) * S), fill=colour, width=2 * S)
            else:
                ImageDraw.Draw(im).text((x * S, center_y * S), glyph, font=font(size), fill=colour, anchor='mm')
    box(im, (0, 598, 360, 784), '#FFFFFF', 0)
    text(im, (120, 621), '华理跑步姬', 24)
    text(im, (107, 661), 'ECUST · CAMPUS COMPANION', 9, '#52679B')
    wrap(im, (28, 691), 'tips：先热身，再出发，让身体慢慢进入节奏。', 13, 304, '#4C5C73')
    box(im, (144, 756, 216, 759), '#52679B', 2)
    return im


def note(im, x, y, width, title, copy):
    layer = Image.new('RGBA', im.size)
    text(layer, (x + 14, y + 11), title, 12, '#52679B')
    end = wrap(layer, (x + 14, y + 36), copy, 13, width - 28, '#4C5C73')
    height = end - y + 10
    box(im, (x, y, x + width, y + height), '#F4F8FD', 14)
    im.alpha_composite(layer)
    return y + height


def action(im, y, label, primary=True):
    box(im, (40, y, 320, y + 50), '#52679B' if primary else '#EAEFFA', 14)
    ImageDraw.Draw(im).text((180 * S, (y + 25) * S), label, font=font(14), fill='#FFFFFF' if primary else '#52679B', anchor='mm')


def dialog(kind):
    im = normal() if kind == 'create' else account()
    im.alpha_composite(Image.new('RGBA', im.size, (0, 0, 0, 82)))
    if kind == 'create':
        title, caption, top, bottom = '创建模拟记录', '确认本次参数，准备开始计时', 60, 729
    elif kind == 'probe':
        title, caption, top, bottom = '已设置锁屏自检', '现在锁屏，稍后回来查看结果', 62, 722
    else:
        title, caption, top, bottom = '后台排查报告', '检查权限、系统唤醒与联网情况', 52, 744
    box(im, (20, top, 340, bottom), '#FCFDFF', 26)
    hero = gradient((320 * S, 166 * S), [(226, 240, 254, 255), (245, 249, 255, 255)], [0, 1])
    mask = Image.new('L', (320 * S, (bottom - top) * S))
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, 320 * S - 1, (bottom - top) * S - 1), 26 * S, fill=255)
    hero.putalpha(mask.crop((0, 0, 320 * S, 166 * S)))
    im.alpha_composite(hero, (20 * S, top * S))
    box(im, (146, top + 20, 214, top + 88), '#FFFFFF', 22)
    paste_fit(im, Image.open(ROOT / 'android/app/src/main/res/drawable-nodpi/brand_head.webp'), (150, top + 24, 60, 60))
    ImageDraw.Draw(im).text((180 * S, (top + 113) * S), title, font=font(22), fill='#242E45', anchor='mm')
    ImageDraw.Draw(im).text((180 * S, (top + 148) * S), caption, font=font(12), fill='#4C5C73', anchor='mm')
    y = top + 181
    if kind == 'create':
        for i, (value, label) in enumerate([('2.20', '目标 / 公里'), ('15', '时长 / 分钟'), ('2600', '模拟步数')]):
            x = 40 + i * 95.7
            box(im, (x, y, x + 88.6, y + 76), '#EAEFFA', 14)
            ImageDraw.Draw(im).text(((x + 44.3) * S, (y + 26) * S), value, font=font(20), fill='#242E45', anchor='mm')
            ImageDraw.Draw(im).text(((x + 44.3) * S, (y + 55) * S), label, font=font(12), fill='#5B6A80', anchor='mm')
        y += 90
        y = note(im, 40, y, 280, '路线与里程', '沿校园道路经过三点，实际里程在 2.10–2.70 公里之间，提交以实际路线为准。\n\n已预览路线：2.19 公里。') + 12
        y = note(im, 40, y, 280, '结束后的处理', '计时结束后立即校验并提交；服务器判定无效时，也会提交并结束本条记录。')
        action(im, bottom - 126, '创建并等待')
        action(im, bottom - 66, '返回修改', False)
    elif kind == 'probe':
        y = note(im, 40, y, 280, '自检时间', '示例时间 · 14:01:00\n到此时间后再等约一分钟，打开 App 查看“后台排查报告”。') + 12
        y = note(im, 40, y, 280, '检查范围', '仅验证系统唤醒和到华体服务器的 HTTPS 连通性，不携带账号会话，不创建或提交跑步记录。') + 12
        note(im, 40, y, 280, '等待期间', '系统省电可能延迟自检。请保持联网，避免短时间重复测试。')
        action(im, bottom - 66, '知道了')
    else:
        y = note(im, 40, y, 280, '报告内容', '报告不包含登录会话、账号或位置坐标。可长按选择文字，也可复制完整报告。') + 14
        box(im, (40, y, 320, bottom - 142), '#F0F6FD', 14)
        wrap(im, (53, y + 14), '华理跑步姬 2.0.4 后台排查报告\n\n以下为设计示例，不代表设备结果：\n\n精确定时权限：已允许\n电池优化：已排除\n当前网络：已联网\n每日开关：关闭\n当前记录状态：无\n\n运行事件：可滚动查看……', 13, 254, '#4C5C73')
        action(im, bottom - 126, '复制报告')
        action(im, bottom - 66, '关闭', False)
    return im


def main():
    root = ET.parse(ROOT / 'android/app/src/main/res/values/brand.xml').getroot()
    assert root.find("string[@name='splash_vertical_first']").text == '华理跑步姬—'
    assert root.find("string[@name='splash_vertical_second']").text == '跑步记录模拟'
    art_info = __import__('json').loads((ROOT / 'android/design-assets/art-build.json').read_text(encoding='utf-8'))
    new_scene = next(row['asset'] for row in art_info['sources'] if '02-旗袍-无语' in row['source'])
    panels = [('开屏 · 左侧渐变与竖排标题', splash(new_scene)), ('创建记录 · 参数分组', dialog('create')),
              ('锁屏自检 · 清晰步骤', dialog('probe')), ('后台排查 · 报告可滚动', dialog('report'))]
    out = Image.new('RGBA', (1630 * S, 914 * S), '#EDF3FA')
    text(out, (30, 18), '华理跑步姬 v2.0.4 · 开屏与提示界面', 23)
    text(out, (30, 50), '设计预览，非手机截图 · 使用实际内置素材 · 参数、时间和报告为示例', 13, '#5B6A80')
    for i, (label, panel) in enumerate(panels):
        x = 30 + i * 400
        text(out, (x, 82), label, 15)
        out.alpha_composite(panel, (x * S, 112 * S))
    path = ROOT / 'dist/welcome-dialogs-v2.0.4-preview.jpg'
    out.convert('RGB').save(path, quality=95)
    print('Desktop design preview:', path)


if __name__ == '__main__':
    main()
