"""Local signed-upgrade check, restricted to the existing test emulator."""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ADB = ROOT / 'toolchain/sdk/platform-tools/adb.exe'
APK = ROOT.parent / 'output/Folio-0.18.1.apk'
AUDIT = ROOT / 'audit/v0181-upgrade'
PACKAGE = 'app.folio.reader'
AUDIT.mkdir(exist_ok=True)
def call(*args, binary=False):
    data = subprocess.check_output([str(ADB), '-s', 'emulator-5554', *map(str,args)], timeout=60)
    return data if binary else data.decode('utf8', errors='replace')
assert call('shell','getprop','ro.kernel.qemu').strip() == '1', 'Only the test emulator is allowed'
version = call('shell','dumpsys','package',PACKAGE)
baseline = re.search(r'versionName=([^\s]+)', version).group(1)
assert baseline in {'0.18.0'}, 'Unexpected installed baseline'
call('root'); time.sleep(2)
call('shell','am','force-stop',PACKAGE)
base = f'/data/user/0/{PACKAGE}'
names = call('shell','find',base+'/files',base+'/databases',base+'/shared_prefs','-type','f').splitlines()
paths = [n for n in names if ('/reader-state/' in n or '/bookmarks/' in n or
    n.endswith(('/library.sqlite','/cat-cafe.db','/notebook.json','/notebook-draft.json','/notes-legacy.json',
                '/speech.xml','/appearance.xml','/audio-settings.xml','/cafe-room-v1.xml')))]
assert paths, 'No existing durable files to verify'
before = {n:hashlib.sha256(call('exec-out','cat',n,binary=True)).hexdigest() for n in paths}
print(call('install','-r',APK))
after = {n:hashlib.sha256(call('exec-out','cat',n,binary=True)).hexdigest() for n in paths}
assert before == after, 'Signed installation changed durable data'
assert 'versionName=0.18.1' in call('shell','dumpsys','package',PACKAGE)
call('shell','am','start','-n',PACKAGE+'/app.folio.reader.MainActivity'); time.sleep(3)
call('shell','uiautomator','dump','/sdcard/folio-v019-upgrade.xml')
root = ET.fromstring(call('shell','cat','/sdcard/folio-v019-upgrade.xml'))
tabs = [n for n in root.iter('node') if n.get('text') == 'Café' or n.get('content-desc') == 'Café tab']
assert tabs, 'No Café navigation in installed release'
bounds = [int(n) for n in re.findall(r'\d+',tabs[-1].get('bounds',''))]
assert len(bounds)==4
call('shell','input','tap',(bounds[0]+bounds[2])//2,(bounds[1]+bounds[3])//2)
time.sleep(4)
(AUDIT/'release-cafe.png').write_bytes(call('exec-out','screencap','-p',binary=True))
report = {'from':baseline,'to':'0.18.1','checkedDurableFiles':len(paths),
          'byteIdenticalAfterSignedInstallation':True,'uninstalledOrCleared':False,
          'checksBeforeFirstLaunch':True,'apkSha256':hashlib.sha256(APK.read_bytes()).hexdigest(),
          'files':before,'physicalPhone':False}
(AUDIT/'result.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps({k:v for k,v in report.items() if k!='files'},indent=2))
