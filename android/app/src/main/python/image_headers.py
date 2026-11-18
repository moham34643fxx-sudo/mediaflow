"""Bounded image geometry hints, never full-image integrity or publication approval."""
MAX_PREFIX_BYTES = 256 * 1024


def _geometry(extension, width, height):
    return (extension, width, height) if 0 < width <= 32768 and 0 < height <= 32768 else None


def _png(prefix):
    if len(prefix) < 33 or prefix[8:16] != b'\0\0\0\rIHDR':
        return None
    return _geometry('png', int.from_bytes(prefix[16:20], 'big'), int.from_bytes(prefix[20:24], 'big'))


def _webp_extended(prefix, chunk_size):
    if chunk_size != 10 or len(prefix) < 30 or prefix[20] & 0xC3 or prefix[21:24] != b'\0\0\0':
        return None
    return _geometry('webp', 1 + int.from_bytes(prefix[24:27], 'little'), 1 + int.from_bytes(prefix[27:30], 'little'))


def _webp_lossless(prefix, chunk_size):
    if chunk_size < 5 or len(prefix) < 25 or prefix[20] != 0x2F:
        return None
    packed = int.from_bytes(prefix[21:25], 'little')
    return _geometry('webp', 1 + (packed & 0x3FFF), 1 + ((packed >> 14) & 0x3FFF)) if packed >> 29 == 0 else None


def _webp_lossy(prefix, chunk_size):
    if chunk_size < 10 or len(prefix) < 30 or prefix[23:26] != b'\x9d\x01\x2a' or prefix[20] & 1:
        return None
    return _geometry('webp', int.from_bytes(prefix[26:28], 'little') & 0x3FFF, int.from_bytes(prefix[28:30], 'little') & 0x3FFF)


_WEBP_READERS = {b'VP8X': _webp_extended, b'VP8L': _webp_lossless, b'VP8 ': _webp_lossy}


def _webp(prefix):
    if len(prefix) < 20 or prefix[8:12] != b'WEBP':
        return None
    chunk_size = int.from_bytes(prefix[16:20], 'little')
    if int.from_bytes(prefix[4:8], 'little') + 8 < 20 + chunk_size:
        return None
    reader = _WEBP_READERS.get(bytes(prefix[12:16]))
    return reader(prefix, chunk_size) if reader is not None else None


def _jpeg_marker(prefix, offset):
    if offset >= len(prefix) or prefix[offset] != 0xFF:
        return None
    while offset < len(prefix) and prefix[offset] == 0xFF:
        offset += 1
    return (prefix[offset], offset + 1) if offset < len(prefix) else None


def _jpeg_segments(prefix):
    offset = 2
    for _ in range(256):
        current = _jpeg_marker(prefix, offset)
        if current is None:
            return
        marker, offset = current
        if marker in (0xD9, 0xDA):
            return
        if marker == 1 or 0xD0 <= marker <= 0xD8:
            continue
        if offset + 2 > len(prefix):
            return
        size = int.from_bytes(prefix[offset:offset + 2], 'big')
        if size < 2 or offset + size > len(prefix):
            return
        yield marker, prefix[offset + 2:offset + size]
        offset += size


def _jpeg(prefix):
    for marker, frame in _jpeg_segments(prefix):
        if marker in (0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF):
            if len(frame) < 6 or frame[5] not in range(1, 5) or len(frame) != 6 + 3 * frame[5]:
                return None
            return _geometry('jpg', int.from_bytes(frame[3:5], 'big'), int.from_bytes(frame[1:3], 'big'))
    return None


def dimensions(prefix):
    for signature, reader in ((b'\x89PNG\r\n\x1a\n', _png), (b'RIFF', _webp), (b'\xff\xd8', _jpeg)):
        if prefix.startswith(signature):
            return reader(prefix)
    return None


def _prefix_response(engine, url, headers):
    from yt_dlp.networking.common import Request
    from yt_dlp.networking.exceptions import HTTPError
    public_headers = {**headers, 'Accept-Encoding': 'identity'}
    try:
        return engine.urlopen(Request(url, headers={**public_headers, 'Range': f'bytes=0-{MAX_PREFIX_BYTES - 1}'}))
    except HTTPError as error:
        if error.status != 416:
            raise
        error.close()
        return engine.urlopen(Request(url, headers=public_headers))


def probe(engine, url, headers):
    """The caller validates the URL; the engine retains redirect/TLS/read policy."""
    with _prefix_response(engine, url, headers) as response:
        prefix = bytearray()
        while len(prefix) < MAX_PREFIX_BYTES:
            amount = 64 - len(prefix) if len(prefix) < 64 else min(8192, MAX_PREFIX_BYTES - len(prefix))
            chunk = response.read(amount)
            if not chunk:
                return dimensions(prefix)
            prefix.extend(chunk)
            geometry = dimensions(prefix)
            if geometry is not None:
                return geometry
            if len(prefix) >= 64 and not prefix.startswith(b'\xff\xd8'):
                return None
    return None
