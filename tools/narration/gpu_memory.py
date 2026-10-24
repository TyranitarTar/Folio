"""Optional Windows per-process GPU allocation counters; unavailable is not zero."""
import ctypes
from ctypes import wintypes as W
import os

class GpuMemorySampler:
    def __init__(self):
        self.error=None;self.query=None;self.counters={}
        try:
            self.api=ctypes.WinDLL('pdh')
            self.api.PdhOpenQueryW.argtypes=[W.LPCWSTR,ctypes.c_size_t,ctypes.POINTER(W.HANDLE)]
            self.api.PdhAddEnglishCounterW.argtypes=[W.HANDLE,W.LPCWSTR,ctypes.c_size_t,ctypes.POINTER(W.HANDLE)]
            self.api.PdhCollectQueryData.argtypes=[W.HANDLE]
            self.api.PdhGetFormattedCounterArrayW.argtypes=[W.HANDLE,W.DWORD,ctypes.POINTER(W.DWORD),ctypes.POINTER(W.DWORD),ctypes.c_void_p]
            self.api.PdhCloseQuery.argtypes=[W.HANDLE]
            self.query=W.HANDLE()
            self.check(self.api.PdhOpenQueryW(None,0,ctypes.byref(self.query)))
            for label,name in [('dedicatedGpuBytes','Dedicated Usage'),('sharedGpuBytes','Shared Usage')]:
                handle=W.HANDLE();self.check(self.api.PdhAddEnglishCounterW(self.query,'\\GPU Process Memory(*)\\'+name,0,ctypes.byref(handle)))
                self.counters[label]=handle
        except (OSError,AttributeError) as exc:self.error=str(exc)

    @staticmethod
    def check(code):
        if code:raise OSError(f'GPU allocation counter returned 0x{code & 0xffffffff:08x}')

    def sample(self):
        if self.error:return {}
        class ValueUnion(ctypes.Union):
            _fields_=[('integer',ctypes.c_longlong),('number',ctypes.c_double),('pointer',ctypes.c_void_p)]
        class Value(ctypes.Structure):_fields_=[('status',W.DWORD),('value',ValueUnion)]
        class Item(ctypes.Structure):_fields_=[('name',W.LPWSTR),('formatted',Value)]
        result={}
        try:
            self.check(self.api.PdhCollectQueryData(self.query))
            for label,counter in self.counters.items():
                size=W.DWORD();count=W.DWORD()
                code=self.api.PdhGetFormattedCounterArrayW(counter,0x400,ctypes.byref(size),ctypes.byref(count),None)
                if (code & 0xffffffff)!=0x800007d2 or not size.value:continue
                buffer=ctypes.create_string_buffer(size.value)
                self.check(self.api.PdhGetFormattedCounterArrayW(counter,0x400,ctypes.byref(size),ctypes.byref(count),buffer))
                items=ctypes.cast(buffer,ctypes.POINTER(Item))
                values=[items[i].formatted.value.integer for i in range(count.value)
                        if items[i].name.startswith(f'pid_{os.getpid()}_') and items[i].formatted.status in (0,1)]
                if values:result[label]=sum(values)
        except OSError as exc:self.error=str(exc)
        return result

    def close(self):
        if self.query:self.api.PdhCloseQuery(self.query);self.query=None
