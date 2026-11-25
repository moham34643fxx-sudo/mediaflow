import http.cookiejar
import unittest
from urllib.request import Request
from unittest.mock import patch
from xml.etree import ElementTree
from yt_dlp import YoutubeDL
from yt_dlp.extractor.facebook import FacebookIE

from facebook_source_formats import annotate_formats, configure, install_session_cookies


class FacebookSessionCookieScopeTest(unittest.TestCase):
    def test_session_cookies_are_host_only_and_https_only(self):
        jar = http.cookiejar.CookieJar()
        count = install_session_cookies(jar, [{
            'name': 'c_user',
            'value': 'fixture-secret',
            'domain': 'www.facebook.com',
            'hostOnly': True,
        }])

        self.assertEqual(1, count)
        for url in (
            'https://www.facebook.com/watch',
            'https://evil.facebook.com/watch',
            'https://facebook.com/watch',
            'https://www.facebook.com.attacker.test/watch',
            'http://www.facebook.com/watch',
        ):
            request = Request(url)
            jar.add_cookie_header(request)
            expected = 'c_user=fixture-secret' if url.startswith('https://www.facebook.com/') else None
            self.assertEqual(expected, request.get_header('Cookie'), url)

    def test_domain_wide_or_untrusted_cookie_records_are_rejected(self):
        jar = http.cookiejar.CookieJar()
        count = install_session_cookies(jar, [
            {'name': 'xs', 'value': 'too-broad', 'domain': '.facebook.com', 'hostOnly': False},
            {'name': 'xs', 'value': 'untrusted', 'domain': 'evil.facebook.com', 'hostOnly': True},
            {'name': 'xs', 'value': 'spoofed', 'domain': 'www.facebook.com', 'hostOnly': False},
            {'name': 'xs', 'value': 'malformed', 'domain': ['www.facebook.com'], 'hostOnly': True},
        ])

        self.assertEqual(0, count)
        request = Request('https://www.facebook.com/watch')
        jar.add_cookie_header(request)
        self.assertIsNone(request.get_header('Cookie'))

    def test_each_known_facebook_host_gets_an_independent_cookie_scope(self):
        jar = http.cookiejar.CookieJar()
        count = install_session_cookies(jar, [
            {'name': 'xs', 'value': 'desktop', 'domain': 'www.facebook.com', 'hostOnly': True},
            {'name': 'xs', 'value': 'mobile', 'domain': 'm.facebook.com', 'hostOnly': True},
        ])

        self.assertEqual(2, count)
        for url, expected in (
            ('https://www.facebook.com/watch', 'xs=desktop'),
            ('https://m.facebook.com/watch', 'xs=mobile'),
            ('https://web.facebook.com/watch', None),
        ):
            request = Request(url)
            jar.add_cookie_header(request)
            self.assertEqual(expected, request.get_header('Cookie'), url)


class FacebookQualityMetadataTest(unittest.TestCase):
    def test_dash_quality_label_does_not_override_or_invent_stream_geometry(self):
        document = ElementTree.fromstring(
            '<MPD><Representation id="portrait" FBQualityLabel="720p"/>'
            '<Representation id="partial" FBQualityLabel="480p"/></MPD>')
        portrait, partial = annotate_formats(document, [
            {'format_id': 'portrait', 'width': 716, 'height': 1272},
            {'format_id': 'partial', 'height': 360},
        ])
        self.assertEqual((716, 1272, '720p'), (
            portrait['width'], portrait['height'], portrait['mediaflow_source_quality_label']))
        self.assertEqual(360, partial['height'])
        self.assertNotIn('width', partial)
        self.assertEqual('480p', partial['mediaflow_source_quality_label'])

    def test_progressive_fallbacks_keep_urls_without_inventing_numbered_qualities(self):
        sd, hd = 'https://cdn.example/sd.mp4', 'https://cdn.example/hd.mp4'
        with YoutubeDL({'quiet': True, 'cachedir': False}) as engine:
            configure(engine, 'https://www.facebook.com/reel/12345/')
            extractor = engine.get_info_extractor('Facebook')
            for method, page, expected in (
                (extractor._extract_from_embed, f'{{"sd_src":"{sd}","hd_src":"{hd}"}}', {sd, hd}),
                (extractor._extract_from_mobile, f'<meta property="og:video" content="{sd}">', {sd}),
            ):
                with self.subTest(method=method.__name__), patch.object(FacebookIE, '_download_webpage', return_value=page):
                    formats = method('12345')['formats']
                    self.assertEqual(expected, {fmt['url'] for fmt in formats})
                    for fmt in formats:
                        self.assertFalse({'height', 'width', 'mediaflow_source_quality_label'} & fmt.keys())


if __name__ == '__main__':
    unittest.main()
