"""Collect runtime credits and embedded license texts from resolved artifacts."""
from pathlib import Path
import io
import zipfile
import xml.etree.ElementTree as ET
import urllib.request

root = Path(__file__).resolve().parent
cache = root / 'toolchain/gradle-user/caches/modules-2/files-2.1'
texts = {}
credits = []
def collect(data, label):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        for name in archive.namelist():
            leaf = name.rsplit('/', 1)[-1].lower()
            if leaf.startswith(('license', 'notice', 'copying')) and not name.endswith('/'):
                text = archive.read(name).decode('utf-8', errors='replace').strip()
                if text: texts.setdefault(text, []).append(label + ' / ' + name)
            if name == 'classes.jar': collect(archive.read(name), label)

for coordinate in (root / 'runtime-dependencies.txt').read_text().splitlines():
    group, artifact, version = coordinate.split(':')
    location = cache / group / artifact / version
    poms = list(location.glob('*/*.pom'))
    licenses = []
    if poms:
        pom = ET.parse(poms[0]).getroot()
        licenses = [(l.findtext('{*}name', ''), l.findtext('{*}url', '')) for l in pom.findall('.//{*}license')]
    if not licenses and group == 'com.google.guava': licenses = [('Apache License 2.0', 'https://www.apache.org/licenses/LICENSE-2.0')]
    credits.append(coordinate + '\n' + '\n'.join(f'  {name}: {url}' for name, url in licenses))
    for extension in ('jar', 'aar'):
        for artifact_file in location.glob(f'*/*.{extension}'):
            collect(artifact_file.read_bytes(), coordinate)

apache = urllib.request.urlopen('https://www.apache.org/licenses/LICENSE-2.0.txt', timeout=60).read().decode()
jsoup = urllib.request.urlopen('https://raw.githubusercontent.com/jhy/jsoup/jsoup-1.18.1/LICENSE', timeout=60).read().decode()
body = 'FOLIO THIRD-PARTY NOTICES\n\nRuntime dependencies and licenses\n\n' + '\n\n'.join(credits)
body += '\n\nAPACHE LICENSE 2.0\n\n' + apache + '\n\nJSOUP LICENSE\n\n' + jsoup
body += '\n\nREADIUM LICENSE\n\n' + (root / 'app/src/main/assets/NOTICE_Readium.txt').read_text()
for text, labels in texts.items(): body += '\n\n' + '\n'.join(labels) + '\n\n' + text
(root / 'app/src/main/assets/THIRD_PARTY_NOTICES.txt').write_text(body, encoding='utf-8')
print(f'Collected {len(credits)} runtime dependency credits and {len(texts)} embedded notices.')
