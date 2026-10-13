from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
import urllib.request
import json
import hashlib
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
TOOLS = ROOT / 'toolchain'
REF = ROOT / 'reference'

def fetch(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'Folio-Build'}), timeout=90)

release = json.loads((REF / 'jdk-release.txt').read_text())
jdk = next(a for a in release['assets'] if 'jdk_x64_windows_hotspot' in a['name'] and a['name'].endswith('.zip'))
repo = ET.parse(REF / 'sdk-repository.txt').getroot()
package = next(p for p in repo.findall('{*}remotePackage') if p.get('path') == 'cmdline-tools;latest')
archive = next(a for a in package.findall('.//{*}archive') if a.find('{*}host-os').text == 'windows')
cmdline_name = archive.find('.//{*}url').text
cmdline_hash = archive.find('.//{*}checksum').text
tasks = [
    ('jdk', jdk['browser_download_url'], None),
    ('cmdline', 'https://dl.google.com/android/repository/' + cmdline_name, ('sha1', cmdline_hash)),
    ('gradle', 'https://services.gradle.org/distributions/gradle-8.11.1-bin.zip', None),
    ('readium', 'https://api.github.com/repos/readium/kotlin-toolkit/zipball/3.1.2', None),
]

def download(task):
    name, url, checksum = task
    path = TOOLS / (name + '.zip')
    if not path.exists():
        with fetch(url) as response, path.open('wb') as out:
            while chunk := response.read(1024 * 1024):
                out.write(chunk)
    if name == 'gradle':
        checksum = ('sha256', fetch(url + '.sha256').read().decode().strip())
    if name == 'jdk':
        check_url = next(a['browser_download_url'] for a in release['assets'] if a['name'] == jdk['name'] + '.sha256.txt')
        checksum = ('sha256', fetch(check_url).read().decode().split()[0])
    if checksum:
        algo, expected = checksum
        digest = hashlib.new(algo)
        with path.open('rb') as input_file:
            while chunk := input_file.read(1024 * 1024):
                digest.update(chunk)
        actual = digest.hexdigest()
        assert expected.lower() == actual, name + ' checksum mismatch'
    destination = REF / 'readium' if name == 'readium' else TOOLS / name
    destination.mkdir(exist_ok=True)
    with zipfile.ZipFile(path) as z:
        z.extractall(destination)
    print(name, 'ready', path.stat().st_size, flush=True)

with ThreadPoolExecutor(max_workers=4) as pool:
    for result in pool.map(download, tasks):
        pass
