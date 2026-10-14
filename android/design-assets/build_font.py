"""Build a useful Chinese font subset under the requested APK resource budget.

Source: Google Fonts Noto Sans SC; SIL Open Font License 1.1.
The subset contains every GB2312 Chinese character, UI/map text, Latin and
punctuation. Extra GBK characters expand coverage up to the supplied budget.
No padding or unused random bytes are added.
"""
from pathlib import Path
import argparse
import io
import json
from fontTools.ttLib import TTFont
from fontTools import subset
from fontTools.varLib.instancer import instantiateVariableFont

ROOT = Path(__file__).resolve().parents[2]
SOURCE = Path(__file__).with_name("NotoSansSC-source.ttf")
STATIC = SOURCE.with_name("NotoSansSC-Regular-source.ttf")
DEST = ROOT / "android/app/src/main/assets/fonts/campus-sans.ttf"

def encoded_characters(encoding):
    chars=set()
    for high in range(0x81,0xFF):
        for low in range(0x40,0xFF):
            try:
                text=bytes([high,low]).decode(encoding)
                if len(text)==1:chars.add(ord(text))
            except UnicodeDecodeError:pass
    return chars

def main():
    parser=argparse.ArgumentParser();parser.add_argument("--target-bytes",type=int,default=3000000)
    args=parser.parse_args()
    if not STATIC.exists():
        font=instantiateVariableFont(TTFont(SOURCE),{"wght":400},inplace=True);font.save(STATIC)
    available=set(TTFont(STATIC).getBestCmap())
    required=set(range(0x20,0x100))|set(range(0x2000,0x2070))|set(range(0x3000,0x3040))|set(range(0xFF00,0xFFEF))
    required|=encoded_characters("gb2312")
    for folder in [ROOT/"android/app/src/main/java",ROOT/"android/app/src/main/res",ROOT/"android/app/src/main/assets"]:
        for path in folder.rglob("*"):
            if path.suffix in (".java",".xml",".json"):
                required|={ord(c) for c in path.read_text(encoding="utf-8")}
    required&=available
    extra=sorted((encoded_characters("gbk")&available)-required)
    def build(count):
        font=TTFont(STATIC);options=subset.Options();options.recalc_timestamp=False
        operation=subset.Subsetter(options=options);operation.populate(unicodes=required|set(extra[:count]));operation.subset(font)
        names={1:"Campus Sans",2:"Regular",3:"Campus Sans Regular 1.0",4:"Campus Sans Regular",6:"CampusSans-Regular",16:"Campus Sans",17:"Regular"}
        for record in font["name"].names:
            if record.nameID in names:record.string=names[record.nameID].encode(record.getEncoding(),errors="replace")
        font["head"].modified=font["head"].created
        out=io.BytesIO();font.save(out);return out.getvalue()
    baseline=build(0)
    if len(baseline)>args.target_bytes:raise ValueError("Budget too small to include all common Chinese and UI characters")
    low,high=0,len(extra);best=baseline;chosen=0
    while low<=high:
        middle=(low+high)//2;data=build(middle)
        if len(data)<=args.target_bytes:best=data;chosen=middle;low=middle+1
        else:high=middle-1
    DEST.parent.mkdir(parents=True,exist_ok=True);DEST.write_bytes(best)
    (DEST.parent/"OFL.txt").write_bytes(SOURCE.with_name("OFL-NotoSansSC.txt").read_bytes())
    report={"font":"Campus Sans (Noto Sans SC subset)","license":"OFL-1.1","characters":len(required)+chosen,
            "common_and_ui_characters":len(required),"additional_gbk_characters":chosen,"bytes":len(best),"target_bytes":args.target_bytes}
    SOURCE.with_name("font-build.json").write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
    print(json.dumps(report,ensure_ascii=True))

if __name__=="__main__":main()
