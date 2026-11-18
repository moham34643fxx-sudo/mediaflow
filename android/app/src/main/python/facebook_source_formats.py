"""Preserve Facebook's source labels and provide resilient multi-tier extraction.

Implements embed player, mobile web, and crawler fallbacks for public videos without cookies.
"""
import re
import html
import json
import time
import http.cookiejar
from urllib.parse import urlsplit, quote


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
            fmt['mediaflow_source_quality_label'] = label
            orig_h = fmt.get('height') or 0
            orig_w = fmt.get('width') or 0
            if orig_h == 0 or orig_w == 0:
                try:
                    target_edge = int(label[:-1])
                    fmt['height'] = (target_edge * 16) // 9
                    fmt['width'] = target_edge
                except Exception:
                    pass
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
            if hasattr(engine, 'cookiejar') and engine.cookiejar is not None:
                domain = '.facebook.com'
                count = 0
                for c in cookie_list:
                    name = c.get('name')
                    value = c.get('value')
                    if name and value:
                        ck = http.cookiejar.Cookie(
                            version=0, name=str(name), value=str(value), port=None, port_specified=False,
                            domain=domain, domain_specified=True, domain_initial_dot=True,
                            path='/', path_specified=True, secure=True, expires=int(time.time() + 86400 * 30),
                            discard=False, comment=None, comment_url=None, rest={'HttpOnly': None}, rfc2109=False
                        )
                        engine.cookiejar.set_cookie(ck)
                        count += 1
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
                    if 'permission to view' in page.lower() or 'video unavailable' in page.lower() or 'log in' in page.lower():
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

                    if hd_match:
                        hd_url = unescape_string(hd_match.group(1))
                        if hd_url.startswith('http'):
                            formats.append({
                                'format_id': 'hd',
                                'url': hd_url,
                                'ext': 'mp4',
                                'height': 720,
                                'width': 1280,
                                'vcodec': 'h264',
                                'acodec': 'aac',
                                'protocol': 'https',
                                'mediaflow_source_quality_label': '720p',
                            })
                    if sd_match:
                        sd_url = unescape_string(sd_match.group(1))
                        if sd_url.startswith('http'):
                            formats.append({
                                'format_id': 'sd',
                                'url': sd_url,
                                'ext': 'mp4',
                                'height': 480,
                                'width': 854,
                                'vcodec': 'h264',
                                'acodec': 'aac',
                                'protocol': 'https',
                                'mediaflow_source_quality_label': '480p',
                            })

                    if formats:
                        title_match = re.search(r'<meta\s+property=["\']og:title["\']\s+content=["\']([^"\']+)["\']', page)
                        title = html.unescape(title_match.group(1)) if title_match else f'Facebook Video {video_id}'
                        return {
                            'id': video_id,
                            'title': title,
                            'formats': formats,
                        }
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
                    formats = []
                    og_match = re.search(r'<meta\s+property=["\']og:video(?::secure_url)?["\']\s+content=["\']([^"\']+)["\']', page)
                    if og_match:
                        u = unescape_string(og_match.group(1))
                        if u.startswith('http'):
                            formats.append({
                                'format_id': 'sd',
                                'url': u,
                                'ext': 'mp4',
                                'height': 480,
                                'width': 854,
                                'vcodec': 'h264',
                                'acodec': 'aac',
                                'protocol': 'https',
                                'mediaflow_source_quality_label': '480p',
                            })
                    if formats:
                        title_match = re.search(r'<meta\s+property=["\']og:title["\']\s+content=["\']([^"\']+)["\']', page)
                        title = html.unescape(title_match.group(1)) if title_match else f'Facebook Video {video_id}'
                        return {
                            'id': video_id,
                            'title': title,
                            'formats': formats,
                        }
            except Exception:
                pass
            return None

        def _extract_from_url(self, url, video_id):
            self._last_embed_permission_denied = False
            super_exc = None
            try:
                res = super()._extract_from_url(url, video_id)
                if res and res.get('formats'):
                    return res
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
                    return {
                        'id': video_id,
                        'title': f'Facebook Video {video_id}',
                        'formats': extra_formats,
                    }

            # Try Tier 3: Mobile Web
            mobile_res = self._extract_from_mobile(video_id)
            if mobile_res:
                return mobile_res

            # If all tiers failed, detect if authentication is required or session expired
            lower_reel = reel_page.lower()
            login_signals = (
                getattr(self, '_last_embed_permission_denied', False) or
                'usecaaloggedoutdialog' in lower_reel or
                'usecometloginform' in lower_reel or
                'permission to view' in lower_reel or
                'caa_login' in lower_reel or
                'login_data' in lower_reel or
                '/login.php' in lower_reel or
                '/checkpoint/' in lower_reel or
                '>you must log in to continue' in lower_reel or
                'id="login_form"' in lower_reel or
                'id="loginbutton"' in lower_reel or
                'canonical" href="https://www.facebook.com/watch/' in lower_reel
            )

            has_session = False
            try:
                from java import jclass
                has_session = bool(jclass('com.zdownloader.app.engine.FacebookSessionManager').hasActiveSession())
            except Exception:
                pass

            if has_session:
                if login_signals or (super_exc and any(k in str(super_exc).lower() for k in ('login', 'log in', 'cookie', 'credentials', 'sign in'))):
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
                if login_signals or (super_exc and any(k in str(super_exc).lower() for k in ('login', 'log in', 'cookie', 'credentials', 'sign in', 'authentication'))):
                    raise ExtractorError('This video requires authentication. Please log in to Facebook.', expected=True)
                if super_exc:
                    raise super_exc
                raise ExtractorError('Cannot parse data')

        def _real_extract(self, url):
            video_id = self._match_id(url)
            real_url = self._VIDEO_PAGE_TEMPLATE % video_id if url.startswith('facebook:') else url
            return self._extract_from_url(real_url, video_id)

    engine.add_info_extractor(LabelledFacebookIE(engine))
