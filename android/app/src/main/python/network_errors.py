"""Stable codes across urllib/yt-dlp exception wrappers. Never return raw URLs."""
import socket
import ssl
import errno
import re

_BOT_FRAGMENTS = (
    'confirm you\'re not a bot', 'confirm you are not a bot', 'confirm you’re not a bot',
    'confirm you are not a robot', 'bot verification', 'captcha challenge before playback',
    'unusual traffic from your computer network', 'automated queries',
)
_FACEBOOK_AUTH_FRAGMENTS = (
    'this video requires authentication', 'this content requires authentication',
    'this post requires authentication', 'login required to view this video',
    'this video is only available to logged in users',
    'this video is only available to logged-in users',
    'this video is only available to registered users',
    'you must log in to view this video', 'you must sign in to view this video',
    'log in to view this video', 'sign in to view this video',
    'sign in to confirm your age', 'facebook session expired',
    'your facebook session has expired',
)
_GENERAL_AUTH_FRAGMENTS = (
    'authentication is required', 'authentication required', 'requires authentication',
    'login required', 'this video is only available to logged in users',
    'this video is only available to logged-in users',
    'this video is only available to registered users',
    'you must be logged in to view this video',
    'you must be signed in to view this video',
    'log in to view this video', 'sign in to view this video',
    'sign in to confirm your age',
    'web client only works when logged-in',
    'web client only works when logged in',
)


def _auth_fragments(platform):
    return (_FACEBOOK_AUTH_FRAGMENTS
            if isinstance(platform, str) and platform.casefold() == 'facebook'
            else _GENERAL_AUTH_FRAGMENTS)


def _classify_http_status(statuses, text, auth_fragments):
    if 403 in statuses and any(fragment in text for fragment in _BOT_FRAGMENTS):
        return 'BOT_DETECTED', 'extraction'
    if 403 in statuses and any(fragment in text for fragment in auth_fragments):
        return 'AUTHENTICATION_REQUIRED', 'extraction'
    return http_failure(statuses[0]), 'http'


def exception_facts(exc):
    """Class names and machine SSL reasons only; never exception text/URLs."""
    pending, seen, rows = [exc], set(), []
    transport_classes = []
    while pending and len(rows) < 12:
        error = pending.pop()
        if id(error) in seen:
            continue
        seen.add(id(error))
        row = {'class': type(error).__name__}
        reason = getattr(error, 'reason', None)
        if isinstance(reason, str) and re.fullmatch(r'[A-Z0-9_]{1,80}', reason):
            row['ssl_reason'] = reason
        rows.append(row)
        extra = getattr(error, 'safe_exception_classes', ())
        if isinstance(extra, (list, tuple)):
            transport_classes.extend(extra[:12])
        for key in ('__cause__', '__context__', 'reason', 'cause'):
            child = getattr(error, key, None)
            if isinstance(child, BaseException):
                pending.append(child)
        info = getattr(error, 'exc_info', None)
        if isinstance(info, tuple) and len(info) > 1 and isinstance(info[1], BaseException):
            pending.append(info[1])
    known = {row['class'] for row in rows}
    for name in transport_classes:
        if len(rows) >= 12:
            break
        if isinstance(name, str) and re.fullmatch(r'[A-Za-z0-9_.$]{1,120}', name) and name not in known:
            rows.append({'class': name})
            known.add(name)
    return rows


def classify(exc, default='EXTRACTION_FAILED', platform=None):
    pending, seen = [exc], set()
    texts = []
    statuses = []
    unsupported = False
    while pending:
        error = pending.pop()
        if id(error) in seen:
            continue
        seen.add(id(error))
        texts.append(re.sub(r'https?://\S+', '<url>', str(error).lower()))
        error_type = type(error)
        transport_code = getattr(error, 'transport_code', None)
        if transport_code in {'DNS_FAILED', 'TLS_FAILED', 'TLS_TIMEOUT', 'CONNECT_TIMEOUT', 'READ_TIMEOUT',
                              'CONNECTION_REFUSED', 'CONNECTION_FAILED', 'NETWORK_UNAVAILABLE',
                              'DOWNLOAD_INTERRUPTED', 'URL_POLICY_REJECTED'}:
            return transport_code, ('tls' if transport_code.startswith('TLS_') else 'network')
        unsupported |= error_type.__name__ == 'UnsupportedError' and error_type.__module__.startswith('yt_dlp.')
        for key in ('status', 'code'):
            value = getattr(error, key, None)
            if isinstance(value, int) and 400 <= value <= 599:
                statuses.append(value)
        if isinstance(error, socket.gaierror): return 'DNS_FAILED', 'dns'
        if isinstance(error, ssl.SSLCertVerificationError): return 'TLS_FAILED', 'tls'
        if isinstance(error, (ssl.SSLEOFError, ssl.SSLZeroReturnError)):
            return 'DOWNLOAD_INTERRUPTED', 'tls'
        if isinstance(error, ssl.SSLError):
            message = str(error).lower()
            if 'timed out' in message or 'timeout' in message: return 'TLS_TIMEOUT', 'tls'
            if any(token in message for token in ('unexpected_eof', 'eof occurred', 'connection reset', 'closed connection')):
                return 'DOWNLOAD_INTERRUPTED', 'tls'
            return 'TLS_FAILED', 'tls'
        if isinstance(error, InterruptedError): return 'DOWNLOAD_INTERRUPTED', 'transfer'
        if isinstance(error, OSError) and error.errno == errno.ENOSPC:
            return 'INSUFFICIENT_STORAGE', 'storage'
        if isinstance(error, OSError) and error.filename is not None:
            if error.errno in (errno.EACCES, errno.EPERM): return 'STORAGE_PERMISSION_ERROR', 'storage'
            if error.errno in (errno.ENOENT, errno.EROFS): return 'FILE_WRITE_FAILED', 'storage'
        if isinstance(error, ConnectionRefusedError): return 'CONNECTION_REFUSED', 'tcp'
        if isinstance(error, OSError) and error.errno in (errno.ENETUNREACH, errno.EHOSTUNREACH):
            return 'NETWORK_UNAVAILABLE', 'tcp'
        for key in ('__cause__', '__context__', 'reason', 'cause'):
            child = getattr(error, key, None)
            if isinstance(child, BaseException): pending.append(child)
        info = getattr(error, 'exc_info', None)
        if isinstance(info, tuple) and len(info) > 1 and isinstance(info[1], BaseException): pending.append(info[1])
    text = ' '.join(texts)
    # Keep malformed URLs as INVALID_URL; socket-policy rejections surface as URL_POLICY_REJECTED
    # so UI can distinguish user input errors from SSRF guard blocks without leaking addresses.
    if 'address rejected by media url policy' in text or 'policy_address' in text:
        return 'URL_POLICY_REJECTED', 'url'
    auth_fragments = _auth_fragments(platform)
    # Preserve HTTP status categories unless a 403 explicitly identifies a bot/auth gate.
    if statuses:
        return _classify_http_status(statuses, text, auth_fragments)
    for fragments, code, stage in (
        (('protected tweet', 'protected account', 'private account', 'private video',
          'this video is private', 'private content'), 'PRIVATE_CONTENT', 'extraction'),
        (('invalid_url',), 'INVALID_URL', 'url'),
        (('name resolution', 'getaddrinfo failed', 'name or service not known', 'no address associated'), 'DNS_FAILED', 'dns'),
        (('certificate verify failed', 'certificate_verify_failed', 'hostname mismatch'), 'TLS_FAILED', 'tls'),
        (('unexpected_eof', 'eof occurred in violation', 'connection reset', 'remote end closed', 'incomplete read'), 'DOWNLOAD_INTERRUPTED', 'body'),
        (('ssl:',), 'TLS_FAILED', 'tls'),
        (('connection refused',), 'CONNECTION_REFUSED', 'tcp'),
        (('connect timeout', 'connection timed out'), 'CONNECT_TIMEOUT', 'tcp'),
        (('read timed out', 'read timeout'), 'READ_TIMEOUT', 'body'),
        (('extraction_timeout',), 'EXTRACTION_TIMEOUT', 'extraction'),
        (('timed out', 'timeout'), 'NETWORK_TIMEOUT', 'network_unknown'),
        (('connection reset', 'remote end closed', 'incomplete read'), 'DOWNLOAD_INTERRUPTED', 'body'),
        (('requested format is not available',), 'FORMAT_UNAVAILABLE', 'extraction'),
        (('this live event will begin in', 'this video is a live stream', 'live stream recording is not supported', 'live stream has not started', 'this video is currently live', 'this video is an upcoming live'), 'LIVE_STREAM_DETECTED', 'extraction'),
        (('drm',), 'DRM_PROTECTED', 'extraction'),
        (_BOT_FRAGMENTS, 'BOT_DETECTED', 'extraction'),
        (('has been rate-limited by youtube', 'rate-limited by youtube', 'too many requests'), 'RATE_LIMITED', 'extraction'),
        (auth_fragments, 'AUTHENTICATION_REQUIRED', 'extraction'),
        (('video is unavailable', 'video is not available', 'video has been removed', 'video not found',
          'requested tweet is unavailable', 'tweet is unavailable', 'tweet has been deleted',
          'post is unavailable', 'post not found', 'no video could be found in this tweet',
          'no video could be found in this post'), 'VIDEO_NOT_FOUND', 'extraction'),
        (('not available in your country',), 'GEO_RESTRICTED', 'extraction'),
    ):
        if any(fragment in text for fragment in fragments): return code, stage
    status = re.search(r'(?:http error|http status|status code)\s*[:=]?\s*(\d{3})', text)
    if status:
        return http_failure(int(status[1])), 'http'
    if unsupported:
        return 'UNSUPPORTED_PLATFORM', 'extraction'
    return default, 'extraction' if default == 'EXTRACTION_FAILED' else 'transfer'


def http_failure(status):
    return {401: 'AUTHENTICATION_REQUIRED', 403: 'HTTP_FORBIDDEN', 404: 'VIDEO_NOT_FOUND',
            408: 'READ_TIMEOUT', 410: 'MEDIA_URL_EXPIRED', 429: 'RATE_LIMITED'}.get(
                status, 'SERVER_UNAVAILABLE' if 500 <= status <= 599 else 'HTTP_ERROR')
