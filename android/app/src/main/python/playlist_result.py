"""Small metadata-only playlist boundary. Never expand nested collections implicitly."""
import hashlib
import itertools
import math


def options(offset=0, limit=100):
    offset, limit = int(offset), int(limit)
    if offset < 0 or offset > 1_000_000 or not 1 <= limit <= 100:
        raise ValueError('BATCH_LIMIT_EXCEEDED')
    return {'extract_flat': 'in_playlist', 'lazy_playlist': True,
            'playlist_items': f'{offset + 1}:{offset + limit + 1}',
            'noplaylist': False, 'ignoreerrors': False}


def page(info, original_url, offset, limit, validate, check):
    if not isinstance(info, dict):
        raise ValueError('EXTRACTION_FAILED')
    def text(value, length=512):
        return value[:length] if isinstance(value, str) else None
    def url(value):
        try:
            return validate(value) if isinstance(value, str) else None
        except (ValueError, TypeError):
            return None
    def number(value):
        return value if isinstance(value, (float, int)) and not isinstance(value, bool) and math.isfinite(value) and value > 0 else None
    def thumbnail(entry):
        direct = url(entry.get('thumbnail'))
        if direct:
            return direct
        # Do not materialize a giant or lazy thumbnail list.
        for item in itertools.islice(entry.get('thumbnails') or [], 8):
            if isinstance(item, dict) and url(item.get('url')):
                return url(item['url'])
        return None
    is_collection = info.get('_type') in ('playlist', 'multi_video') or info.get('entries') is not None
    entries = info.get('entries') or [] if is_collection else [info]
    items = []
    more = False
    for local, entry in enumerate(itertools.islice(entries, limit + 1)):
        check()
        if local == limit:
            more = True
            break
        entry = entry if isinstance(entry, dict) else {}
        position = int(number(entry.get('playlist_index')) or offset + local + 1)
        webpage = url(entry.get('webpage_url'))
        media = url(entry.get('url'))
        # Embedded media can inherit the collection page as webpage_url. Sending
        # that parent back to extraction loses the child's identity.
        link = (media if entry.get('_type', 'video') == 'video' and webpage == original_url else None) or webpage or media or url(entry.get('original_url'))
        nested = entry.get('_type') in ('playlist', 'multi_video') or entry.get('entries') is not None
        availability = entry.get('availability')
        reason = ('COLLECTION' if nested else 'AUTHENTICATION_REQUIRED' if availability in ('private', 'premium_only', 'subscriber_only', 'needs_auth')
                  else 'VIDEO_NOT_FOUND' if not link or availability == 'unavailable' else None)
        identity = '|'.join(str(v or '') for v in (position, entry.get('ie_key'), entry.get('id'), link))
        # Some multi-video extractors already return a fully described single
        # representation. Reuse its small size hint without extracting anything.
        # Flat rows or ambiguous multi-format plans remain size-unknown.
        hint = None
        formats = entry.get('formats')
        if entry.get('_type', 'video') == 'video' and isinstance(formats, list) and len(formats) == 1 and isinstance(formats[0], dict):
            fmt = formats[0]
            size = number(fmt.get('filesize')) or number(fmt.get('filesize_approx'))
            if size and not fmt.get('has_drm') and not fmt.get('request_data') and fmt.get('protocol') != 'mhtml' and fmt.get('vcodec') is not None and fmt.get('acodec') is not None:
                width, height = number(fmt.get('width')), number(fmt.get('height'))
                hint = {'bytes': size, 'estimated': not number(fmt.get('filesize')),
                        'video': fmt['vcodec'] != 'none', 'audio': fmt['acodec'] != 'none',
                        'quality': min(width,height) if width and height else height or width}
        items.append({'key': hashlib.sha256(identity.encode()).hexdigest(), 'url': link,
                      'id': text(entry.get('id')), 'extractor': text(entry.get('ie_key') or entry.get('extractor_key')),
                      'position': position, 'title': text(entry.get('title')), 'duration': number(entry.get('duration')),
                      'thumbnail': thumbnail(entry), 'reason': reason, 'collection': nested, 'size_hint': hint})
    total = number(info.get('playlist_count')) or number(info.get('n_entries'))
    return {'kind': 'playlist' if is_collection else 'single', 'title': text(info.get('title')),
            'creator': text(info.get('uploader') or info.get('channel')), 'thumbnail': thumbnail(info),
            'total': int(total) if total else None, 'items': items, 'offset': offset,
            'next_offset': offset + len(items), 'has_more': more,
            'url': original_url}
