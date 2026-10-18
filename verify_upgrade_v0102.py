"""Exercise public GitHub download + Android installation on the populated test emulator.

Refuses physical devices, never clears app data or replaces an unexpected baseline.
"""
from pathlib import Path
import argparse,json,re,sqlite3,subprocess,time,xml.etree.ElementTree as ET

root=Path(__file__).resolve().parent
parser=argparse.ArgumentParser()
parser.add_argument('--from-version',default='0.10.1')
parser.add_argument('--to-version',default='0.10.2')
parser.add_argument('--to-code',type=int,default=14)
parser.add_argument('--label',default='v0102-upgrade')
args=parser.parse_args()
assert re.fullmatch(r'[a-zA-Z0-9-]+',args.label)
adb=root/'toolchain/sdk/platform-tools/adb.exe'
serial='emulator-5554';package='app.folio.reader'
audit=root/'audit';audit.mkdir(exist_ok=True)
def run(*command,check=True):
    return subprocess.run([str(adb),'-s',serial,*map(str,command)],check=check,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=45)
def call(*command,binary=False,check=True):
    raw=run(*command,check=check).stdout
    return raw if binary else raw.decode('utf-8',errors='replace')
def snapshot(label):
    folder=audit/label;folder.mkdir(exist_ok=False)
    call('shell','am','force-stop',package)
    for relative in ['files/library/library.sqlite','files/library/library.sqlite-wal','files/library/library.sqlite-shm',
                     'shared_prefs/speech.xml','shared_prefs/appearance.xml']:
        source=f'/data/user/0/{package}/{relative}'
        if run('shell','test','-f',source,check=False).returncode==0:
            (folder/Path(relative).name).write_bytes(call('exec-out','cat',source,binary=True))
    state=folder/'reader-state';state.mkdir()
    names=call('shell','find',f'/data/user/0/{package}/files/reader-state','-maxdepth','1','-name','*.json',check=False).splitlines()
    for name in names:
        if re.fullmatch(r'[a-f0-9]{64}-(speech|guided|pace|ai)\.json',Path(name).name):
            (state/Path(name).name).write_bytes(call('exec-out','cat',name,binary=True))
    return folder
def dump():
    call('shell','uiautomator','dump','/data/local/tmp/folio-upgrade.xml')
    raw=call('exec-out','cat','/data/local/tmp/folio-upgrade.xml')
    (audit/(args.label+'-ui.xml')).write_text(raw,encoding='utf-8')
    return ET.fromstring(raw)
def bounds(node):
    return list(map(int,re.findall(r'\d+',node.get('bounds',''))))
def click(predicate,seconds=60,scroll=False):
    until=time.monotonic()+seconds;attempt=0
    while time.monotonic()<until:
        ui=dump();matches=[]
        for node in ui.iter('node'):
            box=bounds(node)
            if node.get('enabled')=='true' and predicate(node.get('text','')) and len(box)==4 and box[2]>box[0] and box[3]>box[1]:
                matches.append(box)
        if matches:
            box=max(matches,key=lambda b:b[3])
            call('shell','input','tap',(box[0]+box[2])//2,(box[1]+box[3])//2)
            return
        attempt+=1
        if scroll and attempt%2==0:
            candidates=[bounds(n) for n in ui.iter('node') if n.get('scrollable')=='true' and len(bounds(n))==4]
            if candidates:
                x1,y1,x2,y2=max(candidates,key=lambda b:(b[2]-b[0])*(b[3]-b[1]))
                call('shell','input','swipe',(x1+x2)//2,y1+int((y2-y1)*.78),(x1+x2)//2,y1+int((y2-y1)*.25),500)
        time.sleep(1)
    raise RuntimeError('Expected update control missing; inspect '+str(audit/(args.label+'-ui.xml')))
def rows(folder):
    with sqlite3.connect(folder/'library.sqlite') as db:
        return {table:sorted(db.execute(f'SELECT * FROM "{table}"').fetchall(),key=repr) for table in ['books','progress','marks','shelves','state']}
def prefs(path):
    return {n.get('name'):n.text if n.tag=='string' else n.get('value') for n in ET.parse(path).getroot()}
def main():
    assert call('shell','getprop','ro.kernel.qemu').strip()=='1','Emulator only.'
    installed=call('shell','dumpsys','package',package)
    assert re.search(r'versionName='+re.escape(args.from_version)+r'\s',installed),'Unexpected baseline; no install performed.'
    call('root');call('wait-for-device')
    before=snapshot(args.label+'-before');initial=rows(before)
    assert initial['books'] and initial['marks'],'A populated library and annotation are required.'
    call('shell','input','keyevent','KEYCODE_WAKEUP');call('shell','wm','dismiss-keyguard')
    call('shell','am','start','-n',package+'/.MainActivity')
    click(lambda s:s=='More');click(lambda s:s=='App updates',scroll=True);click(lambda s:s=='Check for updates')
    click(lambda s:s.startswith('Download '+args.to_version+' '),scroll=True,seconds=90)
    print('Download started',flush=True)
    click(lambda s:s=='Install update',scroll=True,seconds=300)
    # Android may require the source-install permission first. Handle its actual settings UI.
    time.sleep(2);ui=dump()
    if any('Allow from this source' in n.get('text','') for n in ui.iter('node')):
        click(lambda s:'Allow from this source' in s)
        call('shell','input','keyevent','KEYCODE_BACK')
        click(lambda s:s=='Install update',scroll=True)
    click(lambda s:s=='Update',seconds=60)
    until=time.monotonic()+150
    while time.monotonic()<until:
        installed=call('shell','dumpsys','package',package)
        if re.search(r'versionName='+re.escape(args.to_version)+r'\s',installed) and f'versionCode={args.to_code} ' in installed:break
        time.sleep(2)
    else:raise RuntimeError('Android installation did not complete.')
    call('shell','am','start','-n',package+'/.MainActivity');time.sleep(3)
    after=snapshot(args.label+'-after')
    assert initial==rows(after),'Library records changed.'
    states={p.name:p.read_bytes() for p in (before/'reader-state').glob('*.json')}
    assert states and all((after/'reader-state'/name).read_bytes()==value for name,value in states.items())
    for name in ['speech.xml','appearance.xml']:
        if(before/name).exists():assert all(prefs(after/name).get(k)==v for k,v in prefs(before/name).items())
    result=dict(passed=True,fromVersion=args.from_version,toVersion=args.to_version,books=len(initial['books']),annotations=len(initial['marks']),
                exactLibraryRecordsPreserved=True,exactReaderStateFilesPreserved=len(states),originalVoiceAndAppearancePreserved=True,
                method='Production More/App updates; public GitHub download; in-app checksum/signature checks; Android Update confirmation on isolated emulator.')
    (audit/(args.label+'-result.json')).write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result,indent=2),flush=True)
if __name__=='__main__':main()
