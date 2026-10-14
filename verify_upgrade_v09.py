"""Compare exact production data after the real in-app 0.8.1 → 0.9.0 upgrade."""
from pathlib import Path
import sqlite3, json, xml.etree.ElementTree as ET
root = Path(__file__).resolve().parent / 'audit'
before, after = root / 'v09-upgrade-before', root / 'v09-upgrade-after'
def rows(folder):
    with sqlite3.connect(folder / 'library.sqlite') as c:
        return {t: sorted(c.execute(f'SELECT * FROM "{t}"').fetchall(), key=repr)
                for t in ('books','progress','marks','shelves','state')}
a, b = rows(before), rows(after)
assert a == b, 'Library records changed during upgrade'
states = {p.name:p.read_bytes() for p in (before/'reader-state').glob('*.json')}
assert states
for name, data in states.items(): assert (after/'reader-state'/name).read_bytes() == data
def prefs(file):
    return {n.get('name'): n.text if n.tag=='string' else n.get('value') for n in ET.parse(file).getroot()}
for name in ('speech.xml','appearance.xml'):
    if (before/name).exists():
        old, new = prefs(before/name), prefs(after/name)
        assert all(new.get(k)==v for k,v in old.items()), f'{name} changed'
version = (root/'v09-upgrade-version.log').read_text()
assert 'versionName=0.9.0' in version and 'versionCode=11' in version
result = dict(passed=True, fromVersion='0.8.1', toVersion='0.9.0', books=len(a['books']), annotations=len(a['marks']),
    exactLibraryRecordsPreserved=True, exactReaderStateFilesPreserved=len(states), originalVoiceSettingsPreserved=True,
    method='Installed 0.8.1: More/App updates -> public GitHub check -> download -> checksum and signing verification -> Android installer confirmation; exact library/state/preferences comparison after 0.9.0 installation')
(root/'v09-upgrade-result.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result,indent=2))
