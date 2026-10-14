"""Prepare supplied artwork without redrawing it. Originals remain untouched."""
from pathlib import Path
import argparse
import hashlib
import json
from PIL import Image, ImageDraw, ImageChops

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / 'android/app/src/main/assets/art'
RES = ROOT / 'android/app/src/main/res'

def transparent_background(source):
    im = source.convert('RGB')
    # Remove only near-white pixels connected to the outside. Enclosed white
    # clothing, skin and hair remain opaque, unlike chroma-keying all whites.
    channels = im.split()
    minimum = ImageChops.darker(ImageChops.darker(channels[0], channels[1]), channels[2])
    mask = minimum.point(lambda value: 255 if value >= 247 else 0)
    ImageDraw.floodfill(mask, (0, 0), 128)
    alpha = mask.point(lambda value: 0 if value == 128 else 255)
    out = im.convert('RGBA'); out.putalpha(alpha)
    bounds = alpha.getbbox()
    if bounds:
        margin = round(max(im.size)*0.02)
        bounds = (max(0,bounds[0]-margin), max(0,bounds[1]-margin), min(im.width,bounds[2]+margin), min(im.height,bounds[3]+margin))
        out = out.crop(bounds)
    return out

def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()

def prepare_wallpapers(pictures):
    files=sorted(path for path in pictures.iterdir() if path.is_file() and path.suffix.lower() in ('.png','.jpg','.jpeg','.webp'))
    if not files:raise ValueError('No opening illustrations in the supplied folder')
    folder=ASSETS/'wallpapers';folder.mkdir(parents=True,exist_ok=True)
    assets=[];sources=[]
    for i,path in enumerate(files):
        name=f'wallpapers/scene_{i+1:02d}.webp';im=Image.open(path).convert('RGB')
        im.save(ASSETS/name,format='WEBP',lossless=True,method=6)
        assets.append(name)
        sources.append({'source':str(path),'sha256':digest(path),'asset':name,'size':list(im.size)})
    # Only clean previously generated scene files in this application asset folder.
    for old in folder.glob('scene_*.webp'):
        if f'wallpapers/{old.name}' not in assets:old.unlink()
    return assets,sources

def make_icon(characters):
    icon_path=characters/'01-原图截取6张/04-尴尬-跑步.png'
    crop=(80,100,286,291)
    clipped=Image.open(icon_path).convert('RGB').crop(crop)
    head=Image.new('RGB',(206,206),'white');head.paste(clipped,(0,(206-clipped.height)//2))
    (RES/'drawable-nodpi').mkdir(parents=True,exist_ok=True)
    head.resize((512,512),Image.Resampling.LANCZOS).save(RES/'drawable-nodpi/brand_head.webp',lossless=True,method=6)
    foreground=Image.new('RGBA',(432,432),(0,0,0,0))
    foreground.paste(head.resize((272,272),Image.Resampling.LANCZOS),(80,80))
    foreground.save(RES/'drawable-nodpi/launcher_head.webp',lossless=True,method=6)
    mono=Image.new('RGBA',(432,432),(0,0,0,0));draw=ImageDraw.Draw(mono)
    draw.ellipse((132,140,300,308),fill='white');draw.polygon([(138,170),(131,120),(182,148)],fill='white');draw.polygon([(250,145),(303,116),(296,177)],fill='white')
    mono.save(RES/'drawable-nodpi/launcher_mono.png')
    return icon_path,crop

def main():
    p=argparse.ArgumentParser()
    p.add_argument('--pictures', default='E:/picture.ai.ecust.assistant')
    p.add_argument('--characters', default='E:/ai.q.ecust.as')
    p.add_argument('--icons-only',action='store_true')
    p.add_argument('--wallpapers-only',action='store_true')
    a=p.parse_args(); pictures=Path(a.pictures); characters=Path(a.characters)
    if a.icons_only:
        icon,crop=make_icon(characters)
        report_path=ROOT/'android/design-assets/art-build.json'
        if report_path.exists():
            report=json.loads(report_path.read_text(encoding='utf-8'));report['head_crop_pixels']=crop;report_path.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
        print(json.dumps({'icon_source':str(icon),'head_crop_pixels':crop},ensure_ascii=True));return
    if a.wallpapers_only:
        catalog_path=ASSETS/'catalog.json'
        catalog=json.loads(catalog_path.read_text(encoding='utf-8'))
        catalog['wallpapers'],sources=prepare_wallpapers(pictures)
        catalog_path.write_text(json.dumps(catalog,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        report_path=ROOT/'android/design-assets/art-build.json'
        report=json.loads(report_path.read_text(encoding='utf-8'))
        report['sources']=sources+[entry for entry in report['sources'] if not entry['asset'].startswith('wallpapers/')]
        report['wallpaper_count']=len(sources)
        report['wallpaper_source_folder']=str(pictures)
        report['asset_bytes']=sum(path.stat().st_size for path in ASSETS.rglob('*') if path.is_file())
        report_path.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        print(json.dumps({'wallpaper_count':len(sources),'wallpaper_source_folder':str(pictures)},ensure_ascii=True));return
    for folder in ['wallpapers','mascots']:(ASSETS/folder).mkdir(parents=True,exist_ok=True)
    previous=json.loads((ASSETS/'catalog.json').read_text(encoding='utf-8')) if (ASSETS/'catalog.json').exists() else {}
    catalog={'wallpapers':[],'pairs':[]}; sources=[]
    if previous.get('backgrounds'):catalog['backgrounds']=previous['backgrounds']
    catalog['wallpapers'],sources=prepare_wallpapers(pictures)
    outfits=['原图','运动服','女仆装','水手服','汉服','侦探装']
    folders=sorted(path for path in characters.iterdir() if path.is_dir() and path.name[:2].isdigit())
    for outfit_index,folder in enumerate(folders):
        normals=sorted(folder.glob('*正常.png')); runs=sorted(folder.glob('*跑步.png'))
        assert len(normals)==len(runs)==3, folder
        for mood,(normal,running) in enumerate(zip(normals,runs)):
            pair={'id':f'{outfit_index+1:02d}_{mood+1:02d}','outfit':outfits[outfit_index]}
            for role,path in [('normal',normal),('running',running)]:
                im=transparent_background(Image.open(path));im.thumbnail((768,768),Image.Resampling.LANCZOS)
                name=f'mascots/{pair["id"]}_{role}.webp';im.save(ASSETS/name,format='WEBP',lossless=True,method=6)
                pair[role]=name;sources.append({'source':str(path),'sha256':digest(path),'asset':name,'size':list(im.size)})
            catalog['pairs'].append(pair)
    assert catalog['wallpapers'] and len(catalog['pairs'])==18
    icon_path,crop=make_icon(characters)
    catalog_path=ASSETS/'catalog.json';catalog_path.write_text(json.dumps(catalog,ensure_ascii=False,indent=2),encoding='utf-8')
    report={'wallpaper_count':len(catalog['wallpapers']),'mascot_count':len(catalog['pairs'])*2,'icon_source':str(icon_path),'head_crop_pixels':crop,'asset_bytes':sum(path.stat().st_size for path in ASSETS.rglob('*') if path.is_file()),'sources':sources}
    (ROOT/'android/design-assets/art-build.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({k:v for k,v in report.items() if k!='sources'},ensure_ascii=True))

if __name__=='__main__':main()
