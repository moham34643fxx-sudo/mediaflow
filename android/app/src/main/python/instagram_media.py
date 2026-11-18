"""Original Instagram image sources; never promote a video's thumbnail to media."""
import hashlib
import math
from urllib.parse import parse_qs, urlsplit

_HEADERS = {'Referer': 'https://www.instagram.com/'}


def _dimension(value):
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    return int(value) if 0 < value <= 32768 and math.isfinite(value) and value == int(value) else None


def _image_candidate(candidate, url, validate, dimensions):
    validate(url)
    width, height = _dimension(candidate.get('width')), _dimension(candidate.get('height'))
    extension = urlsplit(url).path.rsplit('.', 1)[-1].lower()
    if width is None or height is None or extension not in ('jpg', 'jpeg', 'png', 'webp'):
        geometry = dimensions(url) if dimensions is not None else None
        if geometry is None:
            return None
        extension, width, height = geometry
    if extension not in ('jpg', 'jpeg', 'png', 'webp'):
        return None
    return {'format_id': f'original-image-{width}x{height}', 'url': url, 'ext': extension,
        'width': width, 'height': height, 'protocol': 'https', 'vcodec': 'none', 'acodec': 'none',
        'mediaflow_image': 'original', 'http_headers': _HEADERS.copy()}


def image_formats(product, validate, dimensions=None):
    from yt_dlp.networking.exceptions import HTTPError
    if product.get('media_type') != 1:
        return []
    versions = product.get('image_versions2')
    candidates = versions.get('candidates') or [] if isinstance(versions, dict) else []
    if not isinstance(candidates, list) or len(candidates) > 100:
        raise ValueError('Requested format is not available')
    sources, inspected, first_error = {}, set(), None
    for candidate in candidates:
        url = candidate.get('url') if isinstance(candidate, dict) else None
        if not isinstance(url, str) or url in inspected:
            continue
        inspected.add(url)
        try:
            source = _image_candidate(candidate, url, validate, dimensions)
        except HTTPError as error:
            if error.status not in (403, 404, 410):
                raise
            error.close();first_error = first_error or error
            continue
        if source is not None:
            sources.setdefault((source['width'], source['height']), source)
    if not sources and first_error is not None:
        raise first_error
    return list(sources.values())


def companion_audio_formats(product, validate):
    """Expose the accessible audio source, not an invented timed photo/video clip."""
    if product.get('media_type') != 1:
        return []
    sources = {}
    for container in ('music_metadata', 'clips_metadata'):
        metadata = product.get(container)
        if not isinstance(metadata, dict):
            continue
        music = metadata.get('music_info')
        asset = music.get('music_asset_info') if isinstance(music, dict) else None
        for sound in (metadata.get('original_sound_info'), asset):
            if not isinstance(sound, dict):
                continue
            url = sound.get('progressive_download_url')
            if not isinstance(url, str) or not url:
                continue
            validate(url)
            extension = urlsplit(url).path.rsplit('.', 1)[-1].lower()
            if extension not in ('m4a', 'aac', 'mp3', 'ogg', 'opus', 'webm', 'mp4'):
                continue
            identity = hashlib.sha256(url.encode()).hexdigest()[:20]
            sources.setdefault(url, {'format_id': f'companion-audio-{identity}',
                'url': url, 'ext': extension, 'protocol': 'https', 'vcodec': 'none',
                'mediaflow_companion_audio': 'source',
                'http_headers': _HEADERS.copy()})
    return list(sources.values())


def configure(engine, validate):
    from yt_dlp.extractor.instagram import InstagramIE
    from yt_dlp.utils import ExtractorError
    from image_headers import probe

    class OriginalImageInstagramIE(InstagramIE):
        @classmethod
        def ie_key(cls):
            return InstagramIE.ie_key()

        def _real_extract(self, url):
            indexes = parse_qs(urlsplit(url).query, max_num_fields=100).get('img_index', [])
            self._mediaflow_index = int(indexes[0]) if len(indexes) == 1 else None
            if indexes and (len(indexes) != 1 or self._mediaflow_index not in range(1, 101)):
                raise ValueError('FORMAT_UNAVAILABLE')
            return super()._real_extract(url)

        def _extract_product(self, product, video_id=None, get_comments=True):
            if isinstance(product, list):
                product = product[0]
            index = getattr(self, '_mediaflow_index', None)
            carousel = product.get('carousel_media') if isinstance(product, dict) else None
            if index is not None and carousel is None and index != 1:
                raise ValueError('FORMAT_UNAVAILABLE')
            if index is not None and isinstance(carousel, list):
                if not 1 <= index <= len(carousel) <= 100 or not isinstance(carousel[index - 1], dict):
                    raise ValueError('FORMAT_UNAVAILABLE')
                product = {**product, **carousel[index - 1]}
                product.pop('carousel_media', None)
            extracted = super()._extract_product(product, video_id, get_comments)
            if extracted.get('_type') == 'playlist':
                code = video_id or product.get('code') or extracted.get('id')
                for position, entry in enumerate(extracted['entries'], 1):
                    entry['webpage_url'] = f'https://www.instagram.com/p/{code}/?img_index={position}'
            return extracted

        def _extract_product_media(self, product):
            media = super()._extract_product_media(product)
            # Upstream's real video/DASH sources always win over image candidates.
            if not media.get('formats'):
                images = image_formats(product, validate, lambda url: probe(engine, url, _HEADERS))
                if not images and product.get('media_type') == 1:
                    raise ExtractorError('Requested format is not available', expected=True)
                media['formats'] = images + companion_audio_formats(product, validate) if images else []
                if images:
                    # Metadata-only callers choose media themselves. Upstream's
                    # default best-video selector rejects genuine image-only posts.
                    self._downloader.format_selector = self._downloader.build_format_selector('all')
            return media

    engine.add_info_extractor(OriginalImageInstagramIE(engine))
