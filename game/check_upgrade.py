"""Local signed-upgrade check, restricted to the existing test emulator."""
from pathlib import Path
import hashlib
from io import BytesIO
from PIL import Image
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ADB = ROOT / 'toolchain/sdk/platform-tools/adb.exe'
APK = ROOT.parent / 'output/Folio-0.19.0.apk'
AUDIT = ROOT / 'audit/v019-upgrade'
PACKAGE = 'app.folio.reader'
AUDIT.mkdir(exist_ok=True)
def call(*args, binary=False):
    data = subprocess.check_output([str(ADB), '-s', 'emulator-5554', *map(str,args)], timeout=60)
    return data if binary else data.decode('utf8', errors='replace')
assert call('shell','getprop','ro.kernel.qemu').strip() == '1', 'Only the test emulator is allowed'
version = call('shell','dumpsys','package',PACKAGE)
baseline = re.search(r'versionName=([^\s]+)', version).group(1)
assert baseline in {'0.18.1'}, 'Unexpected installed baseline'
call('root'); time.sleep(2)
call('shell','am','force-stop',PACKAGE)
base = f'/data/user/0/{PACKAGE}'
names = call('shell','find',base+'/files',base+'/databases',base+'/shared_prefs','-type','f').splitlines()
paths = [n for n in names if ('/reader-state/' in n or '/bookmarks/' in n or
    n.endswith(('/library.sqlite','/cat-cafe.db','/notebook.json','/notebook-draft.json','/notes-legacy.json',
                '/speech.xml','/appearance.xml','/audio-settings.xml','/cafe-room-v1.xml','/cafe-room-godot-v1.json')))]
assert base+'/shared_prefs/cafe-room-v1.xml' in paths, 'Start the 0.18.1 room before the upgrade so a real migration is checked'
assert not any(n.endswith('/cafe-room-godot-v1.json') for n in paths), 'Baseline already has a Godot state file'
before = {n:hashlib.sha256(call('exec-out','cat',n,binary=True)).hexdigest() for n in paths}
print(call('install','-r',APK))
after = {n:hashlib.sha256(call('exec-out','cat',n,binary=True)).hexdigest() for n in paths}
assert before == after, 'Signed installation changed durable data'
assert 'versionName=0.19.0' in call('shell','dumpsys','package',PACKAGE)
call('shell','am','start','-n',PACKAGE+'/app.folio.reader.MainActivity'); time.sleep(3)
call('shell','uiautomator','dump','/sdcard/folio-v019-upgrade.xml')
root = ET.fromstring(call('shell','cat','/sdcard/folio-v019-upgrade.xml'))
tabs = [n for n in root.iter('node') if n.get('text') == 'Café' or n.get('content-desc') == 'Café tab']
assert tabs, 'No Café navigation in installed release'
bounds = [int(n) for n in re.findall(r'\d+',tabs[-1].get('bounds',''))]
assert len(bounds)==4
call('shell','input','tap',(bounds[0]+bounds[2])//2,(bounds[1]+bounds[3])//2)
start=time.monotonic()
for _ in range(18):
    time.sleep(.7)
    pixels=call('exec-out','screencap','-p',binary=True)
    with Image.open(BytesIO(pixels)) as frame:
        sample=frame.crop((frame.width//5,frame.height//4,frame.width*4//5,frame.height*3//4)).convert('RGB')
        colors=len(set(sample.resize((100,100)).getdata()))
    if colors>1500:
        (AUDIT/'release-cafe.png').write_bytes(pixels)
        break
else:
    raise AssertionError('The signed release did not render the room')
visible_seconds=round(time.monotonic()-start,2)
# The released host must actually render, migrate state, and release its process.
assert call('shell','pidof',PACKAGE+':cafe').strip(), 'No isolated production engine'
old_room_path=base+'/shared_prefs/cafe-room-v1.xml'
old_room={}
if old_room_path in before:
    parsed=ET.fromstring(call('exec-out','cat',old_room_path))
    old_room={entry.get('name'):entry.text for entry in parsed if entry.tag=='string'}
new_room=json.loads(call('exec-out','cat',base+'/files/cafe-room-godot-v1.json'))
for key in ('mode','pace'):
    if key in old_room:assert new_room[key]==old_room[key], 'Room preference migration changed '+key
for key,value in json.loads(old_room.get('counts','{}')).items():
    if isinstance(value,int) and 0<value<=100000:assert new_room['counts'].get(key,0)>=value
call('shell','input','keyevent','KEYCODE_HOME');time.sleep(2)
assert not any(line.strip().endswith(PACKAGE+':cafe') for line in call('shell','ps','-A').splitlines()), 'Hidden release left engine running'
report = {'from':baseline,'to':'0.19.0','checkedDurableFiles':len(paths),
          'byteIdenticalAfterSignedInstallation':True,'uninstalledOrCleared':False,
          'checksBeforeFirstLaunch':True,'apkSha256':hashlib.sha256(APK.read_bytes()).hexdigest(),
          'files':before,'physicalPhone':False,'releaseEngineOpened':True,
          'oldRoomMigrated':True,'releaseEngineExitedOnHome':True,
          'releaseRendered':True,'sampledColors':colors,'visibleBySeconds':visible_seconds}
(AUDIT/'result.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps({k:v for k,v in report.items() if k!='files'},indent=2))
