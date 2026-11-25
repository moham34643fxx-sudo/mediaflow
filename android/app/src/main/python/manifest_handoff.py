"""One-analysis response handoff, not a URL/video cache or a manifest parser.

GenericIE sniffs a URL before asking the maintained HLS parser to fetch it.
Hand that parser the already fetched, decoded small manifest. Everything is
discarded with the analysis; cookies, headers and proxy routing are part of the
identity. Site selection, live/DRM checks and format parsing remain upstream.
"""
import io
import math
import re
import time
import xml.etree.ElementTree as ET
from urllib.parse import urlsplit, urljoin
from yt_dlp.networking.common import Response

_DASH_NS = '{urn:mpeg:dash:schema:mpd:2011}'
_CHANNEL_SCHEME = 'urn:mpeg:dash:23003:3:audio_channel_configuration:2011'


def _dash_base(node, inherited):
    bases = node.findall(_DASH_NS + 'BaseURL')
    if not bases:
        return inherited
    if len(bases) != 1 or not bases[0].text or not bases[0].text.strip():
        return None
    return urljoin(inherited, bases[0].text.strip()) if inherited else None


def _dash_channel_count(nodes):
    # None means unknown or ambiguous, never the common stereo default.
    if len(nodes) != 1 or nodes[0].get('schemeIdUri') != _CHANNEL_SCHEME:
        return None
    count = nodes[0].get('value', '')
    return int(count) if re.fullmatch(r'[1-9][0-9]?', count) and int(count) <= 32 else None


def _dash_audio_profiles(root, manifest_url):
    profiles = {}
    for period in root.findall(_DASH_NS + 'Period'):
        period_base = _dash_base(period, _dash_base(root, manifest_url))
        for adaptation in period.findall(_DASH_NS + 'AdaptationSet'):
            base = _dash_base(adaptation, period_base)
            for representation in adaptation.findall(_DASH_NS + 'Representation'):
                codec = representation.get('codecs', adaptation.get('codecs'))
                rate = representation.get('audioSamplingRate', adaptation.get('audioSamplingRate'))
                if not codec or not rate or not re.fullmatch(r'[1-9][0-9]{0,5}', rate):
                    continue
                if len(representation.findall(_DASH_NS + 'BaseURL')) != 1:
                    continue
                channels = representation.findall(_DASH_NS + 'AudioChannelConfiguration')
                if not channels:
                    channels = adaptation.findall(_DASH_NS + 'AudioChannelConfiguration')
                key = (representation.get('id'), _dash_base(representation, base), codec, int(rate))
                profiles.setdefault(key, set()).add(_dash_channel_count(channels))
    return profiles


def _dash_document_root(document):
    if len(document) > 512 * 1024 or re.search(br'<!\s*(?:DOCTYPE|ENTITY)\b', document, re.I):
        return None
    try:
        root = ET.fromstring(document)
    except ET.ParseError:
        return None
    return root if root.tag == _DASH_NS + 'MPD' else None


def _enrich_dash_channels(document, manifest_url, formats):
    root = _dash_document_root(document)
    if root is None or root.get('type', 'static') != 'static':
        return
    profiles = _dash_audio_profiles(root, manifest_url)
    for fmt in formats:
        if fmt.get('audio_channels') is not None or fmt.get('manifest_url') not in (None, manifest_url):
            continue
        key = (fmt.get('format_id'), fmt.get('url'), fmt.get('acodec'), fmt.get('asr'))
        counts = profiles.get(key, set())
        if len(counts) == 1 and None not in counts and key[1] is not None:
            fmt['audio_channels'] = next(iter(counts))


class MeasuredResponse(Response):
    def __init__(self, original, row):
        super().__init__(original, original.url, original.headers, original.status,
                         original.reason, original.extensions)
        self.row = row

    def read(self, size=-1):
        start = time.monotonic()
        try:
            result = self.fp.read(size)
            self.row['bytes_read'] += len(result)
            return result
        finally:
            self.row['body_ms'] += (time.monotonic() - start) * 1000


class PrefixedReader(io.IOBase):
    """Return an over-budget prefix and then the original stream without loss."""
    def __init__(self, prefix, remaining):
        self.prefix, self.remaining = io.BytesIO(prefix), remaining

    def read(self, size=-1):
        if size is None:
            size = -1
        first = self.prefix.read(size)
        if size < 0:
            return first + self.remaining.read()
        return first + self.remaining.read(size - len(first)) if len(first) < size else first

    def close(self):
        self.remaining.close()
        super().close()


class ManifestHandoff:
    MAX_DOCUMENT = 512 * 1024
    MAX_TOTAL = 1024 * 1024

    def __init__(self):
        self.documents = {}
        self.bytes = 0
        self.reused = 0
        self.trace = []
        self.network_ms = 0

    @staticmethod
    def identity(request, cookiejar):
        if request.method != 'GET' or request.data is not None or request.extensions:
            return None
        # Decoded bytes are independent of transport compression. All other
        # request headers, including Referer and Authorization, remain significant.
        headers = tuple(sorted((k.lower(), v) for k, v in request.headers.items()
                               if k.lower() != 'accept-encoding'))
        if any(k == 'range' for k, _ in headers):
            return None
        cookies = tuple(sorted((c.domain, c.path, c.name, c.value, c.secure, c.expires or 0)
                               for c in cookiejar))
        return request.url, headers, cookies, tuple(sorted(request.proxies.items()))

    def send(self, sender, request, cookiejar):
        key = self.identity(request, cookiejar)
        if key is not None and key in self.documents:
            document, url, headers, status, reason, extensions = self.documents[key]
            self.reused += 1
            return Response(io.BytesIO(document), url, headers, status, reason, extensions)
        parsed = urlsplit(request.url)
        row = {'method': request.method, 'headers_ms': 0, 'body_ms': 0, 'bytes_read': 0}
        if len(self.trace) < 32:
            self.trace.append(row)
        start = time.monotonic()
        try:
            original = sender(request)
            row['status'] = original.status
        except Exception as exc:
            row['error_type'] = type(exc).__name__
            status = getattr(exc, 'status', None)
            if isinstance(status, int):
                row['status'] = status
            raise
        finally:
            row['headers_ms'] = (time.monotonic() - start) * 1000
            self.network_ms += row['headers_ms']
        response = MeasuredResponse(original, row)
        mime = response.get_header('Content-Type', '').split(';')[0].strip().lower()
        # No webpage scraping, no extension-only assumption about media contents.
        candidate = parsed.path.lower().endswith(('.m3u8', '.mpd')) or mime in (
            'application/vnd.apple.mpegurl', 'application/x-mpegurl', 'application/dash+xml')
        if (key is None or not candidate or response.status != 200
                or response.get_header('Set-Cookie') or self.bytes >= self.MAX_TOTAL):
            return response
        limit = min(self.MAX_DOCUMENT, self.MAX_TOTAL - self.bytes)
        prefix = response.read(limit + 1)
        if len(prefix) > limit:
            return Response(PrefixedReader(prefix, response), response.url, response.headers,
                            response.status, response.reason, response.extensions)
        # Only genuine standard manifest bytes qualify. HTML/JSON error pages do not.
        evidence = prefix.lstrip(b'\xef\xbb\xbf \t\r\n')
        if evidence.startswith(b'#EXTM3U') or _dash_document_root(prefix) is not None:
            self.documents[key] = (prefix, response.url, response.headers, response.status,
                                   response.reason, response.extensions)
            self.bytes += len(prefix)
        response.close()
        return Response(io.BytesIO(prefix), response.url, response.headers,
                        response.status, response.reason, response.extensions)

    def timings(self):
        return {'manifest_handoffs': self.reused,
                'body_read': round(sum(row['body_ms'] for row in self.trace)),
                'request_trace': [{k: round(v, 2) if isinstance(v, float) else v
                                    for k, v in row.items()} for row in self.trace]}

    def enrich_metadata(self, info, formats, source_url):
        """Size/duration hints from bytes already fetched by the upstream parsers.

        Never creates formats, fetches variants/segments, or substitutes for the
        maintained HLS/DASH parsers. A master AVERAGE-BANDWIDTH includes audio.
        """
        durations, averages = {}, {}
        for document, url, _, _, _, _ in self.documents.values():
            text = document.decode('utf-8-sig', errors='replace')
            if text.lstrip().startswith('#EXTM3U'):
                if '#EXT-X-ENDLIST' in text:
                    values = [float(v) for v in re.findall(r'(?m)^#EXTINF:([0-9.]+)', text)
                              if re.fullmatch(r'\d+(?:\.\d+)?', v)]
                    duration = sum(values)
                    if values and math.isfinite(duration) and duration > 0:
                        durations[url] = duration
                rate = None
                for line in map(str.strip, text.splitlines()):
                    if line.startswith('#EXT-X-STREAM-INF:'):
                        match = re.search(r'(?:^|,)AVERAGE-BANDWIDTH=(\d{1,12})(?=,|$)', line.partition(':')[2])
                        rate = int(match[1]) / 1000 if match else None
                    elif line and not line.startswith('#') and rate:
                        if math.isfinite(rate) and rate > 0:
                            averages[urljoin(url, line)] = rate
                        rate = None
            elif '<MPD' in text and not re.search(r'type\s*=\s*["\']dynamic["\']', text):
                if all(other == document for other, other_url, *_ in self.documents.values() if other_url == url):
                    _enrich_dash_channels(document, url, formats)
                match = re.search(r'mediaPresentationDuration\s*=\s*["\']PT(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?["\']', text)
                if match:
                    duration = sum(float(v or 0) * scale for v, scale in zip(match.groups(), (3600, 60, 1)))
                    if math.isfinite(duration) and duration > 0:
                        durations[url] = duration
        for fmt in formats:
            rate = averages.get(fmt.get('url'))
            if rate:
                fmt['combined_tbr'] = rate
        if info.get('duration') is None and source_url in durations:
            info['duration'] = durations[source_url]
