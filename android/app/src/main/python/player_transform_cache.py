"""Bounded, expiring RAM-only EJS transformations; never media URLs or challenge results."""
from collections import OrderedDict
import sys
import threading
import time


class PlayerTransformCache:
    MAX_BYTES = 8 * 1024 * 1024
    MAX_ENTRIES = 2
    TTL_SECONDS = 600

    def __init__(self, clock=time.monotonic, timer_factory=threading.Timer):
        self._clock = clock
        self._timer_factory = timer_factory
        self._entries = OrderedDict()
        self._bytes = 0
        self._lock = threading.Lock()
        self._timer = None

    def get(self, key):
        with self._lock:
            if self._prune(self._clock()):
                self._schedule()
            entry = self._entries.get(key)
            if entry is None:
                return None
            self._entries.move_to_end(key)
            return entry[1]

    def put(self, key, script):
        # Charge Python's retained string/tuple storage, not just character count.
        charge = sys.getsizeof(script) + sys.getsizeof(key) + sum(sys.getsizeof(part) for part in key)
        with self._lock:
            self._prune(self._clock())
            self._remove(key)
            if charge <= self.MAX_BYTES:
                while self._entries and (self._bytes + charge > self.MAX_BYTES or len(self._entries) >= self.MAX_ENTRIES):
                    self._remove(next(iter(self._entries)))
                self._entries[key] = (self._clock() + self.TTL_SECONDS, script, charge)
                self._bytes += charge
            self._schedule()

    def discard(self, key):
        with self._lock:
            self._remove(key)
            self._schedule()

    def clear(self):
        with self._lock:
            self._entries.clear()
            self._bytes = 0
            self._schedule()

    def _remove(self, key):
        entry = self._entries.pop(key, None)
        if entry is not None:
            self._bytes -= entry[2]

    def _prune(self, now):
        expired = [key for key, entry in self._entries.items() if entry[0] <= now]
        for key in expired:
            self._remove(key)
        return bool(expired)

    def _schedule(self):
        if self._timer is not None:
            self._timer.cancel()
            self._timer = None
        if self._entries:
            deadline = min(entry[0] for entry in self._entries.values())
            self._timer = self._timer_factory(max(0, deadline - self._clock()), self._expire)
            self._timer.daemon = True
            self._timer.start()

    def _expire(self):
        with self._lock:
            self._prune(self._clock())
            self._schedule()
