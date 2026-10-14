"""Fetch the official JDK in verified byte ranges for slow connections."""
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
import subprocess
import zipfile

ROOT=Path(__file__).resolve().parent/'.toolchain'
URL='https://download.visualstudio.microsoft.com/download/pr/73ca8d57-4705-435a-8d56-ed9624332745/f2eee2dc972b278e355547d50e0e1d8d/microsoft-jdk-17.0.16-windows-x64.zip'
SIZE=186591472
PART=4*1024*1024

def fetch(start):
    end=min(SIZE-1,start+PART-1);target=ROOT/'jdk-parts'/str(start)
    if target.exists() and target.stat().st_size==end-start+1:return target
    result=subprocess.run(['curl.exe','--silent','--show-error','--fail','--location','--retry','2','--max-time','120','--range',f'{start}-{end}','--output',str(target),URL],capture_output=True,text=True)
    if result.returncode or target.stat().st_size!=end-start+1:
        raise RuntimeError(f'JDK range failed: {start}: {result.stderr[:200]}')
    return target

if __name__=='__main__':
    (ROOT/'jdk-parts').mkdir(parents=True,exist_ok=True)
    ranges=list(range(0,SIZE,PART))
    with ThreadPoolExecutor(max_workers=10) as pool:
        futures=[pool.submit(fetch,start) for start in ranges]
        for index,future in enumerate(as_completed(futures),1):future.result();print(f'JDK {index}/{len(ranges)}',flush=True)
    archive=ROOT/'jdk-ranged.zip'
    with archive.open('wb') as output:
        for start in ranges:output.write((ROOT/'jdk-parts'/str(start)).read_bytes())
    with zipfile.ZipFile(archive) as source:
        if source.testzip():raise RuntimeError('JDK archive integrity failed')
        destination=ROOT/'jdk-ranged';destination.mkdir(exist_ok=True)
        for info in source.infolist():
            if not (destination/info.filename).resolve().is_relative_to(destination.resolve()):raise RuntimeError('Unexpected archive path')
        source.extractall(destination)
    print('JDK ready',flush=True)
