"""Bound extractor metadata reads and decompression; media transfer is separate."""
import io
import zlib

MAX_RESPONSE = 8 * 1024 * 1024
MAX_TOTAL = 32 * 1024 * 1024


class LimitedReader(io.IOBase):
    def __init__(self, original, event, check):
        self.original, self.event, self.check = original, event, check
        self.used = 0

    def __getattr__(self, name):
        return getattr(self.original, name)

    def readable(self):
        return True

    def read(self, size=-1):
        self.check(self.event)
        if size == 0:
            return b''
        remaining = min(MAX_RESPONSE - self.used, MAX_TOTAL - getattr(self.event, 'metadata_bytes', 0))
        amount = remaining + 1 if size is None or size < 0 else min(size, remaining + 1)
        try:
            data = self.original.read(amount)
            self.used += len(data)
            self.event.metadata_bytes = getattr(self.event, 'metadata_bytes', 0) + len(data)
            if len(data) > remaining:
                raise ValueError('METADATA_LIMIT')
            self.check(self.event)
            return data
        except Exception:
            self.close()
            raise

    def close(self):
        self.original.close()
        super().close()


def bounded_decompress(data, wbits, limit=MAX_RESPONSE):
    if not data:
        return data
    decoder = zlib.decompressobj(wbits)
    result = decoder.decompress(data, limit + 1)
    if len(result) > limit or decoder.unconsumed_tail:
        raise ValueError('METADATA_LIMIT')
    result += decoder.flush(limit - len(result) + 1)
    if len(result) > limit:
        raise ValueError('METADATA_LIMIT')
    if not decoder.eof:
        raise zlib.error('Incomplete compressed metadata')
    return result
