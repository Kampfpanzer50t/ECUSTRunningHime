"""Download selected Unsplash landscapes for the app's offline background assets."""
from pathlib import Path
import hashlib
import json
import urllib.request
from PIL import Image, ImageDraw

ROOT=Path(__file__).resolve().parents[2]
DEST=ROOT/'android/design-assets/third-party/unsplash-landscapes'
PHOTOS=[
    ('lake','photo-1501785888041-af3ef285b470'),
    ('reflection','photo-1470770841072-f978cf4d019e'),
    ('forest','photo-1441974231531-c6227db76b6e'),
    ('waterfall','photo-1433086966358-54859d0ed716'),
    ('sunrise','photo-1470252649378-9c29740c9fa8'),
]
DEST.mkdir(parents=True,exist_ok=True)
sources=[]
for index,(name,photo) in enumerate(PHOTOS):
    url=f'https://images.unsplash.com/{photo}?auto=format&fit=crop&w=1920&q=90'
    path=DEST/f'{name}.jpg'
    if not path.exists():
        with urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'Mozilla/5.0'}),timeout=30) as response:
            path.write_bytes(response.read())
    with Image.open(path) as im:
        im.load()
        sources.append({'name':name,'asset_id':photo,'url':url,'license':'Unsplash License',
                        'license_url':'https://unsplash.com/license',
                        'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'size':list(im.size)})
    print(name,photo)
(DEST/'sources.json').write_text(json.dumps(sources,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
sheet=Image.new('RGB',(1100,450),(245,246,251));draw=ImageDraw.Draw(sheet)
for i,(name,photo) in enumerate(PHOTOS):
    image=Image.open(DEST/f'{name}.jpg').convert('RGB');image.thumbnail((210,390))
    sheet.paste(image,(i*220+(210-image.width)//2,20));draw.text((i*220+12,420),name,fill=(36,46,69))
sheet.save(ROOT/'tmp/scenic-wallpaper-candidates.jpg')
