import os
os.environ["HF_HUB_DISABLE_IMPLICIT_TOKEN"]="1"
import json
from pathlib import Path
from faster_whisper import WhisperModel
from probe_lm import ROOT
os.environ["HF_HOME"]=str(ROOT/"cache/huggingface")
model=WhisperModel("small",device="cpu",compute_type="int8",cpu_threads=4,download_root=str(ROOT/"cache/whisper"))
paths=[ROOT/"results/samples/pico-da-0.wav",ROOT/"results/samples/pico-da-1.wav",ROOT/"results/samples/pico-da-5.wav",ROOT/"results/lm-fp32-42.wav",
       ROOT/"results/pico-native-da.wav",
       ROOT.parent.parent/"output/Folio-0.5.0-Supertonic-Danish-preview.wav"]
for path in paths:
    if not path.is_file(): continue
    segments,_=model.transcribe(str(path),language="da",beam_size=5,condition_on_previous_text=False)
    print(json.dumps(dict(file=str(path),text=" ".join(s.text.strip() for s in segments)),ensure_ascii=False),flush=True)
