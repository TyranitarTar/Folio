"""Exclusive, recoverable experiment lease shared with Studio's renderer checks."""
from contextlib import contextmanager
import json
import math
import os
from pathlib import Path
import time


def write_trial_json(path, value):
    def finite(item):
        if isinstance(item, float) and not math.isfinite(item):
            return None
        if isinstance(item, dict):
            return {key: finite(data) for key, data in item.items()}
        if isinstance(item, (list, tuple)):
            return [finite(data) for data in item]
        return item
    Path(path).write_text(json.dumps(finite(value), indent=2, ensure_ascii=False, allow_nan=False), encoding='utf-8')


@contextmanager
def gpu_trial_lease(workspace):
    from tools.studio.processes import active_narrators, live_lease
    root = Path(workspace)
    if active_narrators(root, root/'output/FolioStudio-data'):
        raise RuntimeError('A Studio renderer or GPU trial is active')
    path = root/'tools/narration/jobs/gpu-rocm-trial/job.lock'
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        if live_lease(path):
            raise RuntimeError('GPU trial already active')
        path.unlink()
    with path.open('x', encoding='utf-8') as stream:
        json.dump(dict(pid=os.getpid(), started=time.time()), stream)
    try:
        yield
    finally:
        path.unlink(missing_ok=True)
