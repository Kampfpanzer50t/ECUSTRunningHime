"""Export this account's local session for import into the Android app."""
import argparse
from datetime import datetime
import json
from pathlib import Path

def export(auth_path, capture_path, run_path, destination):
    auth=json.loads(auth_path.read_text(encoding='utf-8-sig'))
    if auth.get('host')!='run.ecust.edu.cn':raise ValueError('Host mismatch')
    headers={k:v for k,v in auth.get('headers',{}).items() if k.lower() in {'x-session-id','authorization','user-agent','referer'} and v}
    if not any(k.lower()=='x-session-id' and v!='1111' for k,v in headers.items()):raise ValueError('Missing valid session')
    value={'format':'huati-mobile-session','version':1,'host':auth['host'],'headers':headers,'exported_at':datetime.now().astimezone().isoformat()}
    if capture_path.exists():
        for line in reversed(capture_path.read_text(encoding='utf-8').splitlines()):
            record=json.loads(line)
            if record.get('request',{}).get('path')=='/xcxtapi/activity/randrunInfo':
                query=record['request'].get('query',{})
                if 'lat' in query and 'lng' in query:value['location']={key:float(query[key]) for key in ('lat','lng')}
                break
    if run_path.exists():
        run=json.loads(run_path.read_text(encoding='utf-8'))
        if run.get('status')=='finished' and run.get('record_id'):value['recent_record_id']=run['record_id']
    destination.parent.mkdir(parents=True,exist_ok=True)
    destination.write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf-8')
    print('Export saved: '+str(destination.resolve()))
    print('This file contains your session. Import it into your own phone; do not share it.')

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--auth',type=Path,default=Path('private/captured-auth.json'))
    parser.add_argument('--capture',type=Path,default=Path('private/capture.jsonl'))
    parser.add_argument('--run',type=Path,default=Path('private/current-session.json'))
    parser.add_argument('--output',type=Path,default=Path('mobile-export/huati-session.json'))
    args=parser.parse_args();export(args.auth,args.capture,args.run,args.output)
