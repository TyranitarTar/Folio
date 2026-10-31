"""Offline bootstrap mode tests; never fetch model or runtime bytes."""
import json
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

import bootstrap_ai as boot


class BootstrapTests(unittest.TestCase):
    def run_mode(self, runtime_only):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);cache=root/'cache';cache.mkdir()
            assets=root/'app/src/main/assets/kokoro';assets.mkdir(parents=True)
            provenance=assets/'artifacts.json';provenance.write_text('original provenance',encoding='utf-8')
            requested=[];extracted=[]
            def fetch(item):
                name,_=item;requested.append(name)
                if name=='Tts.kt':(cache/name).write_text('api',encoding='utf-8')
                if name=='espeak-source.zip':
                    with zipfile.ZipFile(cache/name,'w') as z:z.writestr('espeak/LICENSE','license')
                return dict(name=name)
            def extract(path,destination):
                extracted.append(path.name)
                if path.name=='runtime.tar.bz2':
                    for abi in ('arm64-v8a','armeabi-v7a','x86','x86_64'):
                        folder=destination/abi;folder.mkdir(parents=True)
                        for name in ('libonnxruntime.so','libsherpa-onnx-jni.so'):(folder/name).write_bytes(b'jni')
                if path.name=='model.tar.bz2':
                    model=destination/'kokoro-multi-lang-v1_0';model.mkdir(parents=True)
                    for name in ('model.onnx','voices.bin','tokens.txt','LICENSE','README.md'):(model/name).write_bytes(b'model')
                    (model/'espeak-ng-data').mkdir()
            with patch.object(boot,'ROOT',root),patch.object(boot,'CACHE',cache),patch.object(boot,'fetch',side_effect=fetch),patch.object(boot,'extract',side_effect=extract):
                boot.main(['--runtime-only'] if runtime_only else [])
            self.assertTrue((root/'app/src/main/jniLibs/arm64-v8a/libonnxruntime.so').is_file())
            self.assertTrue((root/'app/src/main/java/com/k2fsa/sherpa/onnx/Tts.kt').is_file())
            if runtime_only:
                self.assertEqual(set(requested),{'runtime.tar.bz2','Tts.kt'})
                self.assertEqual(extracted,['runtime.tar.bz2'])
                self.assertEqual(provenance.read_text(encoding='utf-8'),'original provenance')
                self.assertFalse((assets/'model.onnx').exists())
            else:
                self.assertEqual(set(requested),set(boot.ARTIFACTS))
                self.assertEqual(extracted,['runtime.tar.bz2','model.tar.bz2','sherpa-source.tar.gz'])
                self.assertTrue((assets/'model.onnx').is_file())
                self.assertEqual([x['name'] for x in json.loads(provenance.read_text(encoding='utf-8'))],['runtime.tar.bz2','model.tar.bz2'])

    def test_runtime_only(self):self.run_mode(True)
    def test_full_default(self):self.run_mode(False)

if __name__=='__main__':unittest.main()
