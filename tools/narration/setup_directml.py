"""Set up the isolated experimental desktop GPU runtime without changing CPU files."""
from pathlib import Path
import subprocess
import sys

HERE=Path(__file__).resolve().parent
CPU=HERE/'.venv'
GPU=HERE/'.venv-dml'


def main():
    if sys.platform!='win32': raise SystemExit('This tested DirectML setup is for Windows.')
    cpu_python=CPU/'Scripts/python.exe'
    if not cpu_python.is_file(): raise SystemExit('Install the working CPU narration environment first; see README.md.')
    if not (GPU/'Scripts/python.exe').is_file():
        subprocess.run([str(cpu_python),'-m','venv',str(GPU)],check=True)
    python=GPU/'Scripts/python.exe'
    common=[str(python),'-m','pip','install','--disable-pip-version-check','--cache-dir',str(HERE/'cache/pip')]
    subprocess.run(common+['torch==2.4.1','torchaudio==2.4.1','torchvision==0.19.1','numpy==1.26.4',
                          '--index-url','https://download.pytorch.org/whl/cpu'],check=True)
    subprocess.run(common+['torch-directml==0.2.5.dev240914','onnxruntime-directml==1.23.0','numpy==1.26.4'],check=True)
    # The adapter experiment deliberately uses a different torch version from
    # Chatterbox's package pin. Reuse non-torch speech dependencies read-only;
    # the GPU environment's own torch/torchaudio take precedence on sys.path.
    (GPU/'Lib/site-packages/folio_cpu_dependencies.pth').write_text(str(CPU/'Lib/site-packages')+'\n',encoding='utf-8')
    subprocess.run([str(python),str(HERE/'gpu_probe.py'),'tensor','--output',str(HERE/'gpu-results/setup-tensor.json')],check=True)
    print('Isolated GPU runtime ready. Røst supports experimental token-only and acoustic DirectML paths; see DIRECTML.md.')


if __name__=='__main__': main()
