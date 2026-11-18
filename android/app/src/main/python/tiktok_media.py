"""TikTok URL adapter and explicit local slideshow recipe (no external download service).

Use the bundled extractor's authenticated/public transport and web metadata parser.
Never label music as video. Generated slideshows use equal image durations, not
invented original transition timings. A real video, when present, remains intact.
"""
import math
import re
from urllib.parse import urlsplit


def recipe(data, info, validate):
    images = (data.get('imagePost') or {}).get('images') or []
    if not images:
        return None
    if not isinstance(images, list) or not 1 <= len(images) <= 35:
        raise ValueError('FORMAT_UNAVAILABLE')
    slides = []
    for index, image in enumerate(images):
        source = image.get('imageURL') or {}
        urls = source.get('urlList') or []
        url = next((value for value in urls if isinstance(value, str) and value.startswith('https://')), None)
        if not url:
            raise ValueError('FORMAT_UNAVAILABLE')
        validate(url)
        width, height = image.get('imageWidth'), image.get('imageHeight')
        if any(isinstance(n, bool) or not isinstance(n, (int, float)) or not math.isfinite(n)
               or not 1 <= n <= 16384 or int(n) != n for n in (width, height)):
            raise ValueError('FORMAT_UNAVAILABLE')
        slides.append({'url': url, 'width': int(width), 'height': int(height)})
    duration = info.get('duration')
    if not isinstance(duration, (int, float)) or isinstance(duration, bool) or not math.isfinite(duration) or duration <= 0:
        duration = len(slides) * 3
    if duration > 600:
        raise ValueError('FORMAT_UNAVAILABLE')
    w, h = slides[0]['width'], slides[0]['height']
    scale = min(1, 1280 / max(w, h), 720 / min(w, h))
    w, h = max(2, int(w * scale) // 2 * 2), max(2, int(h * scale) // 2 * 2)
    return {'version': 1, 'images': slides, 'duration': float(duration), 'width': w, 'height': h,
            'timing': 'equal_generated', 'post_id': str(info['id'])}


def configure(engine, validate):
    from yt_dlp.extractor.tiktok import TikTokIE

    class MediaFlowTikTokIE(TikTokIE):
        _VALID_URL = TikTokIE._VALID_URL.replace(r'www\.', r'(?:www\.)?').replace('/video)', '/(?:video|photo))')

        @classmethod
        def ie_key(cls):
            return TikTokIE.ie_key()

        def _real_extract(self, url):
            # Canonicalize only a proven TikTok post route; preserve the post ID.
            # Short-link redirects still go through yt-dlp and the URL/DNS policy.
            match = re.fullmatch(r'/(?:@([\w.-]*)|share)/(?:photo|video)/(\d+)/?', urlsplit(url).path)
            if match:
                url = self._create_url(match[1], match[2])
            return super()._real_extract(url)

        def _parse_aweme_video_web(self, data, url, post_id, *args, **kwargs):
            info = super()._parse_aweme_video_web(data, url, post_id, *args, **kwargs)
            # A usable actual video wins; don't synthesize over a genuine source.
            if any(f.get('vcodec') not in (None, 'none', 'images') for f in info.get('formats', [])):
                return info
            plan = recipe(data, info, validate)
            if plan:
                info['formats'].append({'format_id': 'mediaflow-slideshow', 'ext': 'mp4',
                    'url': plan['images'][0]['url'], 'protocol': 'mediaflow_slideshow',
                    'vcodec': 'h264', 'acodec': 'none', 'fps': 30,
                    'width': plan['width'], 'height': plan['height'],
                    'mediaflow_slideshow': plan, 'format_note': 'Generated slideshow; equal image timing'})
                info['duration'] = plan['duration']
            return info

    # Replacing the existing key preserves extractor ordering and short-link delegation.
    engine.add_info_extractor(MediaFlowTikTokIE(engine))
