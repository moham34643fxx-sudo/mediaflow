"""Preserve Facebook's source labels and provide resilient multi-tier extraction.

Implements embed player, mobile web, and crawler fallbacks for public videos without cookies.
"""
import re
import html
import json
import time
import http.cookiejar
from urllib.parse import urlsplit, urljoin, quote


FACEBOOK_COOKIE_HOSTS = frozenset((
    'facebook.com', 'www.facebook.com', 'm.facebook.com', 'web.facebook.com'))


def install_session_cookies(cookiejar, cookies):
    """Install WebView cookies as host-only cookies for known Facebook origins."""
    if cookiejar is None or not hasattr(cookiejar, 'set_cookie'):
        return 0
    count = 0
    for cookie in cookies:
        if not isinstance(cookie, dict) or cookie.get('hostOnly') is not True:
            continue
        name = cookie.get('name')
        value = cookie.get('value')
        domain = cookie.get('domain')
        if not name or not value or not isinstance(domain, str) or domain not in FACEBOOK_COOKIE_HOSTS:
            continue
        cookiejar.set_cookie(http.cookiejar.Cookie(
            version=0, name=str(name), value=str(value), port=None, port_specified=False,
            domain=domain, domain_specified=False, domain_initial_dot=False,
            path='/', path_specified=True, secure=True, expires=int(time.time() + 86400 * 30),
            discard=False, comment=None, comment_url=None, rest={'HttpOnly': None}, rfc2109=False
        ))
        count += 1
    return count


def annotate_formats(document, formats):
    labels = {}
    for node in document.iter():
        if node.tag.rsplit('}', 1)[-1] != 'Representation':
            continue
        label = node.get('FBQualityLabel', '')
        identifier = node.get('id')
        if identifier and re.fullmatch(r'[1-9][0-9]{1,4}p', label):
            labels[identifier] = label
    for fmt in formats:
        label = labels.get(fmt.get('format_id'))
        if label:
            # A representation tier does not establish its aspect ratio or geometry.
            fmt['mediaflow_source_quality_label'] = label
    return formats


def unescape_string(s):
    if not s:
        return ''
    s = s.replace(r'\/', '/').replace(r'\u0026', '&').replace('&amp;', '&')
    try:
        s = json.loads(f'"{s}"')
    except Exception:
        pass
    return s


def _progressive_format(match, format_id):
    if match is None:
        return None
    url = unescape_string(match.group(1))
    if not url.startswith('https://'):
        return None
    # Legacy SD/HD keys identify a download, not a numbered quality or size.
    return {'format_id': format_id, 'url': url, 'ext': 'mp4',
            'vcodec': 'h264', 'acodec': 'aac', 'protocol': 'https'}


_META_TAG_PATTERN = re.compile(r'<meta\b[^>]*>', re.IGNORECASE)
_VIDEO_TAG_PATTERN = re.compile(r'<video\b[^>]*>', re.IGNORECASE)
_TITLE_TAG_PATTERN = re.compile(r'<title\b[^>]*>([\s\S]{0,2048}?)</title\s*>', re.IGNORECASE)
_HTML_ATTRIBUTE_PATTERN = re.compile(r'''([:\w-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))''')


def _tag_attributes(tag):
    attrs = {}
    for match in _HTML_ATTRIBUTE_PATTERN.finditer(tag):
        value = next((part for part in match.groups()[1:] if part is not None), '')
        attrs.setdefault(match.group(1).lower(), html.unescape(value))
    return attrs


def _safe_facebook_image(value, base_url='https://www.facebook.com/'):
    value = html.unescape(str(value or '')).strip()
    if not value or len(value) > 4096:
        return None
    try:
        candidate = urljoin(base_url, value)
        parsed = urlsplit(candidate)
        host = (parsed.hostname or '').lower().rstrip('.')
        port = parsed.port
    except (TypeError, ValueError):
        return None
    allowed = any(host == domain or host.endswith('.' + domain)
                  for domain in ('facebook.com', 'fbcdn.net', 'fbsbx.com'))
    if parsed.scheme.lower() != 'https' or not host or not allowed or parsed.username or parsed.password:
        return None
    if port not in (None, 443):
        return None
    return candidate


_FACEBOOK_METADATA_NAMES = frozenset((
    'og:title', 'twitter:title', 'og:image:secure_url', 'og:image',
    'twitter:image', 'twitter:image:src',
))


def _facebook_meta_contents(page):
    meta = {}
    for match in _META_TAG_PATTERN.finditer(page or ''):
        attrs = _tag_attributes(match.group(0))
        key = (attrs.get('property') or attrs.get('name') or '').strip().lower()
        content = attrs.get('content')
        if key in _FACEBOOK_METADATA_NAMES and content:
            meta.setdefault(key, content)
    return meta


def _clean_facebook_title(value):
    title = re.sub(r'\s+', ' ', html.unescape(re.sub(r'<[^>]*>', ' ', value or ''))).strip()
    if title.lower() in {
        'facebook', 'facebook video', 'facebook watch', 'watch | facebook', 'log in or sign up'
    }:
        return ''
    return title[:240].strip()


def _facebook_page_title(meta, page, video_id):
    title = next((cleaned for key in ('og:title', 'twitter:title')
                  if (cleaned := _clean_facebook_title(meta.get(key)))), None)
    if title is None:
        title_match = _TITLE_TAG_PATTERN.search(page or '')
        title = _clean_facebook_title(title_match.group(1) if title_match else '')
    return title or f'Facebook Video {video_id}'


def _facebook_page_thumbnail(meta, page):
    thumbnail = None
    for key in ('og:image:secure_url', 'og:image', 'twitter:image', 'twitter:image:src'):
        thumbnail = _safe_facebook_image(meta.get(key))
        if thumbnail:
            break
    if not thumbnail:
        video_match = _VIDEO_TAG_PATTERN.search(page or '')
        if video_match:
            thumbnail = _safe_facebook_image(_tag_attributes(video_match.group(0)).get('poster'))
    return thumbnail


def parse_facebook_page_metadata(page, video_id):
    metadata = _facebook_meta_contents(page)
    return {
        'title': _facebook_page_title(metadata, page, video_id),
        'thumbnail': _facebook_page_thumbnail(metadata, page),
    }


def _merge_facebook_page_metadata(info, metadata, video_id):
    if not isinstance(info, dict) or not isinstance(metadata, dict):
        return info
    page_title = metadata.get('title')
    current_title = str(info.get('title') or '').strip()
    generic_titles = {
        '', 'facebook', 'facebook video', 'facebook watch', 'watch | facebook',
        'log in or sign up', f'facebook video {video_id}'.lower(),
    }
    if page_title and current_title.lower() in generic_titles and page_title != f'Facebook Video {video_id}':
        info['title'] = page_title
    thumbnail = _safe_facebook_image(metadata.get('thumbnail'))
    if thumbnail and not _safe_facebook_image(info.get('thumbnail')):
        info['thumbnail'] = thumbnail
    return info


_FACEBOOK_AUTH_PATTERNS = tuple(re.compile(pattern, re.IGNORECASE) for pattern in (
    r'\b(?:this|the)\s+(?:video|content|post)\s+(?:requires|needs)\s+authentication\b',
    r'\b(?:you must|you need to|please)\s+(?:log\s*in|sign\s*in)\s+(?:to|before)\s+(?:view|watch|access|see)\s+(?:this|the)\s+(?:video|content|post)\b',
    r'\b(?:log\s*in|sign\s*in)\s+(?:to|before)\s+(?:view|watch|access|see)\s+(?:this|the)\s+(?:video|content|post)\b',
    r'\bpermission\s+to\s+view\s+(?:this|the)\s+(?:video|content|post)\b',
    r'\b(?:this\s+)?(?:video|content)\s+is\s+only\s+available\s+to\s+(?:logged[ -]?in|registered)\s+users\b',
    r'\b(?:facebook\s+)?session\s+expired\b',
    r'\b(?:facebook\s+)?login\s+required\s+to\s+(?:view|watch|access)\s+(?:this|the)\s+(?:video|content|post)\b',
))


def facebook_auth_signal(value):
    text = re.sub(r'\s+', ' ', html.unescape(str(value or ''))).lower()
    return any(pattern.search(text) for pattern in _FACEBOOK_AUTH_PATTERNS)


def configure(engine, source_url):
    host = (urlsplit(source_url).hostname or '').lower()
    if host not in ('facebook.com', 'fb.watch') and not host.endswith('.facebook.com'):
        return

    # Check for available Android CookieManager or session file cookies and scope to .facebook.com
    try:
        from java import jclass
        js_bridge = jclass('com.zdownloader.app.engine.JsBridge')
        cookies_json_str = str(js_bridge.getFacebookCookiesJson(source_url))
        if cookies_json_str and cookies_json_str != '[]':
            cookie_list = json.loads(cookies_json_str)
            count = install_session_cookies(getattr(engine, 'cookiejar', None), cookie_list)
            if count:
                try:
                    jclass('android.util.Log').i('ZAnalysis', f'Loaded {count} facebook cookies into engine.cookiejar')
                except Exception:
                    pass
    except Exception:
        pass

    from yt_dlp.extractor.facebook import FacebookIE
    from yt_dlp.utils import ExtractorError

    class LabelledFacebookIE(FacebookIE):
        @classmethod
        def ie_key(cls):
            return FacebookIE.ie_key()

        def _download_webpage(self, url_or_request, *args, **kwargs):
            webpage = super()._download_webpage(url_or_request, *args, **kwargs)
            if not isinstance(webpage, str) or not webpage:
                return webpage
            try:
                page_url = (url_or_request if isinstance(url_or_request, str) else
                            getattr(url_or_request, 'url', None) or
                            getattr(url_or_request, 'full_url', None))
                parsed = urlsplit(page_url)
                host = (parsed.hostname or '').lower().rstrip('.')
                is_facebook = (host in ('facebook.com', 'fb.watch') or
                               host.endswith('.facebook.com'))
                if is_facebook and not parsed.path.startswith('/plugins/'):
                    self._mediaflow_page_metadata = parse_facebook_page_metadata(
                        webpage, getattr(self, '_mediaflow_video_id', ''))
            except (TypeError, ValueError):
                pass
            return webpage

        def _parse_mpd_formats(self, document, *args, **kwargs):
            return annotate_formats(document, super()._parse_mpd_formats(document, *args, **kwargs))

        def _extract_from_data_extra(self, webpage, video_id):
            formats = []
            for match in re.finditer(r'data-extra="([^"]+)"', webpage):
                val = html.unescape(match.group(1))
                try:
                    data = json.loads(val)
                except Exception:
                    continue
                if str(data.get('video_id')) == str(video_id):
                    dash_manifest = data.get('dash_manifest')
                    if dash_manifest:
                        doc = self._parse_xml(dash_manifest, video_id, fatal=False)
                        if doc is not None:
                            fmts = self._parse_mpd_formats(doc)
                            formats.extend(fmts)
            return formats

        def _extract_from_embed(self, video_id):
            canonical_urls = [
                f'https://www.facebook.com/reel/{video_id}',
                f'https://www.facebook.com/watch/?v={video_id}',
                f'https://www.facebook.com/video.php?v={video_id}',
            ]
            for canonical in canonical_urls:
                embed_url = f'https://www.facebook.com/plugins/video.php?href={quote(canonical, safe="")}'
                try:
                    page = self._download_webpage(
                        embed_url, video_id, fatal=False,
                        headers={
                            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
                            'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
                            'Accept-Language': 'en-US,en;q=0.9',
                            'Sec-Fetch-Mode': 'navigate',
                        }
                    ) or ''
                    if not page:
                        continue
                    if facebook_auth_signal(page):
                        self._last_embed_permission_denied = True
                    formats = []
                    hd_match = re.search(r'"hd_src"\s*:\s*"([^"]+)"', page) or re.search(r'hd_src:"([^"]+)"', page) or \
                               re.search(r'"playable_url_quality_hd"\s*:\s*"([^"]+)"', page) or re.search(r'"browser_native_hd_url"\s*:\s*"([^"]+)"', page)
                    sd_match = re.search(r'"sd_src"\s*:\s*"([^"]+)"', page) or re.search(r'sd_src:"([^"]+)"', page) or \
                               re.search(r'"playable_url"\s*:\s*"([^"]+)"', page) or re.search(r'"browser_native_sd_url"\s*:\s*"([^"]+)"', page)

                    dash_match = re.search(r'"dash_manifest"\s*:\s*"([^"]+)"', page)
                    if dash_match:
                        raw_dash = unescape_string(dash_match.group(1))
                        doc = self._parse_xml(raw_dash, video_id, fatal=False)
                        if doc is not None:
                            formats.extend(self._parse_mpd_formats(doc))

                    for match, format_id in ((hd_match, 'hd'), (sd_match, 'sd')):
                        progressive = _progressive_format(match, format_id)
                        if progressive:
                            formats.append(progressive)

                    if formats:
                        return {'id': video_id, **parse_facebook_page_metadata(page, video_id),
                                'formats': formats}
                except Exception:
                    continue
            return None

        def _extract_from_mobile(self, video_id):
            m_url = f'https://m.facebook.com/reel/{video_id}'
            try:
                page = self._download_webpage(
                    m_url, video_id, fatal=False,
                    headers={
                        'User-Agent': 'Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36',
                        'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
                        'Accept-Language': 'en-US,en;q=0.9',
                        'Sec-Fetch-Mode': 'navigate',
                    }
                ) or ''
                if page:
                    og_match = re.search(r'<meta\s+property=["\']og:video(?::secure_url)?["\']\s+content=["\']([^"\']+)["\']', page)
                    progressive = _progressive_format(og_match, 'sd')
                    if progressive:
                        return {'id': video_id, **parse_facebook_page_metadata(page, video_id),
                                'formats': [progressive]}
            except Exception:
                pass
            return None

        def _extract_from_url(self, url, video_id):
            self._last_embed_permission_denied = False
            self._mediaflow_video_id = str(video_id)
            self._mediaflow_page_metadata = None
            super_exc = None
            try:
                res = super()._extract_from_url(url, video_id)
                if res and res.get('formats'):
                    return _merge_facebook_page_metadata(
                        res, getattr(self, '_mediaflow_page_metadata', None), video_id)
            except ExtractorError as exc:
                super_exc = exc
            except Exception as exc:
                super_exc = exc

            # Try Tier 1: Embed Player
            embed_res = self._extract_from_embed(video_id)
            if embed_res:
                return embed_res

            # Try Tier 2: data-extra from reel page
            reel_url = f'https://www.facebook.com/reel/{video_id}'
            try:
                reel_page = self._download_webpage(reel_url, video_id, fatal=False) or ''
            except Exception:
                reel_page = ''
            if reel_page:
                extra_formats = self._extract_from_data_extra(reel_page, video_id)
                if extra_formats:
                    return {'id': video_id, **parse_facebook_page_metadata(reel_page, video_id),
                            'formats': extra_formats}

            # Try Tier 3: Mobile Web
            mobile_res = self._extract_from_mobile(video_id)
            if mobile_res:
                return mobile_res

            # If all tiers failed, detect if authentication is required or session expired
            login_signals = (
                getattr(self, '_last_embed_permission_denied', False) or
                facebook_auth_signal(reel_page)
            )

            has_session = False
            try:
                from java import jclass
                has_session = bool(jclass('com.zdownloader.app.engine.FacebookSessionManager').hasActiveSession())
            except Exception:
                pass

            if has_session:
                if login_signals or (super_exc and facebook_auth_signal(super_exc)):
                    try:
                        from java import jclass
                        jclass('com.zdownloader.app.engine.FacebookSessionManager').markSessionExpired()
                    except Exception:
                        pass
                    raise ExtractorError('Facebook session expired. Please log in again.', expected=True)
                if super_exc:
                    raise super_exc
                raise ExtractorError('Cannot parse data')
            else:
                if login_signals or (super_exc and facebook_auth_signal(super_exc)):
                    raise ExtractorError('This video requires authentication. Please log in to Facebook.', expected=True)
                if super_exc:
                    raise super_exc
                raise ExtractorError('Cannot parse data')

        def _real_extract(self, url):
            video_id = self._match_id(url)
            real_url = self._VIDEO_PAGE_TEMPLATE % video_id if url.startswith('facebook:') else url
            return self._extract_from_url(real_url, video_id)

    engine.add_info_extractor(LabelledFacebookIE(engine))
