"""Desktop design preview from the shipped assets; not an Android screenshot."""
from pathlib import Path
import json
from PIL import Image, ImageDraw, ImageFont, ImageOps

ROOT=Path(__file__).resolve().parents[2]
ASSETS=ROOT/'android/app/src/main/assets/art'
FONT=ROOT/'android/app/src/main/assets/fonts/campus-sans.ttf'
CAT=json.loads((ASSETS/'catalog.json').read_text(encoding='utf-8'))
INK=(36,46,69);MUTED=(105,116,138);ACCENT=(82,103,155);CANVAS=(245,246,251);PALE=(234,239,250)
S=2
def f(size):return ImageFont.truetype(str(FONT),round(size*S))
def box(im,rect,color,radius=18):ImageDraw.Draw(im).rounded_rectangle(tuple(round(v*S) for v in rect),round(radius*S),fill=color)
def text(im,xy,content,size=14,color=INK):ImageDraw.Draw(im).text(tuple(round(v*S) for v in xy),content,font=f(size),fill=color)
def wrap(im,xy,content,size,width,color):
    draw=ImageDraw.Draw(im);x,y=xy;line=''
    for char in content:
        if char=='\n' or draw.textlength(line+char,font=f(size))>width*S:
            text(im,(x,y),line,size,color);y+=size+6;line='' if char=='\n' else char
        else:line+=char
    if line:text(im,(x,y),line,size,color);y+=size+6
    return y
def paste_fit(im,source,rect):
    x,y,w,h=rect;pic=source.copy().convert('RGBA');pic.thumbnail((round(w*S),round(h*S)),Image.Resampling.LANCZOS)
    im.alpha_composite(pic,(round((x+w/2)*S-pic.width/2),round((y+h/2)*S-pic.height/2)))
def hero(im,x,y,width,headline,caption,running=False,scene=10,pair=3):
    h=184;wall=Image.open(ASSETS/CAT['wallpapers'][scene]).convert('RGBA');scale=max(width*S/wall.width,h*S/wall.height);wall=wall.resize((round(wall.width*scale),round(wall.height*scale)),Image.Resampling.LANCZOS)
    left=(wall.width-width*S)/2;top=(wall.height-h*S)*.2;wall=wall.crop((round(left),round(top),round(left+width*S),round(top+h*S)))
    shade=Image.new('RGBA',(width*S,h*S));d=ImageDraw.Draw(shade)
    stops=[(34,47,76,255),(40,52,79,235),(57,71,101,115)]
    for px in range(width*S):
        value=px/(width*S-1)*2;i=min(1,int(value));ratio=value-i;color=tuple(round(stops[i][k]*(1-ratio)+stops[i+1][k]*ratio) for k in range(4));d.line((px,0,px,h*S),fill=color)
    wall=Image.alpha_composite(wall,shade);mask=Image.new('L',wall.size);ImageDraw.Draw(mask).rounded_rectangle((0,0,wall.width,wall.height),24*S,fill=255);wall.putalpha(mask);im.alpha_composite(wall,(x*S,y*S))
    text(im,(x+18,y+22),'YOUR PROGRESS' if running else 'READY TO GO',9,(206,217,238))
    end=wrap(im,(x+18,y+47),headline,21,width-150,(255,255,255));wrap(im,(x+18,end+6),caption,12,width-150,(222,230,245))
    pose=CAT['pairs'][pair]['running' if running else 'normal'];paste_fit(im,Image.open(ASSETS/pose),(x+width-120,y+h-167,116,160))
def header(im,title,subtitle):
    head=Image.open(ROOT/'android/app/src/main/res/drawable-nodpi/brand_head.webp').convert('RGBA').resize((38*S,38*S),Image.Resampling.LANCZOS)
    mask=Image.new('L',head.size);ImageDraw.Draw(mask).rounded_rectangle((0,0,38*S,38*S),12*S,fill=255);head.putalpha(mask);im.alpha_composite(head,(22*S,18*S))
    text(im,(70,18),'华理跑步姬',16);text(im,(70,43),'CAMPUS COMPANION',9,MUTED);box(im,(267,24,338,51),PALE,14);text(im,(278,29),'奉贤校区',11,ACCENT)
    text(im,(22,79),title,24);text(im,(22,113),subtitle,12,MUTED)
def nav(im,selected):
    box(im,(0,716,360,784),'white',0)
    d=ImageDraw.Draw(im)
    for i,label in enumerate(['模拟','自动','记录','地图','账号']):
        x=12+i*67
        if i==selected:box(im,(x,724,x+65,776),PALE,16)
        color=ACCENT if i==selected else MUTED
        if i==1:d.ellipse(((x+25)*S,733*S,(x+42)*S,750*S),outline=color,width=2*S);d.line(((x+33)*S,736*S,(x+33)*S,742*S,(x+38)*S,745*S),fill=color,width=2*S)
        else:d.rounded_rectangle(((x+26)*S,733*S,(x+40)*S,751*S),radius=3*S,outline=color,width=2*S)
        text(im,(x+22,754),label,11,color)
def splash():
    im=Image.new('RGBA',(360*S,784*S),CANVAS+(255,));paste_fit(im,Image.open(ASSETS/CAT['wallpapers'][10]),(0,0,360,593));box(im,(0,598,360,784),'white',0)
    text(im,(116,616),'华理跑步姬',24);text(im,(95,654),'ECUST · CAMPUS COMPANION',9,ACCENT)
    wrap(im,(31,681),'tips：先热身，再出发，让身体慢慢进入节奏。',13,302,MUTED);box(im,(144,751,216,754),ACCENT,2);return im
def normal():
    im=Image.new('RGBA',(360*S,784*S),CANVAS+(255,));header(im,'模拟跑步','先预览，再开始你的模拟记录');hero(im,20,151,320,'规划下一段路线','三点规划 · 到时提交\n模拟不代表实际运动。')
    box(im,(20,351,340,481),'white',22)
    for x,v,label in [(36,'2.2 km','默认目标'),(138,'15 min','默认时长'),(246,'3 个点','必经点位')]:text(im,(x,366),v,18);text(im,(x,396),label,10,MUTED)
    box(im,(36,425,324,466),PALE,14);text(im,(136,437),'查看当前记录',14,ACCENT)
    box(im,(20,497,340,704),'white',22);text(im,(38,516),'01  起点与考核点',18)
    for x,label in [(38,'起点纬度'),(191,'起点经度')]:text(im,(x,554),label,11,MUTED);box(im,(x,576,x+130,623),CANVAS,12)
    box(im,(38,641,322,690),PALE,14);text(im,(116,656),'读取手机当前位置',14,ACCENT);nav(im,0);return im
def active():
    im=Image.new('RGBA',(360*S,784*S),CANVAS+(255,));header(im,'跑步记录','当前进度与服务器判定');hero(im,20,151,320,'模拟进行中','按自己的节奏，查看本次路线与剩余时间。',True)
    box(im,(20,351,340,698),'white',22);text(im,(38,371),'当前记录',18);text(im,(38,407),'模拟进行中',13,ACCENT)
    text(im,(38,444),'模拟里程 / km',12,MUTED);text(im,(198,444),'剩余时间',12,MUTED);text(im,(38,467),'1.32',36);text(im,(198,467),'06:00',36)
    box(im,(38,526,322,531),PALE,3);box(im,(38,526,209,531),ACCENT,3)
    box(im,(38,550,322,612),CANVAS,14);wrap(im,(51,562),'示例进度，仅用于设计预览\n不代表真实或已提交记录。',12,257,MUTED)
    box(im,(38,630,322,677),PALE,14);text(im,(140,644),'暂停本次模拟',14,ACCENT);nav(im,2);return im
def main():
    out=Image.new('RGBA',(1210*S,914*S),(233,237,247,255));text(out,(30,18),'华理跑步姬 · 美术主题设计预览',23);text(out,(30,50),'由实际内置素材绘制 · 非安卓手机截图 · 示例进度不创建记录',13,MUTED)
    for i,(label,panel) in enumerate([('随机插画开屏 · 3 秒',splash()),('普通状态 · 随机陪伴',normal()),('模拟中 · 对应跑步姿态',active())]):
        x=30+i*400;text(out,(x,82),label,15);out.alpha_composite(panel,(x*S,112*S))
    out.convert('RGB').save(ROOT/'dist/ecust-running-girl-v2.0.0-preview.jpg',quality=94)
    icon=Image.open(ROOT/'android/app/src/main/res/drawable-nodpi/launcher_head.webp').convert('RGBA').crop((72,72,360,360));icons=Image.new('RGBA',(960,370),(234,239,250,255));d=ImageDraw.Draw(icons)
    for i,kind in enumerate(['圆形桌面图标','圆角桌面图标','头部原裁切']):
        im=Image.new('RGBA',(260,260),'white');im.alpha_composite(icon.resize((260,260),Image.Resampling.LANCZOS));mask=Image.new('L',im.size);md=ImageDraw.Draw(mask)
        if i==0:md.ellipse((0,0,259,259),fill=255)
        elif i==1:md.rounded_rectangle((0,0,259,259),60,fill=255)
        else:im=Image.open(ROOT/'android/app/src/main/res/drawable-nodpi/brand_head.webp').convert('RGBA').resize((260,260),Image.Resampling.LANCZOS);md.rectangle((0,0,259,259),fill=255)
        im.putalpha(mask);icons.alpha_composite(im,(30+i*320,28));d.text((45+i*320,310),kind,font=ImageFont.truetype(str(FONT),23),fill=INK)
    icons.convert('RGB').save(ROOT/'dist/ecust-running-girl-icon-preview.jpg',quality=94)
    print('Design preview and head icon preview written (not Android screenshots).')
if __name__=='__main__':main()
