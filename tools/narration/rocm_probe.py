"""Bounded, isolated gfx1010 compatibility probe; never loads user jobs/models."""
from pathlib import Path
import argparse
import ctypes
from ctypes import wintypes
import json
import os
import sys
import time
import traceback


def host_memory():
    class Memory(ctypes.Structure):
        _fields_ = [('length', wintypes.DWORD), ('load', wintypes.DWORD)] + [
            (n, ctypes.c_ulonglong) for n in ('total', 'available', 'commit', 'available_commit', 'virtual', 'available_virtual', 'extended')]
    value = Memory()
    value.length = ctypes.sizeof(value)
    ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(value))
    return dict(availableBytes=value.available, availableCommitBytes=value.available_commit)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = dict(passed=False, state='starting', pid=os.getpid(), python=sys.version,
                  tests=[], memoryBefore=host_memory(), started=time.time())
    args.output.parent.mkdir(parents=True, exist_ok=True)

    def save():
        args.output.write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding='utf-8')

    save()
    try:
        result['state'] = 'importing_torch'
        save()
        import torch
        import torch.nn.functional as F
        torch.set_num_threads(2)
        torch.set_num_interop_threads(1)
        result.update(torch=torch.__version__, hip=torch.version.hip,
                      available=torch.cuda.is_available(), devices=torch.cuda.device_count())
        if not torch.version.hip or not result['available']:
            raise RuntimeError('A working HIP GPU was not enumerated; no CPU substitution allowed.')
        device = torch.device('cuda:0')
        props = torch.cuda.get_device_properties(0)
        result['gpu'] = dict(name=props.name, architecture=getattr(props, 'gcnArchName', None),
                             totalMemoryBytes=props.total_memory)
        result['memoryGpuBefore'] = dict(zip(('free', 'total'), torch.cuda.mem_get_info()))
        result['state'] = 'testing_operators'
        save()
        torch.manual_seed(41)

        def test(name, cpu, gpu, atol=2e-4, rtol=2e-4):
            result['currentTest'] = name
            save()
            expected = cpu()
            torch.cuda.synchronize()
            start = time.perf_counter()
            actual = gpu()
            torch.cuda.synchronize()
            elapsed = time.perf_counter() - start
            if actual.device.type != 'cuda':
                raise RuntimeError(name + ' did not produce a GPU tensor')
            returned = actual.float().cpu()
            passed = bool(torch.isfinite(returned).all() and torch.allclose(returned, expected.float(), atol=atol, rtol=rtol))
            row = dict(name=name, passed=passed, device=str(actual.device), seconds=elapsed,
                       maxAbsError=float((returned - expected.float()).abs().max()), shape=list(actual.shape))
            result['tests'].append(row)
            save()
            if not passed:
                raise RuntimeError(name + ' failed numeric validation')
            print(json.dumps(row), flush=True)

        a = torch.randn(128, 256)
        b = torch.randn(256, 128)
        ag, bg = a.to(device), b.to(device)
        test('matmul-fp32', lambda: a @ b, lambda: ag @ bg)
        ah, bh = a.half(), b.half()
        ahg, bhg = ah.to(device), bh.to(device)
        test('matmul-fp16', lambda: ah.float() @ bh.float(), lambda: ahg @ bhg, atol=.04, rtol=.02)

        q, k, v = [torch.randn(1, 5, 64, 64) for _ in range(3)]
        qg, kg, vg = [x.to(device) for x in (q, k, v)]
        test('attention-fp32', lambda: F.scaled_dot_product_attention(q, k, v, is_causal=True),
             lambda: F.scaled_dot_product_attention(qg, kg, vg, is_causal=True))
        x = torch.randn(1, 16, 256)
        w = torch.randn(32, 16, 3)
        xg, wg = x.to(device), w.to(device)
        test('conv1d-fp32', lambda: F.conv1d(x, w, padding=1), lambda: F.conv1d(xg, wg, padding=1))
        test('conv-transpose1d-fp32', lambda: F.conv_transpose1d(x, w.transpose(0, 1), stride=2),
             lambda: F.conv_transpose1d(xg, wg.transpose(0, 1).contiguous(), stride=2))
        test('nearest-resize', lambda: F.interpolate(x, scale_factor=3, mode='nearest'),
             lambda: F.interpolate(xg, scale_factor=3, mode='nearest'))

        signal = torch.randn(1, 24000)
        window = torch.hann_window(16)
        sg, windowg = signal.to(device), window.to(device)
        def spectral_roundtrip(value, win):
            spectrum = torch.stft(value, n_fft=16, hop_length=4, win_length=16, window=win, return_complex=True)
            return torch.istft(spectrum, n_fft=16, hop_length=4, win_length=16, window=win, length=value.shape[-1])
        test('stft-istft-complex', lambda: spectral_roundtrip(signal, window), lambda: spectral_roundtrip(sg, windowg))

        cache = torch.zeros(1, 5, 80, 64)
        cacheg = cache.to(device)
        index = torch.arange(8, dtype=torch.int64)
        indexg = index.to(device)
        test('cache-index-copy', lambda: cache.index_copy(2, index, k[:, :, :8]),
             lambda: cacheg.index_copy(2, indexg, kg[:, :, :8]))
        embedding = torch.randn(256, 64)
        eg = embedding.to(device)
        test('embedding-lookup', lambda: F.embedding(index, embedding), lambda: F.embedding(indexg, eg))
        logits = torch.randn(1, 1024)
        lg = logits.to(device)
        test('topk-softmax', lambda: logits.topk(50).values.softmax(-1), lambda: lg.topk(50).values.softmax(-1))
        result['currentTest'] = 'multinomial'
        save()
        sample = torch.multinomial(lg.softmax(-1), 1)
        torch.cuda.synchronize()
        assert sample.device.type == 'cuda' and 0 <= int(sample.item()) < 1024
        result['tests'].append(dict(name='multinomial', passed=True, device=str(sample.device)))
        result.update(passed=True, state='complete', memoryAfter=host_memory(),
                      allocatedGpuPeakBytes=torch.cuda.max_memory_allocated(), reservedGpuPeakBytes=torch.cuda.max_memory_reserved(),
                      memoryGpuAfter=dict(zip(('free', 'total'), torch.cuda.mem_get_info())))
    except Exception as exc:
        result.update(state='failed', error=str(exc), traceback=traceback.format_exc(), memoryAfter=host_memory())
        print(result['traceback'], flush=True)
    finally:
        result['finished'] = time.time()
        save()
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
