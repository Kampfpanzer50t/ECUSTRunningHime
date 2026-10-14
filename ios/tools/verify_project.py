"""Host checks. This does not replace Xcode compilation or an iPhone device test."""
from pathlib import Path
import hashlib
import json
import os
import plistlib
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
IOS = ROOT / 'ios'
sys.path.insert(0, str(ROOT / '.toolchain/ios-validation'))
from tree_sitter import Language, Parser
import tree_sitter_swift
import tree_sitter_bash
from pbxproj import XcodeProject
from PIL import Image, ImageChops

def native_input_digest():
    """Only retain cloud results while all compiled app and test inputs match."""
    digest=hashlib.sha256()
    for folder in ('Huati','HuatiTests','Huati.xcodeproj'):
        for source in sorted((IOS/folder).rglob('*')):
            if not source.is_file() or 'xcuserdata' in source.parts:continue
            digest.update(source.relative_to(IOS).as_posix().encode('utf-8')+b'\0')
            digest.update(hashlib.sha256(source.read_bytes()).digest())
    return digest.hexdigest()

def main():
    parser=Parser(Language(tree_sitter_swift.language()))
    issues=[]
    sources=sorted((IOS/'Huati').rglob('*.swift'))+sorted((IOS/'HuatiTests').rglob('*.swift'))
    for source in sources:
        raw=source.read_bytes();tree=parser.parse(raw)
        def visit(node):
            if node.type=='ERROR' or node.is_missing:issues.append({'file':str(source.relative_to(IOS)), 'line':node.start_point.row+1, 'fragment':raw[node.start_byte:node.end_byte].decode('utf-8','replace')[:180]})
            for child in node.children:visit(child)
        visit(tree.root_node)
    if issues:
        print(json.dumps({'swift_parse_errors':issues},ensure_ascii=False,indent=2));return 1
    shell_parser=Parser(Language(tree_sitter_bash.language()))
    for script in IOS.glob('*.command'):
        assert not shell_parser.parse(script.read_bytes()).root_node.has_error,script
    project=XcodeProject.load(str(IOS/'Huati.xcodeproj/project.pbxproj'))
    assert project is not None
    refs=project.objects.get_objects_in_section('PBXFileReference')
    for ref in refs:
        if ref.sourceTree=='<group>':assert (IOS/ref.path).exists(),ref.path
    schemes=list((IOS/'Huati.xcodeproj').rglob('*.xcscheme'))
    assert len(schemes)==1
    for path in schemes+list((IOS/'Huati.xcodeproj').rglob('*.xcworkspacedata'))+[IOS/'Huati/LaunchScreen.storyboard']:ET.parse(path)
    info=plistlib.loads((IOS/'Huati/Info.plist').read_bytes())
    assert info['CFBundleInfoDictionaryVersion']=='6.0'
    assert info['CFBundleIdentifier']=='$(PRODUCT_BUNDLE_IDENTIFIER)'
    assert set(info['BGTaskSchedulerPermittedIdentifiers'])=={'cn.huati.assistant.ios.run','cn.huati.assistant.ios.probe'}
    assert set(info['UIBackgroundModes'])=={'processing','fetch'}
    assert 'NSLocationAlwaysAndWhenInUseUsageDescription' not in info
    privacy=plistlib.loads((IOS/'Huati/PrivacyInfo.xcprivacy').read_bytes())
    assert not privacy['NSPrivacyTracking']
    assets=json.loads((IOS/'asset-manifest.json').read_text(encoding='utf-8'))
    for item in assets:
        output=IOS/item['output'];assert hashlib.sha256(output.read_bytes()).hexdigest()==item['sha256']
        Image.open(output).verify()
        if output.suffix=='.png':
            source=Image.open(ROOT/item['source']).convert('RGBA');converted=Image.open(output).convert('RGBA')
            assert source.size==converted.size and not ImageChops.difference(source,converted).getbbox(),output
    icons=json.loads((IOS/'Huati/Assets.xcassets/AppIcon.appiconset/Contents.json').read_text())
    for icon in icons['images']:
        image=Image.open(IOS/'Huati/Assets.xcassets/AppIcon.appiconset'/icon['filename'])
        assert image.mode=='RGB' and image.width==image.height
    assert len(list((IOS/'Huati/Content/Art/wallpapers').glob('*.jpg')))==19
    assert len(list((IOS/'Huati/Content/Art/mascots').glob('*.png')))==36
    assert len(list((IOS/'Huati/Content/Art/backgrounds').glob('*.jpg')))==3
    assert (IOS/'Huati/Core/run-core.js').read_bytes()==(IOS/'Huati/Content/Core/run-core.js').read_bytes()
    assert not (IOS/'Huati/Resources').exists(), 'Use Content to preserve flat iOS bundle detection'
    results=[]
    for timezone in ['UTC','Asia/Shanghai','America/Los_Angeles']:
        env={**os.environ,'TZ':timezone}
        process=subprocess.run(['node','--test','--test-reporter=tap',str(IOS/'Tests/core.test.cjs'),str(IOS/'Tests/companion.test.cjs')],capture_output=True,text=True,encoding='utf-8',env=env)
        results.append({'timezone':timezone,'exit_code':process.returncode,'output':process.stdout+process.stderr})
        if process.returncode:print(results[-1]['output']);return 1
    test_count=int(re.search(r'# tests (\d+)',results[0]['output']).group(1))
    for result in results:assert int(re.search(r'# pass (\d+)',result['output']).group(1))==test_count
    for config in project.objects.get_objects_in_section('XCBuildConfiguration'):
        settings=config.buildSettings
        if settings.get('IPHONEOS_DEPLOYMENT_TARGET',None):assert settings['IPHONEOS_DEPLOYMENT_TARGET']=='16.0'
    for script in IOS.rglob('*.py'):compile(script.read_text(encoding='utf-8'),str(script),'exec')
    for script in IOS.rglob('*.cjs'):
        check=subprocess.run(['node','--check',str(script)],capture_output=True,text=True)
        assert check.returncode==0,check.stderr
    report={'app':'华理跑步姬','version':'2.0.6','platform':'iOS','minimum_ios':'16.0','supported_phones':'iPhone 8 / 8 Plus / X and newer on iOS 16+','swift_files_parsed':len(sources),'swift_syntax_parser':'tree-sitter-swift; not Swift type checking','xcode_project_parsed':True,'mac_command_scripts_parsed':len(list(IOS.glob('*.command'))),'asset_files_verified':len(assets),'core_and_companion_tests':test_count,'test_timezones':['UTC','Asia/Shanghai','America/Los_Angeles'],'local_tests_passed':True,'native_xctest_run':False,'xcode_compiled':False,'unsigned_ipa_produced':False,'signed_ipa_produced':False,'iphone_device_test':False,'live_account_requests':False,'companion_deployed':False,'cloud_workflow_run':False,'limitations':['Windows host cannot compile/link/sign iOS app; supplied macOS cloud workflow must run before an IPA exists.','iOS background execution remains opportunistic. The optional companion requires the user computer to stay online and awake.']}
    cloud_file=IOS/'cloud-verification.json'
    if cloud_file.exists():
        cloud=json.loads(cloud_file.read_text(encoding='utf-8'))
        if cloud.get('native_input_sha256')==native_input_digest():
            for key in ('native_xctest_run','native_xctest_count','xcode_compiled','unsigned_ipa_produced','cloud_workflow_run','cloud_run_url','ipa_sha256'):
                if key in cloud:report[key]=cloud[key]
            report['limitations']=['The IPA needs the user\'s valid Apple signing before installation; no physical iPhone was tested.','iOS background execution remains opportunistic. The optional companion requires the user computer to stay online and awake.']
        else:
            report['cloud_evidence_matches_current_inputs']=False
    (IOS/'verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    (IOS/'core-test-results.txt').write_text('\n\n'.join('TZ='+r['timezone']+'\n'+r['output'] for r in results),encoding='utf-8')
    print(json.dumps(report,ensure_ascii=False));return 0

if __name__=='__main__':raise SystemExit(main())
