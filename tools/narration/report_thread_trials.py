"""Write the measured desktop report after the resumable sweep completes."""
from pathlib import Path
import csv
import json

ROOT=Path(__file__).resolve().parents[2]
AUDIT=ROOT/'android-reader/audit/studio-0.2.1'
NAMES={'pico':'Plapre Pico','plapre3':'Plapre 3','roest':'Røst 3','chatterbox-nano':'Chatterbox Nano'}


def main():
    results=json.loads((AUDIT/'summary.json').read_text(encoding='utf-8'))
    chosen=json.loads((AUDIT/'recommendations.json').read_text(encoding='utf-8'))
    lines=['# Folio Studio 0.2.1 measurements','',
        '22 October 2026. Ryzen 7 5800X (8 cores / 16 logical processors), 16 GB RAM,',
        'Radeon RX 5700 XT 8 GB, Windows. One model instance at a time; other user',
        'applications remain open. No extra model weights, GPU drivers or runtimes were installed.','',
        '## CPU thread sweep','',
        'Each cell is synthesis real-time factor (seconds of compute per second of audio;',
        '**lower is faster**), using one passage in flight and four authored passages.',
        'Every model was tested at 2, 4, 6, 8, 12 and 16 CPU threads. Pico/Plapre 3 use',
        'CPU tokens and DirectML acoustics; Røst/Nano use the existing GPU hybrid.',
        'CPU-only registrations have separate functional export checks; this table is',
        'a GPU/hybrid performance sweep, not a full CPU-only speed comparison.','',
        '| Engine | 2 | 4 | 6 | 8 | 12 | 16 |',
        '|---|---:|---:|---:|---:|---:|---:|']
    for engine,name in NAMES.items():
        values=[]
        for threads in (2,4,6,8,12,16):
            result=next(r for r in results if r['engine']==engine and r['threads']==threads and r['parallel']==1 and r['count']==4 and r['repeat']==1)
            values.append(f"{result['rtf']:.3f}")
        lines.append('| '+name+' | '+' | '.join(values)+' |')
    lines+=['','## Recommended settings and eight-passage confirmations','',
        'Two/four passages were screened at two CPU threads and at the best sequential',
        'thread count. To avoid spending working memory for a tiny timing difference,',
        'recommendations prefer fewer passages, then fewer CPU threads, among results',
        'within 3% of the fastest screening run. The chosen settings were confirmed with',
        'eight passages and compared to a one-passage run at the same thread count.','',
        '| Engine | Passages | Threads | Audio s | Sequential s | Selected s | Speedup | Resident GB | Private commit GB |',
        '|---|---:|---:|---:|---:|---:|---:|---:|---:|']
    for engine,name in NAMES.items():
        r=chosen[engine]
        lines.append(f"| {name} | {r['parallel']} | {r['threads']} | {r['audioSeconds']:.2f} | {r['baselineSeconds']:.2f} | {r['synthesisSeconds']:.2f} | {r['speedup']:.2f}× | {r['memory']['peakWorking']/1e9:.2f} | {r['memory']['private']/1e9:.2f} |")
    parity=json.loads((AUDIT/'roest-loader-parity.json').read_text(encoding='utf-8'))
    lines+=['','## Røst loading memory','',
        f"The separate upstream/low-memory loader comparison reduced peak process working set from **{parity['upstreamResidentBytes']/1e9:.2f} GB to {parity['lowMemoryResidentBytes']/1e9:.2f} GB** on the GPU-hybrid profile.",
        'This avoids temporary full-weight initialization/copies; it is a process peak,',
        'not a promise that all runtime memory or GPU VRAM falls by that amount.',
        f"Speech tokens and sample counts match. Compared WAVs differ by at most {round(max(row['peakAbsoluteError'] for row in parity['comparisons'])*32768)}",
        f"16-bit PCM steps, with maximum relative RMS error {max(row['relativeRmsError'] for row in parity['comparisons'])*100:.4f}%; detailed values are",
        'in `roest-loader-parity.json`. No model weights were quantized or replaced.','',
        'The synthesis timings exclude loading/warm-up and MP3/export assembly; each raw result',
        'also records load and total elapsed time. Individual short-run timings vary.',
        'This is not a whole-book or other-computer performance guarantee. Unrelated',
        'applications were not closed, so available memory/background load can vary.','',
        '## Correctness and GPU scheduling','',
        'All accepted thread/concurrency comparisons preserve sample counts. WAVs are',
        'either byte-identical or pass bounded numerical checks: at fixed CPU threads,',
        'at most one 16-bit PCM step; when changing FFT/reduction thread counts, absolute',
        'amplitude error below 0.0001 (at most three PCM steps). Relative RMS error',
        'must stay below 0.01% in both cases. Per-comparison checks record actual errors;',
        'larger changes fail. Røst/Nano additionally preserve speech-token hashes and leave',
        'global Torch random state unchanged. Requests have independent RNG streams',
        'and EOS retry budgets. Tokenization, token generation, acoustic decoding and',
        'watermarking each have their own model-stage gate. Only the coordinator writes',
        'ordered audio and checkpoints. No full-model copies are used.','',
        '**Device safety:** independent simultaneous GPU networks caused a Røst',
        'DirectML device-suspended error and a longer Nano stall. Those configurations',
        'are excluded. Both now serialize GPU forwards across stages while CPU',
        'preparation/finishing can overlap. Affected Nano parallel trials were rerun.',
        'Both 2/4-passage settings also have eight-passage stress checks even if the',
        'selected recommendation is sequential. Stage interval overlap includes',
        'device-gate waiting; it does not establish simultaneous GPU kernel execution.','',
        'The shared low-memory loader uses strict safetensor assignment and rebuilds',
        'derived rotary/positional tensors and the original onset fade. It verifies',
        'that no model tensor remains on the meta device before reference preparation.',
        'Installed model/runtime files and model data are unchanged.','',
        'Resident GB is Windows peak process working set. Private commit is sampled',
        'virtual commitment, not additional resident RAM. Do not add these figures.',
        'GPU VRAM is not measured by this process sampler. More passages still require',
        'working buffers, and a GPU hybrid still uses host RAM/CPU.','',
        '## Evidence and limits','',
        f'The sweep contains {len(results)} completed configurations/confirmations.',
        'Machine-readable evidence: `android-reader/audit/studio-0.2.1/summary.json`,',
        '`recommendations.json`, per-trial WAVs/logs and `thread-results.csv`.',
        'Reproduce/resume with `python tools/narration/run_thread_trials.py`.',
        'Existing completed results are reused rather than rerunning them.','',
        'Real CPU/GPU exports and a reference-voice test are recorded separately in',
        '`exports-result.json` and `export-status.json`. Automated text/audio validity',
        'does not establish pronunciation quality. Listening and desktop visual review',
        'remain open. The fully GPU-resident pipeline is still a future investigation.','']
    (ROOT/'tools/studio/PERFORMANCE-0.2.1.md').write_text('\n'.join(lines),encoding='utf-8')
    with (AUDIT/'thread-results.csv').open('w',newline='',encoding='utf-8') as stream:
        writer=csv.writer(stream);writer.writerow(['engine','backend','threads','passages','count','repeat','audio_seconds','synthesis_seconds','total_seconds','rtf','resident_bytes','private_commit_bytes'])
        for r in results:writer.writerow([r['engine'],r['backend'],r['threads'],r['parallel'],r['count'],r['repeat'],r['audioSeconds'],r['synthesisSeconds'],r['totalSeconds'],r['rtf'],r['memory']['peakWorking'],r['memory']['private']])
    print('Performance report and CSV written')


if __name__=='__main__':main()
