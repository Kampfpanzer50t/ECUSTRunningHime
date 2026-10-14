"""Validate an actual device build. This does not certify code signing or device behavior."""
import json
import plistlib
import sys
import zipfile
from pathlib import Path

def verify(file):
    with zipfile.ZipFile(file) as archive:
        assert archive.testzip() is None, 'ZIP integrity failed'
        info=plistlib.loads(archive.read('Payload/Huati.app/Info.plist'))
        assert info['CFBundleInfoDictionaryVersion']=='6.0'
        assert info['CFBundleIdentifier']=='cn.huati.assistant.ios'
        assert info['CFBundlePackageType']=='APPL'
        assert info['CFBundleSupportedPlatforms']==['iPhoneOS'], 'Simulator build cannot be installed'
        assert info['CFBundleShortVersionString']=='2.0.6'
        assert info['MinimumOSVersion']=='16.0'
        executable=archive.read('Payload/Huati.app/'+info['CFBundleExecutable'])
        assert executable[:4]==bytes.fromhex('cffaedfe'), 'Expected 64-bit Mach-O'
        assert int.from_bytes(executable[4:8],'little')==0x0100000c, 'Expected ARM64 iPhone binary'
        assert 'Payload/Huati.app/Content/Core/run-core.js' in archive.namelist()
        assert not any(n.startswith('Payload/Huati.app/Resources/') for n in archive.namelist()), 'Reserved Resources folder breaks flat iOS bundle detection'
        assert len([n for n in archive.namelist() if '/Art/wallpapers/' in n and n.endswith('.jpg')])==19
        return {'artifact':Path(file).name,'version':info['CFBundleShortVersionString'],'minimum_ios':info['MinimumOSVersion'],'architecture':'arm64','device_build':True,'signature_validated':False,'iphone_tested':False}

if __name__=='__main__':
    print(json.dumps(verify(sys.argv[1]),ensure_ascii=False))
