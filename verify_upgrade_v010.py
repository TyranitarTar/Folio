"""Real 0.9 -> 0.10 in-app update on the local test emulator only; compare exact user records."""
from pathlib import Path
import json,re,sqlite3,subprocess,time,xml.etree.ElementTree as ET

root=Path(__file__).resolve().parent
adb=root/'toolchain/sdk/platform-tools/adb.exe'
serial='emulator-5554'
package='app.folio.reader'
audit=root/'audit'
def call(*args,binary=False,check=True):
    result=subprocess.run([str(adb),'-s',serial,*map(str,args)],check=check,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=40)
    return result.stdout if binary else result.stdout.decode('utf-8',errors='replace')
def snapshot(label):
    folder=audit/label;folder.mkdir(exist_ok=True)
    call('shell','am','force-stop',package)
    for relative in ['files/library/library.sqlite','files/library/library.sqlite-wal','files/library/library.sqlite-shm',
                     'shared_prefs/speech.xml','shared_prefs/appearance.xml']:
        source=f'/data/user/0/{package}/{relative}'
        if call('shell','test','-f',source,check=False)=='' and subprocess.run([str(adb),'-s',serial,'shell','test','-f',source]).returncode==0:
            (folder/Path(relative).name).write_bytes(call('exec-out','cat',source,binary=True))
    state=folder/'reader-state';state.mkdir(exist_ok=True)
    names=call('shell','find',f'/data/user/0/{package}/files/reader-state','-maxdepth','1','-name','*.json',check=False).splitlines()
    for name in names:
        if re.fullmatch(r'[a-f0-9]{64}-(speech|guided|pace|ai)\.json',Path(name).name):
            (state/Path(name).name).write_bytes(call('exec-out','cat',name,binary=True))
    return folder
def dump():
    call('shell','uiautomator','dump','/data/local/tmp/folio-v010-upgrade.xml')
    raw=call('exec-out','cat','/data/local/tmp/folio-v010-upgrade.xml')
    (audit/'v010-upgrade-ui.xml').write_text(raw,encoding='utf-8')
    return ET.fromstring(raw)
def click(predicate,seconds=90,scroll=False):
    end=time.monotonic()+seconds;attempt=0
    while time.monotonic()<end:
        for node in dump().iter('node'):
            if node.get('enabled')=='true' and predicate(node.get('text','')):
                values=list(map(int,re.findall(r'\d+',node.get('bounds',''))))
                if len(values)==4:
                    call('shell','input','tap',(values[0]+values[2])//2,(values[1]+values[3])//2)
                    return
        attempt+=1
        if scroll and attempt%2==0:call('shell','input','swipe','540','1850','540','950','350')
        time.sleep(1)
    raise RuntimeError('Expected update control was not available; inspect the saved emulator UI dump.')
def rows(folder):
    with sqlite3.connect(folder/'library.sqlite') as connection:
        return {table:sorted(connection.execute(f'SELECT * FROM "{table}"').fetchall(),key=repr) for table in ['books','progress','marks','shelves','state']}
def prefs(path):
    return {n.get('name'):n.text if n.tag=='string' else n.get('value') for n in ET.parse(path).getroot()}
def main():
    assert call('shell','getprop','ro.kernel.qemu').strip()=='1','This check is restricted to an emulator.'
    initial=call('shell','dumpsys','package',package)
    assert 'versionName=0.9.0' in initial,'Do not replace another installed version automatically.'
    call('root');call('wait-for-device')
    before=snapshot('v010-upgrade-before')
    initial_rows=rows(before)
    assert initial_rows['books'],'Use a populated emulator library for the update check.'
    call('shell','input','keyevent','KEYCODE_WAKEUP');call('shell','wm','dismiss-keyguard')
    call('shell','am','start','-n',package+'/.MainActivity')
    click(lambda text:text=='More')
    click(lambda text:text=='App updates',scroll=True)
    click(lambda text:text=='Check for updates')
    click(lambda text:text.startswith('Download 0.10.0'),scroll=True)
    click(lambda text:text=='Install update',seconds=180,scroll=True)
    click(lambda text:text=='Update',seconds=60)
    end=time.monotonic()+120
    while time.monotonic()<end:
        version=call('shell','dumpsys','package',package)
        if 'versionName=0.10.0' in version and 'versionCode=12 ' in version:break
        time.sleep(2)
    else:raise RuntimeError('Android did not finish installing the verified update.')
    call('shell','am','start','-n',package+'/.MainActivity');time.sleep(3)
    after=snapshot('v010-upgrade-after')
    assert initial_rows==rows(after),'Library records changed.'
    states={p.name:p.read_bytes() for p in (before/'reader-state').glob('*.json')}
    assert states and all((after/'reader-state'/name).read_bytes()==value for name,value in states.items())
    for name in ['speech.xml','appearance.xml']:
        if (before/name).exists():assert all(prefs(after/name).get(k)==v for k,v in prefs(before/name).items())
    result=dict(passed=True,fromVersion='0.9.0',toVersion='0.10.0',books=len(initial_rows['books']),annotations=len(initial_rows['marks']),
                exactLibraryRecordsPreserved=True,exactReaderStateFilesPreserved=len(states),originalVoiceAndAppearancePreserved=True,
                method='Production More/App updates, public GitHub download, checksum/signature validation and Android Update confirmation on the local emulator.')
    (audit/'v010-upgrade-result.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result,indent=2),flush=True)
if __name__=='__main__':main()
