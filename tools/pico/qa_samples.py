"""Create authentic pack previews and inspect original Danish passages with local ASR."""
import json
import os
import pathlib
import time
import numpy as np
import onnxruntime as ort
import soundfile as sf
from probe_lm import PicoLM, ROOT

os.environ["HF_HOME"] = str(ROOT / "cache/huggingface")
os.environ["HF_HUB_DISABLE_IMPLICIT_TOKEN"] = "1"

def main():
    options = ort.SessionOptions(); options.intra_op_num_threads = 4
    options.inter_op_num_threads = 1
    kanade = ort.InferenceSession(str(ROOT / "export/kanade.onnx"), options)
    hift = ort.InferenceSession(str(ROOT / "export/hift.onnx"), options)
    lm = PicoLM()
    folder = ROOT / "results/samples"; folder.mkdir(exist_ok=True)
    cases = [(v, "Regnen er holdt op. Hun lukker bogen, tager sin jakke og går stille ud i den klare morgen.") for v in ["ida", "tor", "liv", "ask", "kaj"]]
    cases += [("ida", text) for text in [
        "Det gamle bibliotek ligger på hjørnet af Nørregade og Søndergade.",
        "Hun hviskede: Jeg kommer tilbage i morgen. Derefter lukkede hun døren.",
        "Toget til København afgår klokken sytten. Husk din jakke og din billet.",
        "Prisen er tolv komma fem nul kroner. Der er femogtyve bøger på hylden.",
    ]]
    report=[]
    for index, (voice, text) in enumerate(cases):
        start = time.perf_counter()
        result = lm.generate(text, voice, seed=42 + index)
        if result["stop"] != "eos":
            raise RuntimeError(f"Incomplete Pico generation: {voice} / {text}")
        codes = np.asarray(result["tokens"], np.int64)
        speaker = np.asarray(lm.speakers[voice], np.float32)[None]
        mel = kanade.run(None, {"tokens": codes, "speaker": speaker})[0]
        rng = np.random.default_rng(42)
        initial = rng.random((1, 9), dtype=np.float32); initial[:, 0] = 0
        noise = rng.normal(size=(1, codes.size * 960, 9)).astype(np.float32)
        wave = hift.run(None, {"mel": mel, "noise": noise, "initial_phase": initial})[0].reshape(-1)
        assert np.isfinite(wave).all() and wave.size == codes.size * 960
        target = folder / (f"pico-da-{index}.wav")
        sf.write(target, wave, 24000, subtype="PCM_16")
        row={k:v for k,v in result.items() if k!="tokens"}
        row.update(file=target.name, full_seconds=time.perf_counter()-start)
        report.append(row)
        print(json.dumps(row,ensure_ascii=False),flush=True)
    (ROOT / "results/qa-samples.json").write_text(json.dumps(report,indent=2,ensure_ascii=False),encoding="utf-8")
    from faster_whisper import WhisperModel
    recognizer = WhisperModel("tiny",device="cpu",compute_type="int8",cpu_threads=4,
                             download_root=str(ROOT / "cache/whisper"))
    for row in report:
        segments, _ = recognizer.transcribe(str(folder/row["file"]),language="da",beam_size=5,condition_on_previous_text=False)
        row["asr"] = " ".join(s.text.strip() for s in segments)
        print(json.dumps({"expected":row["text"],"asr":row["asr"]},ensure_ascii=False),flush=True)
    (ROOT / "results/qa-samples.json").write_text(json.dumps(report,indent=2,ensure_ascii=False),encoding="utf-8")

if __name__ == "__main__":
    main()
