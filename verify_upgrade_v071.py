from pathlib import Path
import sqlite3, json, hashlib, xml.etree.ElementTree as ET
root = Path(__file__).resolve().parent / "audit"
before = root / "v071-upgrade-before"
after = root / "v071-upgrade-after"
def database(path):
    with sqlite3.connect(path) as c:
        return {table:sorted(c.execute(f'SELECT * FROM "{table}"').fetchall(), key=repr) for table in ("books","progress","marks","shelves","state")}
a = database(before / "library.sqlite")
b = database(after / "library.sqlite")
assert a == b, "Library records changed during signed upgrade"
oldstates = {p.name:p.read_bytes() for p in (before / "reader-state").glob("*.json")}
assert oldstates
for name,contents in oldstates.items(): assert (after / "reader-state" / name).read_bytes() == contents

def preferences(path):
    result = {}
    for node in ET.parse(path).getroot():
        result[node.get("name")] = node.text if node.tag == "string" else node.get("value")
    return result
old = preferences(before / "speech.xml"); new = preferences(after / "speech.xml")
for key,value in old.items():
    if key == "follow": assert new.get(key) == "true"
    else: assert new.get(key) == value
result = {"passed":True,"fromVersion":"0.7.0","toVersion":"0.7.1","books":len(a["books"]),
    "annotations":len(a["marks"]),"exactLibraryRecordsPreserved":True,"exactReaderStateFilesPreserved":len(oldstates),
    "originalVoiceSettingsPreserved":True,"method":"Signed replacement APK; launch Library; compare SQLite rows, reader-state bytes and original speech preference values"}
(root / "v071-upgrade-result.json").write_text(json.dumps(result,indent=2)+"\n",encoding="utf-8")
print(json.dumps(result,indent=2))
