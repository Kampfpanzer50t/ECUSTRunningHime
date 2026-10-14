"""Make two browser-uploadable source bundles plus the macOS build workflow."""
from pathlib import Path
import hashlib
import json
import stat
import zipfile

ROOT = Path(__file__).resolve().parents[2]
IOS = ROOT / 'ios'
OUT = ROOT / 'dist/ios-cloud-upload'
EXCLUDED = {'Exports', 'private', '__pycache__', 'xcuserdata', 'node_modules', '.git', '.DS_Store'}

def main():
    OUT.mkdir(parents=True, exist_ok=True)
    packages = {}
    for group in ('project', 'art'):
        target = OUT / f'ios-{group}-v2.0.6.zip'
        with zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for source in sorted(IOS.rglob('*')):
                relative = source.relative_to(IOS)
                if not source.is_file() or any(part in EXCLUDED for part in relative.parts):
                    continue
                is_art = relative.as_posix().startswith('Huati/Content/Art/')
                if is_art != (group == 'art'):
                    continue
                assert source.suffix.lower() not in {'.p12', '.pem', '.key', '.mobileprovision', '.ipa', '.apk'}
                assert source.name not in {'huati-session.json', 'huati-companion-pair.json', 'key.bin', 'state.bin', 'executor.lock'}
                item = zipfile.ZipInfo(relative.as_posix())
                item.create_system = 3
                item.external_attr = (stat.S_IFREG | (0o755 if source.suffix == '.command' else 0o644)) << 16
                item.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(item, source.read_bytes())
        assert target.stat().st_size < 25 * 1024 * 1024, 'GitHub browser upload limit exceeded'
        with zipfile.ZipFile(target) as archive:
            assert archive.testzip() is None
            for name in archive.namelist():
                assert hashlib.sha256(archive.read(name)).digest() == hashlib.sha256((IOS / name).read_bytes()).digest()
        packages[target.name] = {'bytes': target.stat().st_size, 'sha256': hashlib.sha256(target.read_bytes()).hexdigest()}

    workflow = (IOS / '.github/workflows/build-ios.yml').read_text(encoding='utf-8')
    source_step = '''          if [[ -f ios-project-v2.0.6.zip && -f ios-art-v2.0.6.zip ]]; then
            mkdir -p .build/ios
            python3 - <<'PY'
          from pathlib import Path
          import hashlib, zipfile
          root = Path('.build/ios').resolve()
          expected = __HASHES__
          for name, digest in expected.items():
              assert hashlib.sha256(Path(name).read_bytes()).hexdigest() == digest, 'Source bundle checksum mismatch'
              with zipfile.ZipFile(name) as archive:
                  for item in archive.infolist():
                      destination = (root / item.filename).resolve()
                      assert destination.is_relative_to(root), 'Invalid archive path'
                  archive.extractall(root)
          PY
            printf 'APP_DIR=.build/ios\\n' >> "$GITHUB_ENV"
          elif [[ -f ios/Huati.xcodeproj/project.pbxproj ]]; then
'''
    source_step = source_step.replace('__HASHES__', repr({name: data['sha256'] for name, data in packages.items()}))
    workflow = workflow.replace('          if [[ -f ios/Huati.xcodeproj/project.pbxproj ]]; then\n', source_step)
    workflow_path = OUT / '.github/workflows/build-ios.yml'
    workflow_path.parent.mkdir(parents=True, exist_ok=True)
    workflow_path.write_text(workflow, encoding='utf-8', newline='\n')
    (OUT / 'upload-manifest.json').write_text(json.dumps(packages, indent=2), encoding='utf-8')
    print(json.dumps({'folder': str(OUT), 'packages': packages, 'workflow': str(workflow_path)}))

if __name__ == '__main__':
    main()
