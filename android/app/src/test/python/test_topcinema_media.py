import unittest
from pathlib import Path
import tempfile
from types import SimpleNamespace
from unittest.mock import patch
from urllib.parse import urlsplit

from yt_dlp import YoutubeDL
from yt_dlp.extractor.generic import GenericIE

from analysis_engine import AnalysisYoutubeDL
import topcinema_media as adapter
from playlist_result import page as normalize_playlist


WATCH = 'https://topcinemaa.one/fixture/watch/'
EMBED = 'https://down.vidtube.one/embed-fixture.html'
FINAL_EMBED = 'https://player.vidtube.one/embed-fixture.html'
MEDIA = 'https://cdn.example.test/master.m3u8?sig=fixture-secret'
SERIES = 'https://topcinemaa.one/series/fixture-show'
SEASON_1 = 'https://topcinemaa.one/series/fixture-season-1'
SEASON_2 = 'https://topcinemaa.one/series/fixture-season-2'
SEASON_3 = 'https://topcinemaa.one/series/fixture-season-3'
EPISODE_ARTICLE = 'https://topcinemaa.one/fixture-season-1-episode-1'
EPISODE_WATCH = 'https://topcinemaa.one/fixture-season-1-episode-1/watch'
PLAYER_PAGE = (
    '<script>jwplayer("vplayer").setup({'
    f'file:"{MEDIA}",duration:"4413.57"'
    '});</script>')


class _FixtureResponse:
    status = 200

    def __init__(self, url, body):
        self.url = url
        self._body = body
        self._offset = 0
        content_type = (
            'application/vnd.apple.mpegurl'
            if urlsplit(url).path.endswith('.m3u8') else 'video/mp2t')
        self.headers = {
            'Content-Type': content_type,
            'Content-Length': str(len(body)),
        }

    def read(self, amount=-1):
        if amount is None or amount < 0:
            amount = len(self._body) - self._offset
        data = self._body[self._offset:self._offset + amount]
        self._offset += len(data)
        return data

    def geturl(self):
        return self.url

    def close(self):
        pass

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()


class TopCinemaMediaTest(unittest.TestCase):
    def test_series_and_season_pages_emit_only_exposed_episode_articles(self):
        ydl = AnalysisYoutubeDL({
            'quiet': True, 'noplaylist': False, 'cachedir': False,
            'extract_flat': 'in_playlist', 'playlist_items': '1:101',
        })
        self.addCleanup(ydl.close)
        adapter.configure(ydl, SERIES, lambda value: value)
        ie = ydl._ies['Generic']
        pages = {
            SERIES: (
                '<meta property="og:title" content="مسلسل Fixture Show مترجم">'
                '<meta property="og:site_name" content="Top Cinema">'
                '<a href="/series/fixture-season-2">الموسم 2 مسلسل Fixture Show</a>'
                '<a href="/series/fixture-season-1">الموسم 1 مسلسل Fixture Show</a>'
                '<a href="/series/fixture-season-3">الموسم 3 مسلسل Fixture Show</a>'
                '<a href="/series/unrelated-season-1">الموسم 1 مسلسل Other Show</a>'),
            SEASON_1: (
                '<meta property="og:title" content="مسلسل Fixture Show الموسم 1">'
                '<a href="/fixture-s1-e2">مسلسل Fixture Show الموسم 1 الحلقة 2</a>'
                '<a href="/fixture-s1-e1">مسلسل Fixture Show الموسم 1 الحلقة 1</a>'
                '<a href="https://outside.example/fixture-s1-e3">مسلسل Fixture Show الموسم 1 الحلقة 3</a>'),
            SEASON_2: (
                '<meta property="og:title" content="مسلسل Fixture Show الموسم 2">'
                '<a href="/fixture-s2-e1">مسلسل Fixture Show الموسم 2 الحلقة 1</a>'
                '<a href="/series/fixture-season-2">مسلسل Fixture Show الموسم 2 الحلقة 9</a>'),
        }
        requests = []

        def download_page(target, *_args, **_kwargs):
            requests.append(target)
            if target not in pages:
                raise adapter.ExtractorError('fixture season unavailable', expected=True)
            return pages[target], SimpleNamespace(url=target)

        ie._download_webpage_handle = download_page
        info = ydl.extract_info(SERIES, download=False)
        self.assertEqual(info['mediaflow_collection_type'], 'topcinema_series')
        self.assertEqual(info['playlist_count'], 3)
        self.assertEqual(
            [(entry['season_number'], entry['episode_number'], entry['title'])
             for entry in info['entries']],
            [
                (1, 1, 'مسلسل Fixture Show الموسم 1 الحلقة 1'),
                (1, 2, 'مسلسل Fixture Show الموسم 1 الحلقة 2'),
                (2, 1, 'مسلسل Fixture Show الموسم 2 الحلقة 1'),
            ])
        self.assertEqual(len(info['mediaflow_unverified_seasons']), 1)
        self.assertEqual(info['mediaflow_unverified_seasons'][0]['season_number'], 3)
        self.assertNotIn('fixture-season-3', ' '.join(entry['url'] for entry in info['entries']))

        page = normalize_playlist(info, SERIES, 0, 100, lambda value: value, lambda: None)
        self.assertEqual(page['collection_type'], 'topcinema_series')
        self.assertEqual(
            [(item['season_number'], item['episode_number']) for item in page['items']],
            [(1, 1), (1, 2), (2, 1)])
        self.assertEqual(page['unverified_seasons'][0]['season_number'], 3)

        season_info = ydl.extract_info(SEASON_2, download=False)
        self.assertEqual(season_info['mediaflow_collection_type'], 'topcinema_season')
        self.assertEqual(season_info['mediaflow_season_number'], 2)
        self.assertEqual([entry['episode_number'] for entry in season_info['entries']], [1])
        self.assertIn(SEASON_3, requests)

    def test_arabic_season_and_episode_numbers_are_parsed_without_guessing(self):
        self.assertEqual(adapter.season_number('مسلسل Fixture الموسم الثالث مترجم'), 3)
        self.assertEqual(adapter.season_number('الموسم ٤'), 4)
        self.assertEqual(adapter.episode_number('الحلقة ١٢ مترجمة'), 12)
        self.assertIsNone(adapter.season_number('مسلسل بلا رقم موسم'))
        self.assertIsNone(adapter.episode_number('الحلقة الأخيرة'))

    def test_episode_article_uses_its_exposed_watch_route(self):
        ydl = AnalysisYoutubeDL({'quiet': True, 'noplaylist': True, 'cachedir': False})
        self.addCleanup(ydl.close)
        article_html = (
            '<meta property="og:title" content="مسلسل Fixture الموسم 1 الحلقة 1">'
            '<a href="/fixture-season-1-episode-1/watch">مشاهدة الان الذهاب لصفحة المشاهدة</a>')
        requested = []
        checked = []
        hls_calls = []

        def validate(value):
            checked.append(value)
            return value

        adapter.configure(ydl, EPISODE_ARTICLE, validate)
        ie = ydl._ies['Generic']

        def download_page(target, *_args, **_kwargs):
            requested.append(target)
            if target == EPISODE_ARTICLE:
                return article_html, SimpleNamespace(url=target)
            if target == EPISODE_WATCH:
                return (
                    '<meta property="og:title" content="Fixture episode - Top Cinema">'
                    '<meta property="og:site_name" content="Top Cinema">'
                    '<meta property="og:image" content="https://topcinemaa.one/fixture.jpg">'
                    '<iframe src="' + EMBED + '"></iframe>',
                    SimpleNamespace(url=target))
            if target == EMBED:
                return PLAYER_PAGE, SimpleNamespace(url=FINAL_EMBED)
            raise AssertionError('Unexpected fixture request')

        ie._download_webpage_handle = download_page
        ie._extract_m3u8_formats = lambda media, video_id, ext, **kwargs: (
            hls_calls.append((media, kwargs)) or [{
                'format_id': 'hls-720', 'url': media, 'ext': ext,
                'protocol': 'm3u8_native', 'width': 1280, 'height': 720,
                'vcodec': 'avc1', 'acodec': 'mp4a',
            }])
        info = ydl.extract_info(EPISODE_ARTICLE, download=False)
        self.assertEqual(requested, [EPISODE_ARTICLE, EPISODE_WATCH, EMBED])
        self.assertEqual(hls_calls[0][0], MEDIA)
        self.assertEqual(info['extractor_key'], 'TopCinema')
        self.assertIn(EPISODE_WATCH, checked)

    def test_matches_is_limited_to_supported_https_watch_routes(self):
        accepted = (
            WATCH,
            'https://TOPCINEMAA.ONE/fixture/watch/?episode=1',
            'https://topcinema.cam/some-film/watch/',
            'https://topcinemaa.one/some-episode/watch',
        )
        rejected = (
            'http://topcinemaa.one/fixture/watch/',
            'https://topcinemaa.one/',
            'https://topcinemaa.one/fixture/download/',
            'https://topcinemaa.one.example/fixture/watch/',
            'https://user@topcinemaa.one/fixture/watch/',
            'https://topcinemaa.one:444/fixture/watch/',
            'https://example.com/fixture/watch/',
        )
        for url in accepted:
            with self.subTest(url=url):
                self.assertTrue(adapter.matches(url))
        for url in rejected:
            with self.subTest(url=url):
                self.assertFalse(adapter.matches(url))
        self.assertFalse(adapter.matches(None))

    def test_unpack_is_bounded_and_rejects_unsupported_radix(self):
        packed = (
            "eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace("
            "new RegExp('\\\\b'+c.toString(a)+'\\\\b','g'),k[c]);return p}"
            "('0(\"1\").2({3:[{4:\"5\"}]});',62,6,"
            "'jwplayer|vplayer|setup|sources|file|https://x/master.m3u8'"
            ".split('|'))")
        self.assertEqual(
            adapter._unpack(packed),
            'jwplayer("vplayer").setup({sources:[{file:"https://x/master.m3u8"}]});')
        self.assertIsNone(adapter._unpack('no packed script'))

        words = '|'.join(['word'] * 64)
        unsupported_radix = f"return p}}('63',64,64,'{words}'.split('|'))"
        self.assertIsNone(adapter._unpack(unsupported_radix))

        oversized = "return p}('" + ('0 ' * 100) + "',2,1,'" + ('x' * 6000) + "')"
        self.assertIsNone(adapter._unpack(oversized))

    def test_unpack_preserves_ordered_packer_replacement_semantics(self):
        chained = "return p}('2',10,3,'leaf|0|1'.split('|'))"
        self.assertEqual(adapter._unpack(chained), 'leaf')
        # The original loop only processes indices below the supplied count.
        limited_count = "return p}('2',10,2,'leaf|one|two'.split('|'))"
        self.assertEqual(adapter._unpack(limited_count), '2')

    def test_player_config_extracts_url_and_bounded_duration(self):
        media, duration = adapter._player_config(PLAYER_PAGE)
        self.assertEqual(media, MEDIA)
        self.assertEqual(duration, 4413.57)
        self.assertEqual(urlsplit(media).hostname, 'cdn.example.test')
        self.assertEqual(adapter._player_config('<html>no source</html>'), (None, None))
        outside_packed_script = (
            "return p}('x',2,1,'a'.split('|'))"
            '<script>file:"https://cdn.example.test/fallback.m3u8"</script>')
        self.assertEqual(
            adapter._player_config(outside_packed_script)[0],
            'https://cdn.example.test/fallback.m3u8')
        self.assertIsNone(adapter._player_config('x' * (adapter._PAGE_LIMIT + 1))[0])
        self.assertIsNone(adapter._player_config(
            '<script>file:"https://cdn.example.test/a.m3u8",duration:"999999999"</script>')[1])

    def test_only_the_verified_false_videotube_fps_signature_is_rewritten(self):
        corrected = {'width': 1920, 'height': 800, 'fps': 95.948}
        other_fps = {'width': 1920, 'height': 800, 'fps': 95.949}
        other_size = {'width': 1280, 'height': 532, 'fps': 95.948}
        adapter._correct_verified_videotube_fps(corrected)
        adapter._correct_verified_videotube_fps(other_fps)
        adapter._correct_verified_videotube_fps(other_size)
        self.assertEqual(corrected['fps'], 24)
        self.assertEqual(other_fps['fps'], 95.949)
        self.assertEqual(other_size['fps'], 95.948)

    def test_registration_extracts_using_final_embed_referer_and_preserves_headers(self):
        ydl = AnalysisYoutubeDL({'quiet': True, 'noplaylist': True, 'cachedir': False})
        self.addCleanup(ydl.close)
        checked = []
        requests = []
        hls_calls = []
        generic_index = list(ydl._ies).index('Generic')
        youtube_extractor = ydl._ies['Youtube']

        def validate(value):
            checked.append(value)
            return value

        adapter.configure(ydl, WATCH, validate)
        ie = ydl._ies['Generic']
        self.assertEqual(ie.ie_key(), 'Generic')
        self.assertEqual(ie.IE_NAME, 'topcinema')
        self.assertEqual(list(ydl._ies).index('Generic'), generic_index)
        self.assertIs(ydl._ies['Youtube'], youtube_extractor)

        watch_html = (
            '<html><head><title>Fixture title - Top Cinema</title>'
            '<meta property="og:title" content="Fixture title - Top Cinema">'
            '<meta property="og:site_name" content="Top Cinema">'
            '<meta property="og:image" content="https://topcinemaa.one/thumb.jpg">'
            '</head>'
            '<iframe src="https://down.vidtube.one/embed-fixture.html"></iframe></html>')

        def download_page(target, video_id, note=None, headers=None):
            requests.append((target, headers))
            if target == WATCH:
                return watch_html, SimpleNamespace(url=WATCH)
            if target == EMBED:
                return PLAYER_PAGE, SimpleNamespace(url=FINAL_EMBED)
            raise AssertionError('Unexpected fixture request')

        def extract_hls(media, video_id, ext, **kwargs):
            hls_calls.append((media, kwargs))
            return [{
                'format_id': 'hls-720',
                'url': media,
                'ext': ext,
                'protocol': 'm3u8_native',
                'width': 1280,
                'height': 720,
                'vcodec': 'avc1',
                'acodec': 'mp4a',
                'http_headers': {
                    'User-Agent': 'fixture-agent',
                    'X-Origin': 'keep-me',
                    'referer': 'https://stale.example/',
                },
                'fragments': [{
                    'url': 'https://cdn.example.test/segment.ts',
                    'http_headers': {'X-Fragment': 'keep-fragment'},
                }],
            }]

        ie._download_webpage_handle = download_page
        ie._extract_m3u8_formats = extract_hls
        info = ydl.extract_info(WATCH, download=False)

        self.assertEqual(info['extractor_key'], 'TopCinema')
        self.assertEqual(info['duration'], 4413.57)
        self.assertEqual([row[0] for row in requests], [WATCH, EMBED])
        self.assertIsNone(requests[0][1])
        self.assertEqual(requests[1][1], {'Referer': WATCH})
        self.assertEqual(hls_calls[0][0], MEDIA)
        self.assertEqual(hls_calls[0][1]['headers'], {'Referer': FINAL_EMBED})
        self.assertIn(EMBED, checked)
        self.assertIn(FINAL_EMBED, checked)
        self.assertIn(MEDIA, checked)

        fmt = info['formats'][0]
        headers = fmt['http_headers']
        self.assertEqual(headers['Referer'], FINAL_EMBED)
        self.assertEqual(headers['User-Agent'], 'fixture-agent')
        self.assertEqual(headers['X-Origin'], 'keep-me')
        self.assertEqual(sum(key.lower() == 'referer' for key in headers), 1)
        fragment_headers = fmt['fragments'][0]['http_headers']
        self.assertEqual(fragment_headers['Referer'], FINAL_EMBED)
        self.assertEqual(fragment_headers['X-Fragment'], 'keep-fragment')
        calculated = ydl._calc_headers(dict(fmt), load_cookies=True)
        self.assertEqual(calculated['Referer'], FINAL_EMBED)

    def test_real_ytdlp_hls_parser_preserves_verified_qualities_and_corrects_bad_fps(self):
        ydl = YoutubeDL({'quiet': True, 'noplaylist': True, 'cachedir': False})
        self.addCleanup(ydl.close)
        adapter.configure(ydl, WATCH, lambda value: value)
        ie = ydl._ies['Generic']
        watch_html = (
            '<html><head>'
            '<meta property="og:title" content="Fixture title">'
            '<meta property="og:site_name" content="Fixture site">'
            '<meta property="og:image" content="https://topcinemaa.one/thumb.jpg">'
            '</head><iframe src="' + EMBED + '"></iframe></html>')
        ie._download_webpage_handle = lambda target, *args, **kwargs: (
            watch_html if target == WATCH else PLAYER_PAGE,
            SimpleNamespace(url=target))

        manifest = (
            '#EXTM3U\n'
            '#EXT-X-STREAM-INF:BANDWIDTH=2814517,FRAME-RATE=95.948,RESOLUTION=1920x800\n'
            'highest.m3u8\n'
            '#EXT-X-STREAM-INF:BANDWIDTH=1321635,FRAME-RATE=24.0,RESOLUTION=1280x532\n'
            'high.m3u8\n'
            '#EXT-X-STREAM-INF:BANDWIDTH=449876,FRAME-RATE=24.0,RESOLUTION=852x356\n'
            'medium.m3u8\n'
            '#EXT-X-STREAM-INF:BANDWIDTH=259956,FRAME-RATE=24.0,RESOLUTION=428x176\n'
            'low.m3u8\n'
        ).encode()
        requests = []

        def urlopen(request):
            target = request.url
            requests.append(request)
            if target.split('?', 1)[0] != MEDIA.split('?', 1)[0]:
                raise AssertionError('Unexpected request outside the synthetic manifest')
            return _FixtureResponse(target, manifest)

        ydl.urlopen = urlopen
        info = ydl.extract_info(WATCH, download=False)
        formats = info['formats']

        self.assertEqual(
            {(fmt['width'], fmt['height'], fmt['fps']) for fmt in formats},
            {
                (1920, 800, 24),
                (1280, 532, 24.0),
                (852, 356, 24.0),
                (428, 176, 24.0),
            })
        self.assertTrue(all(fmt['protocol'] == 'm3u8_native' for fmt in formats))
        self.assertTrue(all(
            fmt['http_headers']['Referer'] == EMBED for fmt in formats))
        self.assertEqual(len(requests), 1)
        self.assertEqual(
            urlsplit(requests[0].url).path, urlsplit(MEDIA).path)
        self.assertTrue(all(
            next(value for key, value in request.headers.items()
                 if key.lower() == 'referer') == EMBED
            for request in requests))

    def test_synthetic_download_path_sends_embed_referer_to_playlist_and_segment(self):
        master = (
            '#EXTM3U\n'
            '#EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=1280x720\n'
            '720.m3u8\n'
        ).encode()
        rendition = (
            '#EXTM3U\n'
            '#EXT-X-TARGETDURATION:1\n'
            '#EXTINF:1.0,\n'
            'segment.ts\n'
            '#EXT-X-ENDLIST\n'
        ).encode()
        segment = b'controlled-fixture-segment-bytes\n'
        watch_html = (
            '<html><head>'
            '<meta property="og:title" content="Fixture title">'
            '<meta property="og:site_name" content="Fixture site">'
            '<meta property="og:image" content="https://topcinemaa.one/thumb.jpg">'
            '</head><iframe src="' + EMBED + '"></iframe></html>')

        with tempfile.TemporaryDirectory(prefix='mediaflow-hls-fixture-') as directory:
            target = str(Path(directory) / 'fixture.mp4')
            ydl = YoutubeDL({
                'quiet': True,
                'noprogress': True,
                'noplaylist': True,
                'cachedir': False,
                'outtmpl': target,
                'hls_prefer_native': True,
                'fixup': 'never',
                'retries': 0,
                'fragment_retries': 0,
                'skip_unavailable_fragments': False,
            })
            with ydl:
                adapter.configure(ydl, WATCH, lambda value: value)
                ie = ydl._ies['Generic']
                ie._download_webpage_handle = lambda requested, *args, **kwargs: (
                    watch_html if requested == WATCH else PLAYER_PAGE,
                    SimpleNamespace(url=requested))
                requests = []

                def urlopen(request):
                    url = request.url
                    requests.append((url, dict(request.headers)))
                    path = urlsplit(url).path
                    if path.endswith('/master.m3u8'):
                        body = master
                    elif path.endswith('/720.m3u8'):
                        body = rendition
                    elif path.endswith('/segment.ts'):
                        body = segment
                    else:
                        raise AssertionError(
                            'Unexpected request outside the controlled fixture')
                    return _FixtureResponse(url, body)

                ydl.urlopen = urlopen
                info = ydl.extract_info(WATCH, download=False)
                selected = next(
                    fmt for fmt in info['formats'] if fmt.get('height') == 720)
                download_info = dict(selected, id='fixture', title='fixture')
                download_info['http_headers'] = ydl._calc_headers(
                    download_info, load_cookies=True)
                ydl.process_info(download_info)

            self.assertEqual(
                [urlsplit(url).path.rsplit('/', 1)[-1] for url, _ in requests],
                ['master.m3u8', '720.m3u8', 'segment.ts'])
            self.assertTrue(all(
                next(value for key, value in headers.items()
                     if key.lower() == 'referer') == EMBED
                for _, headers in requests))
            self.assertEqual(Path(target).stat().st_size, len(segment))

    def test_unrelated_configuration_does_not_touch_engine(self):
        class EngineWithoutRegistration:
            pass

        adapter.configure(
            EngineWithoutRegistration(),
            'https://example.com/fixture/watch/',
            lambda value: value)

    def test_unrelated_urls_keep_stock_generic_behavior(self):
        ydl = YoutubeDL({'quiet': True})
        self.addCleanup(ydl.close)
        adapter.configure(ydl, WATCH, lambda value: value)
        ie = ydl._ies['Generic']
        with patch.object(
                GenericIE, '_real_extract', autospec=True,
                return_value={'id': 'stock-generic'}) as stock:
            result = ie._real_extract('https://example.com/watch')
        self.assertEqual(result, {'id': 'stock-generic'})
        stock.assert_called_once()

    def test_private_iframe_is_rejected_before_request(self):
        ydl = YoutubeDL({'quiet': True})
        self.addCleanup(ydl.close)
        requests = []

        def validate(value):
            if '127.0.0.1' in value:
                raise ValueError('POLICY_ADDRESS')
            return value

        adapter.configure(ydl, WATCH, validate)
        ie = ydl._ies['Generic']

        def download_page(target, video_id, note=None, headers=None):
            requests.append(target)
            return (
                '<iframe src="https://127.0.0.1/embed.html"></iframe>',
                SimpleNamespace(url=WATCH))

        ie._download_webpage_handle = download_page
        with self.assertRaisesRegex(ValueError, 'POLICY_ADDRESS'):
            ie._real_extract(WATCH)
        self.assertEqual(requests, [WATCH])

    def test_missing_embed_falls_back_to_stock_generic(self):
        ydl = YoutubeDL({'quiet': True})
        self.addCleanup(ydl.close)
        adapter.configure(ydl, WATCH, lambda value: value)
        ie = ydl._ies['Generic']
        ie._download_webpage_handle = lambda *args, **kwargs: (
            '<html><title>no player</title></html>', SimpleNamespace(url=WATCH))
        with patch.object(
                GenericIE, '_real_extract', autospec=True,
                return_value={'id': 'stock-generic'}) as stock:
            result = ie._real_extract(WATCH)
        self.assertEqual(result, {'id': 'stock-generic'})
        stock.assert_called_once()


if __name__ == '__main__':
    unittest.main()
