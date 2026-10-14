"""Download the local Android build tools without changing system settings."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import hashlib
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent / '.toolchain'
PACKAGES = {
    'jdk': 'https://aka.ms/download-jdk/microsoft-jdk-17.0.16-windows-x64.zip',
    'gradle': 'https://services.gradle.org/distributions/gradle-8.9-bin.zip',
    'android': 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip',
}

def prepare(item):
    name, url = item
    archive = ROOT / (name + '.zip')
    destination = ROOT / name
    marker = destination / '.complete'
    if marker.exists():
        print(name + ': ready', flush=True)
        return
    ROOT.mkdir(exist_ok=True)
    if not archive.exists():
        part = archive.with_suffix('.partial')
        print(name + ': downloading', flush=True)
        with urllib.request.urlopen(url, timeout=90) as response, part.open('wb') as output:
            while chunk := response.read(1024 * 1024):
                output.write(chunk)
        part.replace(archive)
    if name == 'gradle':
        with urllib.request.urlopen(url + '.sha256', timeout=30) as response:
            expected = response.read().decode().strip()
        if hashlib.file_digest(archive.open('rb'), 'sha256').hexdigest() != expected:
            raise RuntimeError('Gradle SHA-256 mismatch')
    destination.mkdir(exist_ok=True)
    with zipfile.ZipFile(archive) as source:
        for member in source.infolist():
            resolved = (destination / member.filename).resolve()
            if not resolved.is_relative_to(destination.resolve()):
                raise RuntimeError('Unexpected archive path')
        source.extractall(destination)
    marker.write_text('ready', encoding='utf-8')
    print(name + ': ready', flush=True)

if __name__ == '__main__':
    with ThreadPoolExecutor(max_workers=3) as pool:
        list(pool.map(prepare, PACKAGES.items()))
