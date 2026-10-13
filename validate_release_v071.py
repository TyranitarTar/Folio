"""Validate the v0.7.1 artifact and its scoped regression evidence."""
from pathlib import Path
import hashlib,json,re,shutil,zipfile,xml.etree.ElementTree as E
root=Path(__file__).resolve().parent; output=root.parent/'output'; audit=root/'audit'
def sha(path):
 h=hashlib.sha256()
 with path.open('rb') as f:
  for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
 return h.hexdigest()
apk=output/'Folio-0.7.1.apk'; digest=sha(apk)
assert digest in (output/'Folio-0.7.1.apk.sha256').read_text()
assert 'BUILD SUCCESSFUL' in (root/'build-v071-candidate.log').read_text(encoding='utf-8')
reports=list((root/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
counts={k:sum(int(E.parse(p).getroot().get(k,'0')) for p in reports) for k in ('tests','failures','errors','skipped')}
assert counts['tests']>=97 and not any(counts[k] for k in ('failures','errors','skipped'))
logs={}
for name,minimum in [('focused-v071-final-device.log',8),('regression-v071-final-device.log',9)]:
 log=(root/name).read_text(encoding='utf-8'); assert not any(x in log for x in ('FAILURES!!!','INSTRUMENTATION_FAILED','STATUS_CODE: -3'))
 match=re.search(r'OK \((\d+) tests\)',log); assert match and int(match.group(1))>=minimum
 logs[name]=int(match.group(1))
lint=(root/'app/build/reports/lint-results-debug.txt').read_text(encoding='utf-8');assert '0 errors' in lint
warnings=int(re.search(r'(\d+) warnings',lint.splitlines()[-1]).group(1))
cert='8ba95f74fce4d7a85afc6126a6ee37b1d4c1baf8cecc4276038a0e3a256b087b'
assert cert in (audit/'v071-apk-signature.log').read_text(encoding='utf-8')
meta=(audit/'v071-apk-badging.log').read_text(encoding='utf-8')
assert "versionCode='8'" in meta and "versionName='0.7.1'" in meta
assert 'android.permission.INTERNET' in meta and 'android.permission.REQUEST_INSTALL_PACKAGES' in meta
with zipfile.ZipFile(apk) as now,zipfile.ZipFile(output/'Folio-0.7.0.apk') as old:
 binary=[n for n in old.namelist() if (n.startswith('lib/') and n.endswith('.so')) or n.startswith(('assets/kokoro/','assets/supertonic/'))]
 assert all(now.read(n)==old.read(n) for n in binary)
 native=[n for n in binary if n.startswith('lib/')]
with zipfile.ZipFile(output/'Folio-source-0.7.1.zip') as z:
 names=z.namelist(); assert any(n.endswith('AppUpdates.kt') for n in names)
 assert any(n.endswith('.github/workflows/ci.yml') for n in names)
 assert not any('/signing/' in n or n.endswith(('.jks','.keystore','.p12')) for n in names)
 assert not any(n.endswith('.epub') and not n.endswith('sample.epub') for n in names)
assert sha(output/'Folio-native-source-0.7.1.zip')==sha(output/'Folio-native-source-0.7.0.zip')
upgrade=json.loads((audit/'v071-upgrade-result.json').read_text());assert upgrade['passed']
timing=json.loads((audit/'v071-device-evidence/pagination-v071-timings.json').read_text());assert timing['equivalent']
result={'version':'0.7.1','versionCode':8,'date':'2026-10-12','apkBytes':apk.stat().st_size,'apkSha256':digest,
 'signerCertificateSha256':cert,'jvm':counts,'android':{'runs':logs,'device':'Android15/API35 x86_64 local emulator'},
 'lint':{'errors':0,'warnings':warnings},'nativeLibrariesUnchanged':len(native),'modelPacksUnchanged':True,
 'pagination':timing,'upgrade':upgrade,'github':{'repository':'TyranitarTar/Folio','publication':'Awaiting authenticated browser publishing','automaticChecks':False},
 'limitations':'No physical Galaxy S10 timing benchmark. GitHub download/installer end-to-end acceptance awaits a published future release; URL/version/digest/package/signature policies and manual-update UI are tested.'}
(output/'Folio-0.7.1-validation.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
shutil.copy2(audit/'Folio-0.7.1-review.md',output/'Folio-0.7.1-review.md')
dest=audit/'v071-unit-final';dest.mkdir(exist_ok=True)
for p in reports:shutil.copy2(p,dest/p.name)
print(json.dumps({'apk':apk.name,'sha256':digest,'jvmTests':counts['tests'],'androidRuns':logs,'upgradePreserved':True},indent=2))
