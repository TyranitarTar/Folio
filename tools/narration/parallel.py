"""Bounded lookahead with shared model weights; only the caller commits audio."""
from concurrent.futures import ThreadPoolExecutor
import threading


class PassagePool:
    def __init__(self,render,workers):
        self.render=render;self.workers=workers
        self.executor=ThreadPoolExecutor(max_workers=workers,thread_name_prefix='FolioPassage') if workers>1 else None
        self.pending={};self.todo=iter(())
        self.lock=threading.Lock();self.active=0;self.peak_active=0

    def _run(self,item):
        with self.lock:self.active+=1;self.peak_active=max(self.peak_active,self.active)
        try:return self.render(item)
        finally:
            with self.lock:self.active-=1

    def begin(self,items):
        self.todo=iter(items)
        self._fill()

    def _fill(self):
        if not self.executor:return
        while len(self.pending)<self.workers:
            try:key,item=next(self.todo)
            except StopIteration:break
            self.pending[key]=self.executor.submit(self._run,item)

    def get(self,key,item):
        if not self.executor:return self._run(item)
        future=self.pending.pop(key,None)
        # A corrupted cached passage can be discovered after the initial scan.
        if future is None:future=self.executor.submit(self._run,item)
        result=future.result();self._fill()
        return result

    def close(self):
        if self.executor:
            for future in self.pending.values():future.cancel()
            self.executor.shutdown(wait=True,cancel_futures=True)
        self.pending.clear()
