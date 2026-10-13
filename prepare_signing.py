"""Create a local release key once. Keep signing/ private and backed up for updates."""
from pathlib import Path
import os
import secrets
import shutil
import subprocess

root = Path(__file__).resolve().parent
directory = root / 'signing'
directory.mkdir(exist_ok=True)
properties = directory / 'key.properties'
key = directory / 'folio-release.p12'
if properties.exists() and key.exists():
    print('Existing release signing key retained.')
else:
    if properties.exists() or key.exists():
        raise RuntimeError('Incomplete signing setup; preserve existing files and repair it manually.')
    local_jdks = root / 'toolchain/jdk'
    if local_jdks.exists():
        keytool = str(next(local_jdks.iterdir()) / 'bin/keytool.exe')
    else:
        keytool = shutil.which('keytool')
        if not keytool: raise RuntimeError('Install a JDK and add its bin directory to PATH.')
    password = secrets.token_hex(32)
    environment = dict(os.environ, FOLIO_SIGNING_PASSWORD=password)
    subprocess.run([
        keytool, '-genkeypair', '-keystore', str(key),
        '-storetype', 'PKCS12', '-alias', 'folio', '-keyalg', 'RSA', '-keysize', '3072',
        '-validity', '10000', '-storepass:env', 'FOLIO_SIGNING_PASSWORD',
        '-keypass:env', 'FOLIO_SIGNING_PASSWORD',
        '-dname', 'CN=Folio Local Build, OU=Reader Project, O=Folio, C=DK'
    ], env=environment, check=True, capture_output=True)
    properties.write_text('storeFile=signing/folio-release.p12\nkeyAlias=folio\n'
                          f'storePassword={password}\nkeyPassword={password}\n', encoding='ascii')
    print('Local release signing key created. Private key is excluded from source archives.')
