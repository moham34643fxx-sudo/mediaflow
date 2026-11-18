"""Bounded immutable bytes bound to one live read descriptor, never media approval."""
import hashlib
import os
from pathlib import Path
import stat


class SnapshotError(ValueError):
    pass


def _identity(status):
    return (status.st_dev, status.st_ino, status.st_mode, status.st_size,
            status.st_mtime_ns, status.st_ctime_ns)


class ReadSnapshot:
    def __init__(self, path, maximum, check):
        self.path = Path(path).absolute()
        self.maximum = maximum
        self.check = check
        self._stream = None
        self._version = None
        self._descriptor_version = None
        self._sha256 = None

    def __enter__(self):
        self.check()
        named = self.path.lstat()
        if not stat.S_ISREG(named.st_mode) or not 0 < named.st_size <= self.maximum:
            raise SnapshotError('unsupported_snapshot_type_or_size')
        self._stream = self._open_readonly()
        try:
            self._version = _identity(named)
            self._descriptor_version = _identity(os.fstat(self._stream.fileno()))
            # Windows path stat and handle stat expose different ctime semantics.
            # Bind shared identity fields, then retain both full versions separately.
            comparable = slice(None, -1) if os.name == 'nt' else slice(None)
            if self._version[comparable] != self._descriptor_version[comparable]:
                raise SnapshotError('snapshot_identity_or_version_changed')
            self.verify()
            return self
        except BaseException:
            self.close()
            raise

    def _open_readonly(self):
        flags = os.O_RDONLY | getattr(os, 'O_BINARY', 0)
        flags |= getattr(os, 'O_NOFOLLOW', 0) | getattr(os, 'O_NONBLOCK', 0)
        descriptor = os.open(self.path, flags)
        try:
            return os.fdopen(descriptor, 'rb', buffering=0)
        except BaseException:
            os.close(descriptor)
            raise

    def __exit__(self, *_failure):
        self.close()

    def close(self):
        if self._stream is not None:
            self._stream.close()

    def verify(self):
        self.check()
        if self._stream is None or self._stream.closed:
            raise SnapshotError('closed_snapshot')
        if (_identity(os.fstat(self._stream.fileno())) != self._descriptor_version or
                _identity(self.path.lstat()) != self._version):
            raise SnapshotError('snapshot_identity_or_version_changed')

    @property
    def sha256(self):
        if self._sha256 is None:
            raise SnapshotError('unread_snapshot')
        self.verify()
        return self._sha256

    def read(self):
        self.verify()
        self._stream.seek(0)
        payload = self._read_exact()
        self.verify()
        digest = hashlib.sha256(payload).hexdigest()
        if self._sha256 is not None and self._sha256 != digest:
            raise SnapshotError('validated_snapshot_content_changed')
        self._sha256 = digest
        return payload

    def _read_exact(self):
        length = self._version[3]
        payload = bytearray(length)
        view = memoryview(payload)
        offset = 0
        while offset < length:
            self.check()
            count = self._stream.readinto(view[offset:min(offset + 65536, length)])
            if not count:
                raise SnapshotError('snapshot_truncated_during_read')
            offset += count
        self.check()
        if self._stream.read(1):
            raise SnapshotError('snapshot_grew_during_read')
        # Immutable bytes ensure parsers cannot change a payload after SHA binding.
        return bytes(payload)
