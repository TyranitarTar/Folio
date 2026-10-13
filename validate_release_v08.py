"""Validate the signed APK, source exclusions, native packs and scoped test evidence."""
from pathlib import Path
import hashlib,json,re,shutil,sys,zipfile,xml.etree.ElementTree as E
root=Path(__file__).resolve().parent; output=root.parent/'output'; audit=root/'audit'
def sha(p):
    h=hashlib.sha256()
    with p.open('rb') as f:
        for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
    return h.hexdigest()
apk=output/'Folio-0.8.0.apk'; digest=sha(apk)
assert digest in (output/'Folio-0.8.0.apk.sha256').read_text()
assert 'BUILD SUCCESSFUL' in (audit/'build-v08-fifth-checks.log').read_text(encoding='utf-8')
reports=list((root/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
counts={k:sum(int(E.parse(p).getroot().get(k,'0')) for p in reports) for k in ('tests','failures','errors','skipped')}
assert counts['tests']>=117 and not any(counts[k] for k in ('failures','errors','skipped'))
runs={}
for name,minimum in [('v08-focused-device-final.log',5),('v08-regression-device.log',11)]:
    text=(audit/name).read_text(encoding='utf-8')
    assert not any(x in text for x in ('FAILURES!!!','INSTRUMENTATION_FAILED','STATUS_CODE: -3'))
    m=re.search(r'OK \((\d+) tests\)',text); assert m and int(m[1])>=minimum
    runs[name]=int(m[1])
lint=(root/'app/build/reports/lint-results-debug.txt').read_text(encoding='utf-8');assert '0 errors' in lint
cert='8ba95f74fce4d7a85afc6126a6ee37b1d4c1baf8cecc4276038a0e3a256b087b'
assert cert in (audit/'v08-apk-signature.log').read_text(encoding='utf-8')
meta=(audit/'v08-apk-badging.log').read_text(encoding='utf-8')
assert "versionCode='9'" in meta and "versionName='0.8.0'" in meta
for p in ('INTERNET','REQUEST_INSTALL_PACKAGES','ACCESS_NETWORK_STATE'):assert 'android.permission.'+p in meta
with zipfile.ZipFile(apk) as now,zipfile.ZipFile(output/'Folio-0.7.1.apk') as old:
    binary=[n for n in old.namelist() if (n.startswith('lib/') and n.endswith('.so')) or n.startswith(('assets/kokoro/','assets/supertonic/'))]
    assert all(now.read(n)==old.read(n) for n in binary)
with zipfile.ZipFile(output/'Folio-source-0.8.0.zip') as z:
    names=z.namelist()
    for name in ('BookSources.kt','BookDownloads.kt','BookConversion.kt','ReaderAwake.kt','GuidedBand.kt','IMPLEMENTATION-v0.8.md'):assert any(n.endswith(name) for n in names)
    assert not any('/signing/' in n or '/toolchain/' in n or '/audit/' in n or n.endswith(('.jks','.p12','.keystore')) for n in names)
    assert not any(n.endswith('.epub') and not n.endswith('sample.epub') for n in names)
assert sha(output/'Folio-native-source-0.8.0.zip')==sha(output/'Folio-native-source-0.7.1.zip')
upgrade=json.loads((audit/'v08-upgrade-result.json').read_text()) if '--prepublication' not in sys.argv else {'passed':False,'status':'Pending published release for in-app test'}
if '--prepublication' not in sys.argv:assert upgrade['passed']
result=dict(version='0.8.0',versionCode=9,date='2026-10-13',apkBytes=apk.stat().st_size,apkSha256=digest,
    signerCertificateSha256=cert,jvm=counts,android={'runs':runs,'device':'Android15/API35 x86_64 emulator'},
    lint={'errors':0,'warnings':int(re.search(r'(\d+) warnings',lint.splitlines()[-1])[1])},
    nativeLibrariesUnchanged=sum(n.startswith('lib/') for n in binary),modelPacksUnchanged=True,upgrade=upgrade,
    limitations='Physical Galaxy S10 power behavior and ARM speech timing remain untested. Source availability and rights depend on provider/edition.')
(output/'Folio-0.8.0-validation.json').write_text(json.dumps(result,indent=2)+'\n')
dest=audit/'v08-unit-final';dest.mkdir(exist_ok=True)
for p in reports:shutil.copy2(p,dest/p.name)
print(json.dumps(result,indent=2))
