"""Versioned JSON boundary. No shell execution or externally downloaded code."""
import json
import base64
import socket
import ipaddress
from urllib.parse import urlsplit
import yt_dlp
from analysis_engine import AnalysisYoutubeDL
from manifest_handoff import ManifestHandoff
from media_result import first_media
from format_dimensions import reject_label_dimensions
from yt_dlp.version import __version__
import threading
import traceback
import re
import time
import collections.abc
import itertools
import zlib
from metadata_limits import LimitedReader, bounded_decompress
from native_response_budget import NativeResponseBudget
from metadata_transport import open_response
import android_jsc
from network_errors import classify, exception_facts
from java import jclass
AndroidNetwork = jclass('com.zdownloader.app.engine.AndroidNetworkBridge')
from yt_dlp.networking._urllib import HTTPHandler, RedirectHandler
from yt_dlp.networking._urllib import ProxyHandler
import urllib.request
from address_policy import public_address
from yt_dlp.networking.common import Request, RequestDirector
from yt_dlp.networking.exceptions import HTTPError, RequestError

_dns = socket.getaddrinfo


def checkpoint(request_id, stage):
    # Correlation IDs and stage names only: never URLs, tokens or upstream exception text.
    try:
        jclass('com.zdownloader.app.engine.AnalysisTrace').checkpoint(request_id, stage)
    except Exception:
        pass  # Diagnostics must not change extraction outcomes.


def public_dns(*args, **kwargs):
    event = getattr(_current, 'event', None)
    if event: check(event)
    started = time.monotonic()
    if event: checkpoint(getattr(event, 'request_id', ''), 'python_dns_start')
    results = _dns(*args, **kwargs)
    results = sorted(results, key=lambda r: r[0] != socket.AF_INET)
    if event: checkpoint(getattr(event, 'request_id', ''), 'python_dns_finished')
    if event: event.dns_ms += (time.monotonic() - started) * 1000
    if event: check(event)
    if args[0] != getattr(_current, 'proxy_host', None) and any(not public_address(r[4][0].split('%')[0]) for r in results):
        raise ValueError('POLICY_ADDRESS')
    return results


# urllib, redirects, manifests and CDN requests all pass this resolver.
socket.getaddrinfo = public_dns
_requests = {}
_lock = threading.Lock()
_cancelled = {}
_current = threading.local()


def cancel(request_id):
    with _lock:
        _cancelled[request_id] = time.monotonic()
        event = _requests.get(request_id)
        if event:
            event.set()
    # Never run transport interruption while holding the request-registry lock.
    owner = getattr(event, 'response_owner', None)
    if owner is not None:
        owner.cancel()
    close_metadata_transport(event)


def register_request(request_id):
    event = threading.Event()
    event.request_id = request_id
    event.deadline = time.monotonic() + 85
    event.network_ms = 0.0
    event.dns_ms = 0.0
    event.requests = 0
    event.redirects = 0
    event.transport_sequence = 0
    event.metadata = True
    event.metadata_bytes = 0
    with _lock:
        now = time.monotonic()
        for key, timestamp in list(_cancelled.items()):
            if now - timestamp > 120:
                _cancelled.pop(key, None)
        if _cancelled.pop(request_id, None) is not None:
            event.set()
        _requests[request_id] = event
    _current.event = event
    check(event)
    return event


def check(event):
    if event.is_set():
        raise InterruptedError('DOWNLOAD_INTERRUPTED')
    if time.monotonic() > getattr(event, 'deadline', float('inf')):
        raise TimeoutError('EXTRACTION_TIMEOUT')


def validate_url(url):
    if not isinstance(url, str) or len(url) > 64 * 1024 or any(ord(c) < 32 or ord(c) == 127 for c in url):
        raise ValueError('INVALID_URL')
    parsed = urlsplit(url)
    if (parsed.scheme != 'https' or not parsed.hostname or parsed.username is not None or parsed.password is not None
            or (parsed.port is not None and not 1 <= parsed.port <= 65535) or parsed.netloc.endswith(':')):
        raise ValueError('INVALID_URL')
    # Resolve only at socket connection, not three times per redirect/request.
    # Literal addresses are checked here as well as by the socket resolver.
    try: address = ipaddress.ip_address(parsed.hostname)
    except ValueError: address = None
    if address is not None and not public_address(str(address)): raise ValueError('POLICY_ADDRESS')
    return url


_http_request = HTTPHandler.http_request
_director_send = RequestDirector.send
_redirect = RedirectHandler.redirect_request
_http_response = HTTPHandler.http_response
_http_open = HTTPHandler.do_open
_gzip = HTTPHandler.gz
_deflate = HTTPHandler.deflate


def pooled_metadata_open(self, http_class, request, **kwargs):
    event = getattr(_current, 'event', None)
    # Native downloads/probes and proxy/SOCKS connections retain their existing transport.
    # Re-evaluation on every redirect stays with the upstream per-URI proxy handler.
    if (event is None or not getattr(event, 'pooled_metadata', False)
            or request.type != 'https' or request.has_proxy()
            or self._source_address is not None
            or (request.data is not None and request.get_method() in ('GET', 'HEAD'))
            or getattr(_current, 'proxy_host', None)
            or request.has_header('Ytdl-socks-proxy')):
        return _http_open(self, http_class, request, **kwargs)
    check(event)
    bridge = getattr(event, 'metadata_transport', None)
    if bridge is None:
        bridge = jclass('com.zdownloader.app.engine.MetadataHttpBridge')()
        event.metadata_transport = bridge
    if event.is_set():
        bridge.close()
        check(event)
    remaining = min(request.timeout, event.deadline - time.monotonic())
    operation = f'{event.request_id}:http:{event.transport_sequence}'
    event.transport_sequence += 1
    response = open_response(bridge, request, {'timeout_ms': max(1, int(remaining * 1000)), 'operation': operation})
    try:
        check(event)
    except Exception:
        response.close()
        raise
    return response


def close_metadata_transport(event):
    bridge = getattr(event, 'metadata_transport', None)
    if bridge is not None:
        bridge.close()


def secure_http_response(self, request, response):
    event = getattr(_current, 'event', None)
    if event and getattr(event, 'metadata', False):
        # Bound compressed bytes BEFORE upstream eager decompression, then the
        # decoded body as well. Wrapping RequestDirector alone is too late.
        response = _http_response(self, request, LimitedReader(response, event, check))
        return LimitedReader(response, event, check)
    return _http_response(self, request, response)


def secure_gzip(data):
    event = getattr(_current, 'event', None)
    return bounded_decompress(data, zlib.MAX_WBITS | 16) if event and getattr(event, 'metadata', False) else _gzip(data)


def secure_deflate(data):
    event = getattr(_current, 'event', None)
    if not event or not getattr(event, 'metadata', False):
        return _deflate(data)
    try:
        return bounded_decompress(data, -zlib.MAX_WBITS)
    except zlib.error:
        return bounded_decompress(data, zlib.MAX_WBITS)


def secure_http_request(self, request):
    validate_url(request.full_url)
    return _http_request(self, request)


def secure_send(self, request):
    event = getattr(_current, 'event', None)
    if event: check(event)
    validate_url(request.url)
    if event: checkpoint(getattr(event, 'request_id', ''), 'python_proxy_start')
    proxy = str(AndroidNetwork.proxyFor(request.url))
    if event: checkpoint(getattr(event, 'request_id', ''), 'python_http_start')
    _current.proxy_host = urlsplit(proxy).hostname if proxy else None
    # A nonempty mapping with None is yt-dlp's explicit DIRECT decision. {} would
    # fall back to handler/environment proxies (see RequestHandler._get_proxies).
    request.proxies = {'all': proxy or None}
    started = time.monotonic()
    handoff = getattr(event, 'manifest_handoff', None)
    headers_before = handoff.network_ms if handoff else 0
    try:
        response = (handoff.send(lambda req: _director_send(self, req), request, event.cookiejar)
                    if handoff else _director_send(self, request))
    finally:
        if event:
            event.network_ms += (handoff.network_ms - headers_before if handoff
                                 else (time.monotonic() - started) * 1000)
            event.requests += 1
    if event:
        checkpoint(getattr(event, 'request_id', ''), 'python_http_finished')
        try: check(event)
        except Exception:
            response.close()
            raise
    return response


def secure_redirect(self, request, fp, code, message, headers, url):
    event = getattr(_current, 'event', None)
    if event: event.redirects += 1
    validate_url(url)
    redirected = _redirect(self, request, fp, code, message, headers, url)
    old, new = urlsplit(request.full_url), urlsplit(url)
    if (old.scheme, old.hostname, old.port or 443) != (new.scheme, new.hostname, new.port or 443):
        # Upstream urllib strips Cookie but retains Authorization. Never carry
        # request credentials to a different origin; scoped cookiejar may add
        # cookies independently if it actually authorizes the new destination.
        safe_headers = {'user-agent', 'accept', 'accept-language', 'accept-encoding',
                        'referer', 'origin', 'range', 'if-range', 'content-type',
                        'content-length', 'connection', 'cache-control', 'pragma'}
        for name in list(redirected.headers) + list(redirected.unredirected_hdrs):
            # Credential names are not limited to Authorization: extractors can
            # use arbitrary API/session headers. Preserve only public transport
            # and browser-context headers across origins, not a token denylist.
            if name.lower() not in safe_headers and not name.lower().startswith(('sec-ch-', 'sec-fetch-')):
                redirected.remove_header(name)
    return redirected


def system_proxy_open(self, request):
    """urllib invokes this on *each* request including redirects within an opener."""
    validate_url(request.full_url)
    proxy = str(AndroidNetwork.proxyFor(request.full_url))
    _current.proxy_host = urlsplit(proxy).hostname if proxy else None
    request.remove_header('Ytdl-socks-proxy')
    if not proxy:
        return None
    if urlsplit(proxy).scheme in ('socks4', 'socks4a', 'socks5', 'socks5h'):
        request.add_header('Ytdl-socks-proxy', proxy)
        return None
    return urllib.request.ProxyHandler.proxy_open(self, request, proxy, None)


HTTPHandler.http_request = HTTPHandler.https_request = secure_http_request
HTTPHandler.do_open = pooled_metadata_open
HTTPHandler.http_response = HTTPHandler.https_response = secure_http_response
HTTPHandler.gz = staticmethod(secure_gzip)
HTTPHandler.deflate = staticmethod(secure_deflate)
RequestDirector.send = secure_send
ProxyHandler.proxy_open = system_proxy_open
RedirectHandler.redirect_request = secure_redirect
RedirectHandler.max_redirections = 5
RedirectHandler.max_repeats = 5


class QuietLogger:
    def __init__(self, event=None): self.event = event
    def debug(self, message):
        if self.event: check(self.event)
    def warning(self, message):
        self.debug(message)
        codes = getattr(self.event, 'quality_warnings', None)
        if codes is not None:
            from quality_audit import warning_code
            codes.add(warning_code(message))
    error = debug


def engine_version():
    return json.dumps({'interface': 1, 'extractor': __version__, 'js_runtime': 'QuickJS JNI / EJS 0.8.0'})


def analyze_quality_audit(url, request_id='quality-audit'):
    return analyze(url, request_id, quality_audit=True)


def _is_facebook_source(url):
    try:
        host = (urlsplit(url).hostname or '').lower().rstrip('.')
    except (TypeError, ValueError):
        return False
    return any(host == domain or host.endswith('.' + domain)
               for domain in ('facebook.com', 'fb.watch', 'fbcdn.net', 'fbsbx.com'))


def analyze(url, request_id='test', force_web=False, quality_audit=False):
    started = time.monotonic()
    event = threading.Event()
    try:
        event = register_request(request_id)
        if quality_audit:
            event.quality_warnings = set()
        checkpoint(request_id, 'python_request_registered')
        event.manifest_handoff = ManifestHandoff()
        event.pooled_metadata = True
        android_jsc.reset_stats()
        validate_url(url)
        with AnalysisYoutubeDL({'quiet': True, 'logger': QuietLogger(event), 'socket_timeout': 20,
                              'retries': 1, 'extractor_retries': 1, 'noplaylist': True,
                              'playlistend': 1, 'lazy_playlist': True,
                              'nocheckcertificate': False, 'cachedir': False, 'check_formats': False,
                              'extractor_args': {'youtube': {'player_client': ['web'] if force_web else ['default', '-tv_samsung', 'android']}},
                              'remote_components': [], 'js_runtimes': {}}) as engine:
            android_jsc.configure(engine)
            from facebook_source_formats import configure as configure_source_formats
            configure_source_formats(engine, url)
            from tiktok_media import configure as configure_tiktok
            configure_tiktok(engine, validate_url)
            from instagram_media import configure as configure_instagram
            configure_instagram(engine, validate_url)
            from twitter_media import configure as configure_twitter
            configure_twitter(engine, url, validate_url)
            from topcinema_media import configure as configure_topcinema
            configure_topcinema(engine, url, validate_url)
            event.cookiejar = engine.cookiejar
            initialized = time.monotonic()
            checkpoint(request_id, 'ytdlp_extract_start')
            raw = engine.extract_info(url, download=False)
            checkpoint(request_id, 'ytdlp_extract_finished')
            if isinstance(raw, dict) and (raw.get('_type') in ('playlist', 'multi_video') or raw.get('entries') is not None):
                return json.dumps({'error': 'PLAYLIST_DETECTED'})
            info = first_media(raw)
            extracted = time.monotonic()
            if info.get('has_drm'):
                return json.dumps({'error': 'DRM_PROTECTED'})
            if info.get('is_live') or info.get('live_status') in ('is_live', 'is_upcoming'):
                return json.dumps({'error': 'LIVE_STREAM_DETECTED'})
            formats = []
            for fmt in itertools.islice(info.get('formats') or [info], 1000):
                check(event)
                if not fmt.get('url') or fmt.get('has_drm'):
                    continue
                if fmt.get('protocol') == 'mhtml':
                    continue
                if fmt.get('is_live') or fmt.get('request_data') is not None:
                    continue
                entry = {k: fmt.get(k) for k in (
                    'format_id', 'format', 'format_note', 'resolution', 'url', 'ext', 'video_ext', 'audio_ext', 'width', 'height', 'fps', 'vcodec', 'acodec',
                    'filesize', 'filesize_approx', 'tbr', 'vbr', 'abr', 'protocol', 'http_headers', 'downloader_options', 'dynamic_range',
                    'language', 'language_preference', 'audio_channels', 'asr', 'cookies', 'mediaflow_source_quality_label', 'mediaflow_slideshow', 'mediaflow_image', 'mediaflow_companion_audio',
                    'fragments', 'fragment_base_url', 'manifest_url', 'hls_media_playlist_data',
                    'extra_param_to_segment_url', 'extra_param_to_key_url', 'hls_aes',
                      'is_live', 'is_from_start', 'no_resume')}
                reject_label_dimensions(entry, info, url)
                from media_kind import normalize_source
                from image_headers import probe as probe_image
                entry = normalize_source(entry, lambda source: probe_image(engine, source['url'],
                    source.get('http_headers') or info.get('http_headers') or {}))
                if entry is None:
                    continue
                # Match yt-dlp's neutral language rank. Kotlin treats a missing
                # value as zero, which otherwise promotes unknown HLS audio over
                # the same language's higher-quality direct tracks ranked -1.
                if entry['language_preference'] is None:
                    entry['language_preference'] = -1
                fragments = entry.get('fragments')
                if fragments is not None and not isinstance(fragments, list):
                    if callable(fragments) or not isinstance(fragments, collections.abc.Iterable):
                        continue
                    entry['fragments'] = list(itertools.islice(fragments, 20001))
                if entry.get('fragments') is not None and len(entry['fragments']) > 20000:
                    continue
                # Never forward a null downloader_options: yt-dlp calls
                # info_dict.get('downloader_options', {}).get(...) which throws
                # when the key exists with a null value. Keep only valid int chunks.
                options = entry.get('downloader_options')
                if isinstance(options, dict):
                    chunk = options.get('http_chunk_size')
                    entry['downloader_options'] = {'http_chunk_size': int(chunk)} if isinstance(chunk, int) and chunk > 0 else {}
                    if not entry['downloader_options']:
                        entry.pop('downloader_options', None)
                else:
                    entry.pop('downloader_options', None)
                try:
                    json.dumps(entry)
                except (TypeError, ValueError):
                    continue
                formats.append(entry)
            check(event)
            if not formats:
                return json.dumps({'error': 'FORMAT_UNAVAILABLE'})
            try:
                event.manifest_handoff.enrich_metadata(info, formats, url)
            except (ValueError, OverflowError, TypeError):
                checkpoint(request_id, 'optional_size_hints_unavailable')
            checkpoint(request_id, 'normalized_formats_ready')
            subtitles = []
            subtitle_map = info.get('subtitles')
            subtitle_map = subtitle_map if isinstance(subtitle_map, dict) else {}
            for language, tracks in itertools.islice(subtitle_map.items(), 100):
                if not isinstance(language, str) or not re.fullmatch(r'[a-zA-Z0-9_-]{1,40}', language):
                    continue
                for track in itertools.islice(tracks if isinstance(tracks, (list, tuple)) else [], 12):
                    if not isinstance(track, dict) or track.get('ext') not in ('srt', 'vtt'):
                        continue
                    try:
                        validate_url(track.get('url'))
                    except (ValueError, TypeError):
                        continue
                    payload = {'url': track['url'], 'ext': track['ext'], 'format_id': 'subtitle-' + language,
                               'protocol': 'https', 'vcodec': 'none', 'acodec': 'none'}
                    # Only public, noncredential headers. Protected caption tracks are
                    # not advertised unless their transport can really be handed off.
                    headers = track.get('http_headers') or info.get('http_headers') or {}
                    if not isinstance(headers, dict) or any(not isinstance(k, str) or not isinstance(v, str) or len(k) > 128 or len(v) > 2048 or k.lower() not in ('user-agent', 'referer', 'origin', 'accept', 'accept-language') for k, v in headers.items()):
                        continue
                    payload['http_headers'] = headers
                    subtitles.append({'language': language, 'extension': track['ext'], 'payload': payload})
                    break
            audit = {}
            if quality_audit:
                from quality_audit import inventory
                audit['quality_audit'] = inventory(info, formats)
                audit['quality_audit']['warningCodes'] = sorted(event.quality_warnings)
            return json.dumps({'interface': 1, 'id': info.get('id'), 'title': info.get('title'),
                               'url': info.get('webpage_url') or url, 'thumbnail': info.get('thumbnail'),
                               'duration': info.get('duration'), 'uploader': info.get('uploader'),
                               'source': info.get('extractor_key'), 'formats': formats, 'subtitles': subtitles,
                                'js_challenges': android_jsc.stats(), **audit,
                               'timings_ms': {'engine_setup': round((initialized-started)*1000),
                                              'extraction': round((extracted-initialized)*1000),
                                                 'normalization': round((time.monotonic()-extracted)*1000),
                                                'network_headers_inclusive': round(event.network_ms),
                                                 'dns_inclusive': round(event.dns_ms),
                                                 'requests': event.requests - event.manifest_handoff.reused,
                                                'redirects': event.redirects,
                                                **event.manifest_handoff.timings()}}, ensure_ascii=False)
    except Exception as exc:
        code, stage = classify(exc, platform='facebook' if _is_facebook_source(url) else None)
        if isinstance(exc, InterruptedError): code = 'DOWNLOAD_INTERRUPTED'
        try:
            from java import jclass
            jclass('android.util.Log').e('ZAnalysis', f'zextractor.analyze failed: code={code}, stage={stage}, exc={type(exc).__name__}')
        except Exception:
            pass
        return json.dumps({'error': code, 'stage': stage, 'diagnostic': type(exc).__name__,
                           'exception_chain': exception_facts(exc),
                           **({'quality_audit': {'warningCodes': sorted(getattr(event, 'quality_warnings', ()))}} if quality_audit else {}),
                           'js_challenges': android_jsc.stats(),
                           'timings_ms': {'total': round((time.monotonic()-started)*1000),
                                          'network_headers_inclusive': round(getattr(event, 'network_ms', 0)),
                                          'dns_inclusive': round(getattr(event, 'dns_ms', 0)),
                                          **event.manifest_handoff.timings()} if hasattr(event, 'manifest_handoff') else {}})
    finally:
        close_metadata_transport(event)
        _current.event = None
        _current.proxy_host = None
        with _lock: _requests.pop(request_id, None)


def playlist(url, request_id, offset=0, limit=100):
    from playlist_result import options, page
    started = time.monotonic()
    engine_ready = None
    extraction_started = None
    extraction_finished = None
    normalization_started = None
    normalization_finished = None
    event = threading.Event()
    def timings():
        finished = time.monotonic()
        def elapsed(begin, end):
            return round(max(0.0, end - begin) * 1000) if begin is not None and end is not None else 0
        return {'total': elapsed(started, finished),
                'engine_setup': elapsed(started, engine_ready),
                'extraction': elapsed(extraction_started, extraction_finished),
                'normalization': elapsed(normalization_started, normalization_finished),
                'network_headers_inclusive': round(getattr(event, 'network_ms', 0)),
                'dns_inclusive': round(getattr(event, 'dns_ms', 0)),
                'requests': getattr(event, 'requests', 0),
                'redirects': getattr(event, 'redirects', 0)}
    try:
        event = register_request(request_id)
        checkpoint(request_id, 'playlist_request_registered')
        event.pooled_metadata = True
        validate_url(url)
        checkpoint(request_id, 'playlist_url_validated')
        with AnalysisYoutubeDL({'quiet': True, 'logger': QuietLogger(event), 'socket_timeout': 20,
                               'retries': 1, 'extractor_retries': 1, 'nocheckcertificate': False,
                               'cachedir': False, 'remote_components': [], 'js_runtimes': {},
                               'extractor_args': {'youtube': {'player_client': ['default', '-tv_samsung', 'android']}},
                                 **options(offset, limit)}) as engine:
            engine_ready = time.monotonic()
            android_jsc.configure(engine)
            from instagram_media import configure as configure_instagram
            configure_instagram(engine, validate_url)
            from topcinema_media import configure as configure_topcinema
            configure_topcinema(engine, url, validate_url)
            event.cookiejar = engine.cookiejar
            extraction_started = time.monotonic()
            checkpoint(request_id, 'playlist_extract_started')
            info = engine.extract_info(url, download=False)
            extraction_finished = time.monotonic()
            checkpoint(request_id, 'playlist_extract_finished')
            normalization_started = time.monotonic()
            result = page(info, url, int(offset), int(limit), validate_url, lambda: check(event))
            normalization_finished = time.monotonic()
            result['timings_ms'] = timings()
            checkpoint(request_id, 'playlist_page_ready')
            return json.dumps(result, ensure_ascii=False)
    except Exception as exc:
        code, stage = classify(exc, platform='facebook' if _is_facebook_source(url) else None)
        if isinstance(exc, InterruptedError): code = 'DOWNLOAD_INTERRUPTED'
        checkpoint(request_id, 'playlist_failed')
        return json.dumps({'error': code, 'stage': stage, 'timings_ms': timings()})
    finally:
        close_metadata_transport(event)
        _current.event = None
        _current.proxy_host = None
        with _lock: _requests.pop(request_id, None)
def download_stream(format_json, path, request_id, callback, fragment_concurrency=0, initial_bytes=0):
    event = threading.Event()
    media_url = None
    try:
        event = register_request(request_id)
        # A media transfer may legitimately outlast the metadata deadline.
        event.deadline = float('inf')
        event.metadata = False
        fmt = json.loads(format_json)
        media_url = fmt.get('url')
        validate_url(fmt['url'])
        initial_bytes = max(0, int(initial_bytes or 0))
        first_new_byte_reported = False
        last_progress_time = 0.0
        def progress(data):
            check(event)
            nonlocal last_progress_time, first_new_byte_reported
            now = time.monotonic()
            status = data.get('status')
            downloaded = data.get('downloaded_bytes') or 0
            try:
                downloaded = int(downloaded)
            except (TypeError, ValueError, OverflowError):
                downloaded = 0
            if not first_new_byte_reported and downloaded > initial_bytes:
                first_new_byte_reported = True
                callback.firstNewByte()
            if status == 'finished' or now - last_progress_time >= 0.2:
                last_progress_time = now
                callback.confidence('EXACT' if data.get('total_bytes') else 'ESTIMATED' if data.get('total_bytes_estimate') else 'UNKNOWN')
                callback.progress(str(downloaded),
                                  str(data.get('total_bytes') or data.get('total_bytes_estimate') or 0))
        info = dict(fmt, id=request_id, title=request_id)
        fragment_concurrency = int(fragment_concurrency)
        if fragment_concurrency <= 0:
            # The queue shares this budget across actual downloads/tracks and
            # lowers it on constrained devices. Do not silently clamp it to eight.
            fragment_concurrency = 32
        fragment_concurrency = max(1, min(32, fragment_concurrency))
        with yt_dlp.YoutubeDL({'quiet': True, 'logger': QuietLogger(event), 'socket_timeout': 20,
                              'retries': 2, 'fragment_retries': 2, 'continuedl': True,
                              'skip_unavailable_fragments': False,
                              'concurrent_fragment_downloads': fragment_concurrency,
                              'outtmpl': path, 'fixup': 'never', 'hls_prefer_native': True,
                              'http_chunk_size': 2097152,
                              'buffersize': 1024 * 512,
                              'progress_hooks': [progress], 'cachedir': False,
                               'remote_components': [], 'js_runtimes': {}}) as engine, NativeResponseBudget(engine, lambda: check(event)) as responses:
            event.response_owner = responses
            # yt-dlp intentionally keeps Cookie out of http_headers. Restore its
            # domain/path/expiry-scoped serialized cookies into this isolated
            # downloader, just as --load-info-json does. Never use a global jar.
            info['http_headers'] = engine._calc_headers(info, load_cookies=True)
            engine.process_info(info)
        check(event)
        return json.dumps({'ok': True})
    except Exception as exc:
        code, stage = classify(
            exc, 'HTTP_ERROR',
            platform='facebook' if _is_facebook_source(media_url) else None
        )
        frames = [{'frame': f.name, 'line': f.lineno} for f in traceback.extract_tb(exc.__traceback__, limit=6)]
        return json.dumps({'error': 'DOWNLOAD_INTERRUPTED' if event.is_set() else code,
                           'stage': stage, 'diagnostic': type(exc).__name__, 'exception_chain': exception_facts(exc), 'trace': frames})
    finally:
        event.response_owner = None
        _current.event = None
        _current.proxy_host = None
        with _lock: _requests.pop(request_id, None)


def probe_format(format_payload_json, request_id='probe'):
    observation, _ = _format_prefix(format_payload_json, request_id)
    return json.dumps(observation)


def format_header(format_payload_json, request_id):
    """Internal parser handoff only; media bytes must never enter diagnostics."""
    _, prefix = _format_prefix(format_payload_json, request_id)
    return json.dumps(dict(schema='mediaflow_format_header_v1',
                           prefix=base64.b64encode(prefix).decode('ascii')))


def _format_prefix(format_payload_json, request_id):
    """One bounded prefix for diagnostics/parser. I/O has an 8s socket-timeout tail."""
    result = dict(schema='tiktok_format_probe_v1', outcome='INVALID_INPUT',
                  httpStatus=0, bytesRead=0, mediaSignature='other', scopedCookiesLoaded=False)
    prefix = b''
    if not isinstance(format_payload_json, str) or len(format_payload_json) > 512 * 1024:
        return result, prefix
    try:
        fmt = json.loads(format_payload_json)
    except (ValueError, RecursionError):
        return result, prefix

    if not isinstance(fmt, dict) or not isinstance(fmt.get('url'), str):
        return result, prefix

    url = fmt['url']
    if fmt.get('protocol') not in (None, 'http', 'https') or not url.startswith(('http://', 'https://')):
        result['outcome'] = 'INVALID_TRANSPORT'
        return result, prefix

    try:
        validate_url(url)
    except ValueError:
        result['outcome'] = 'POLICY_REJECTED'
        return result, prefix

    raw_headers = fmt.get('http_headers')
    public_headers = {}
    if isinstance(raw_headers, dict):
        allowed_names = {'user-agent', 'referer', 'origin', 'accept', 'accept-language'}
        for k, v in itertools.islice(raw_headers.items(), 16):
            if isinstance(k, str) and isinstance(v, str):
                lower_k = k.lower()
                if lower_k in allowed_names and len(v) <= 2048 and '\r' not in v and '\n' not in v:
                    public_headers[k] = v

    response = None

    try:
        event = register_request(request_id)
        event.deadline = time.monotonic() + 10
        event.metadata = True

        ydl_opts = {
            'quiet': True,
            'logger': QuietLogger(event),
            'socket_timeout': 8,
            'retries': 0,
            'cachedir': False,
            'remote_components': [],
            'js_runtimes': {},
        }

        with yt_dlp.YoutubeDL(ydl_opts) as engine:
            info = {
                'url': url,
                'http_headers': public_headers,
            }
            if 'cookies' in fmt and isinstance(fmt['cookies'], str):
                info['cookies'] = fmt['cookies']

            calc_headers = engine._calc_headers(info, load_cookies=True)
            result['scopedCookiesLoaded'] = bool(engine.cookiejar.get_cookies_for_url(url))

            headers = dict(calc_headers)
            headers['Range'] = 'bytes=0-65535'
            headers['Accept-Encoding'] = 'identity'

            req = Request(url, headers=headers)

            response = engine.urlopen(req)
            check(event)
            status = getattr(response, 'status', None)
            if type(status) is not int or not 100 <= status <= 599:
                result['outcome'] = 'INVALID_RESPONSE'
            else:
                result['httpStatus'] = status
                result['outcome'] = 'HTTP_ERROR'
                if status in (200, 206):
                    chunk = response.read(65536)
                    check(event)
                    result['bytesRead'] = len(chunk)
                    if len(chunk) >= 12 and chunk[4:8] == b'ftyp':
                        result['mediaSignature'] = 'mp4'
                    elif chunk[:4] == b'\x1a\x45\xdf\xa3':
                        result['mediaSignature'] = 'webm'
                    result['outcome'] = 'SUCCESS'
                    content_range = response.headers.get('Content-Range', '')
                    valid_range = re.fullmatch(r'bytes 0-(\d+)/(\d+|\*)', content_range)
                    mime = response.headers.get('Content-Type', '').split(';')[0].strip().lower()
                    encoding = response.headers.get('Content-Encoding', 'identity').lower()
                    if (encoding == 'identity' and mime in ('video/mp4', 'application/mp4', 'application/octet-stream', '')
                            and (status == 200 or (valid_range and int(valid_range[1]) + 1 == len(chunk)))):
                        prefix = chunk
    except HTTPError as exc:
        response = exc.response
        result['httpStatus'] = exc.status
        result['outcome'] = ('HTTP_FORBIDDEN' if exc.status == 403 else
                             'HTTP_CLIENT_ERROR' if 400 <= exc.status < 500 else
                             'HTTP_SERVER_ERROR' if 500 <= exc.status < 600 else 'HTTP_ERROR')
    except InterruptedError:
        result['outcome'] = 'CANCELLED'
    except TimeoutError:
        result['outcome'] = 'TIMEOUT'
    except socket.gaierror:
        result['outcome'] = 'DNS_FAILED'
    except ValueError:
        result['outcome'] = 'POLICY_REJECTED'
    except (RequestError, OSError) as exc:
        classes = {fact['class'] for fact in exception_facts(exc)}
        result['outcome'] = ('CANCELLED' if 'InterruptedError' in classes else
                             'TIMEOUT' if 'TimeoutError' in classes else
                             'DNS_FAILED' if 'gaierror' in classes else 'NETWORK_ERROR')
    finally:
        try:
            if response is not None:
                response.close()
        finally:
            _current.event = None
            _current.proxy_host = None
            with _lock:
                _requests.pop(request_id, None)

    return result, prefix
