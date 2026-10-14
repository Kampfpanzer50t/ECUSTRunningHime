"""Package original 3D wallpapers and the supplied navigation character."""
from pathlib import Path
import hashlib
import json
import numpy as np
from PIL import Image, ImageDraw, ImageFont
from prepare_art import transparent_background

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / 'android/app/src/main/assets/art'
RES = ROOT / 'android/app/src/main/res/drawable-nodpi'
RENDERS = Path(__file__).with_name('geometry-renders')
CHARACTERS = Path('E:/ai.q.ecust.as/01-原图截取6张')
NAMES = ['雾蓝云谷', '冰蓝层峦', '晨光浮丘']
def blue_grade(pic):
    # Preserve rendered luminance/volume while taking grey highlights into powder blue.
    pixels = np.asarray(pic.convert('RGB'), dtype=np.float32)
    pixels = pixels * np.array([.95, .95, .90]) + np.array([3, 14, 38])
    return Image.fromarray(np.clip(pixels, 0, 255).astype(np.uint8), 'RGB')
catalog = json.loads((ASSETS / 'catalog.json').read_text(encoding='utf-8'))
report = {
    'source': 'Original procedural 3D scenes created for this application',
    'renderer': 'Blender 4.2.23 / Cycles',
    'scene_script': 'android/design-assets/render_geometry.py',
    'palette_grade': {'rgb_multipliers': [.95, .95, .90], 'rgb_offsets': [3, 14, 38]},
    'reference': 'Kenney Background Elements sheet: cloud, hill and geometric tree vocabulary only; no source imagery embedded',
    'backgrounds': [], 'navigation': {}
}

for i, name in enumerate(NAMES):
    source = RENDERS / f'geometry_{i + 1:02d}.png'
    pic = blue_grade(Image.open(source))
    assert pic.size == (1080, 2160), pic.size
    asset = f'backgrounds/geometry_{i + 1:02d}.webp'
    (ASSETS / asset).parent.mkdir(exist_ok=True)
    pic.save(ASSETS / asset, 'WEBP', quality=94, method=6)
    report['backgrounds'].append({
        'name': name, 'asset': asset, 'output_size': list(pic.size),
        'output_sha256': hashlib.sha256((ASSETS / asset).read_bytes()).hexdigest(),
        'render_sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
        'scene_sha256': hashlib.sha256((RENDERS / f'geometry_{i + 1:02d}.blend').read_bytes()).hexdigest()
    })
catalog['backgrounds'] = [row['asset'] for row in report['backgrounds']]

# Same character and outfit as the existing, unchanged launcher head crop.
running = Image.open(ASSETS / 'mascots/01_01_running.webp').convert('RGBA')
running.thumbnail((384, 384), Image.Resampling.LANCZOS)
running.save(RES / 'nav_running.webp', lossless=True, method=6)
crop = (80, 92, 305, 297)
normal_path = CHARACTERS / '01-尴尬-正常.png'
normal = transparent_background(Image.open(normal_path).crop(crop))
# Remove the remaining edge of the detached expression puff above the hood.
ImageDraw.Draw(normal).rectangle((135, 0, 181, 12), fill=(0, 0, 0, 0))
normal.thumbnail((384, 384), Image.Resampling.LANCZOS)
normal.save(RES / 'nav_account.webp', lossless=True, method=6)
report['navigation'] = {
    'running_source': 'art/mascots/01_01_running.webp',
    'normal_source': str(normal_path), 'normal_head_crop': list(crop),
    'running_output_size': list(running.size), 'head_output_size': list(normal.size)
}
(ASSETS / 'catalog.json').write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
for path in (ASSETS / 'background-sources.json', Path(__file__).with_name('background-build.json')):
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

# Remove only superseded photos created by the previous, rejected design.
for i in range(5):
    (ASSETS / f'backgrounds/landscape_{i + 1:02d}.webp').unlink(missing_ok=True)
(ASSETS / 'licenses/Unsplash.txt').unlink(missing_ok=True)

font_path = ROOT / 'android/app/src/main/assets/fonts/campus-sans.ttf'
sheet = Image.new('RGB', (1200, 950), '#EDF5FC')
draw = ImageDraw.Draw(sheet)
draw.text((35, 18), '华理跑步姬 · 淡蓝立体几何壁纸', font=ImageFont.truetype(str(font_path), 29), fill='#242E45')
draw.text((35, 61), '真实三维形体与柔和光影 · 主界面使用 · 开屏仍保留原插画', font=ImageFont.truetype(str(font_path), 17), fill='#69748A')
for i, row in enumerate(report['backgrounds']):
    pic = Image.open(ASSETS / row['asset']).resize((372, 744), Image.Resampling.LANCZOS)
    sheet.paste(pic, (24 + i * 396, 115))
    draw.text((36 + i * 396, 880), NAMES[i], font=ImageFont.truetype(str(font_path), 23), fill='#242E45')
sheet.save(ROOT / 'dist/geometry-wallpapers-v2.0.3-preview.jpg', quality=95)

icons = Image.new('RGBA', (640, 370), '#EAF1FA')
for i, (pic, label) in enumerate([(running, '模拟 · 跑步全身'), (normal, '账号 · 正常头像')]):
    pic = pic.copy()
    pic.thumbnail((230, 260), Image.Resampling.LANCZOS)
    icons.alpha_composite(pic, (i * 320 + (320 - pic.width) // 2, 20 + (260 - pic.height) // 2))
    ImageDraw.Draw(icons).text((i * 320 + 58, 310), label, font=ImageFont.truetype(str(font_path), 24), fill='#242E45')
icons.convert('RGB').save(ROOT / 'dist/navigation-characters-v2.0.3-preview.jpg', quality=94)
print('Prepared 3 original geometry wallpapers and 2 navigation characters.')
