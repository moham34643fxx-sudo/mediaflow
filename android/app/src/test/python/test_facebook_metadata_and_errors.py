import socket
import unittest

from facebook_source_formats import (
    _merge_facebook_page_metadata,
    configure as configure_facebook_formats,
    facebook_auth_signal,
    parse_facebook_page_metadata,
)
from network_errors import classify
from playlist_result import page as playlist_page
from yt_dlp import YoutubeDL
from yt_dlp.extractor.facebook import FacebookIE
from unittest.mock import patch


class FacebookMetadataTest(unittest.TestCase):
    def test_facebook_extractor_captures_metadata_only_from_facebook_page_reads(self):
        engine = YoutubeDL({'quiet': True, 'cachedir': False})
        self.addCleanup(engine.close)
        configure_facebook_formats(engine, 'https://www.facebook.com/reel/12345/')
        extractor = engine._ies['Facebook']
        page = (
            '<meta content="Facebook Reel title" property="og:title">'
            '<meta content="https://scontent.xx.fbcdn.net/reel.jpg" property="og:image">'
        )

        with patch.object(FacebookIE, '_download_webpage', return_value=page):
            self.assertEqual(
                page,
                extractor._download_webpage(
                    'https://www.facebook.com/reel/12345/', '12345', fatal=False
                ),
            )
            self.assertEqual(
                {'title': 'Facebook Reel title',
                 'thumbnail': 'https://scontent.xx.fbcdn.net/reel.jpg'},
                extractor._mediaflow_page_metadata,
            )

            extractor._mediaflow_page_metadata = None
            extractor._download_webpage(
                'https://www.facebook.com/plugins/video.php?href=fixture',
                '12345',
                fatal=False,
            )
            self.assertIsNone(extractor._mediaflow_page_metadata)

            extractor._download_webpage(
                'https://attacker.example/reel/12345/',
                '12345',
                fatal=False,
            )
            self.assertIsNone(extractor._mediaflow_page_metadata)

    def test_reordered_meta_attributes_decode_title_and_image(self):
        page = '''
            <meta content="Arabic title &amp; detail" property="og:title">
            <meta content="//scontent.xx.fbcdn.net/cover.jpg?token=a&amp;edge=1" property="og:image">
        '''

        metadata = parse_facebook_page_metadata(page, '12345')

        self.assertEqual('Arabic title & detail', metadata['title'])
        self.assertEqual(
            'https://scontent.xx.fbcdn.net/cover.jpg?token=a&edge=1',
            metadata['thumbnail'],
        )

    def test_poster_fallback_uses_safe_facebook_cdn_and_generic_title_falls_back(self):
        page = '''
            <title>Watch | Facebook</title>
            <video poster="//lookaside.fbsbx.com/cover.jpg"></video>
        '''

        metadata = parse_facebook_page_metadata(page, '12345')

        self.assertEqual('Facebook Video 12345', metadata['title'])
        self.assertEqual('https://lookaside.fbsbx.com/cover.jpg', metadata['thumbnail'])

    def test_untrusted_thumbnail_host_is_ignored(self):
        metadata = parse_facebook_page_metadata(
            '<meta property="og:image" content="https://evil.example/cover.jpg">',
            '12345',
        )

        self.assertIsNone(metadata['thumbnail'])

    def test_invalid_preferred_metadata_falls_through_to_valid_title_and_image(self):
        metadata = parse_facebook_page_metadata(
            '<meta property="og:title" content="Facebook">'
            '<meta name="twitter:title" content="Real title">'
            '<meta property="og:image:secure_url" content="http://127.0.0.1/unsafe.jpg">'
            '<meta property="og:image" content="https://scontent.xx.fbcdn.net/cover.jpg">',
            '12345',
        )

        self.assertEqual('Real title', metadata['title'])
        self.assertEqual('https://scontent.xx.fbcdn.net/cover.jpg', metadata['thumbnail'])

    def test_page_metadata_repairs_generic_extractor_fields_without_weakening_image_policy(self):
        info = {'title': 'Facebook Video', 'thumbnail': None, 'formats': [{'format_id': '720p'}]}
        metadata = parse_facebook_page_metadata(
            '<meta content="عنوان المقطع &amp; المزيد" property="og:title">'
            '<meta content="https://scontent.xx.fbcdn.net/reel.jpg" property="og:image">',
            '12345',
        )

        merged = _merge_facebook_page_metadata(info, metadata, '12345')

        self.assertIs(merged, info)
        self.assertEqual('عنوان المقطع & المزيد', merged['title'])
        self.assertEqual('https://scontent.xx.fbcdn.net/reel.jpg', merged['thumbnail'])
        self.assertEqual([{'format_id': '720p'}], merged['formats'])

    def test_generic_page_title_does_not_replace_a_better_extractor_title(self):
        info = {'title': 'Extractor title', 'thumbnail': None}
        metadata = parse_facebook_page_metadata(
            '<meta property="og:title" content="Facebook">',
            '12345',
        )

        _merge_facebook_page_metadata(info, metadata, '12345')

        self.assertEqual('Extractor title', info['title'])

    def test_generic_login_shell_is_not_authentication_evidence(self):
        self.assertFalse(facebook_auth_signal(
            '<nav>Log in or sign up</nav><a href="/login.php">Log in</a>'
            '<div>Login required</div><p>Please sign in to continue.</p>'
        ))
        self.assertFalse(facebook_auth_signal(
            '<link rel="canonical" href="https://www.facebook.com/watch/?v=123">'
        ))

    def test_explicit_video_auth_gate_is_detected(self):
        self.assertTrue(facebook_auth_signal('You must log in to view this video.'))
        self.assertTrue(facebook_auth_signal('This video requires authentication.'))


class NetworkErrorClassificationTest(unittest.TestCase):
    def test_facebook_context_ignores_generic_login_wording(self):
        self.assertEqual(
            ('EXTRACTION_FAILED', 'extraction'),
            classify(Exception('temporary login shell appeared while reading public metadata'),
                     platform='facebook'),
        )
        self.assertEqual(
            ('EXTRACTION_FAILED', 'extraction'),
            classify(Exception('Login required'), platform='facebook'),
        )

    def test_facebook_context_keeps_explicit_authentication_errors(self):
        self.assertEqual(
            ('AUTHENTICATION_REQUIRED', 'extraction'),
            classify(Exception('This video requires authentication. Please log in to Facebook.'),
                     platform='facebook'),
        )
        auth_gate = Exception('This video requires authentication.')
        auth_gate.status = 403
        self.assertEqual(
            ('AUTHENTICATION_REQUIRED', 'extraction'),
            classify(auth_gate, platform='facebook'),
        )

    def test_non_facebook_legacy_auth_classification_is_unchanged(self):
        self.assertEqual(
            ('AUTHENTICATION_REQUIRED', 'extraction'),
            classify(Exception('login required')),
        )

    def test_vimeo_login_gated_client_is_classified_as_authentication_required(self):
        self.assertEqual(
            ('AUTHENTICATION_REQUIRED', 'extraction'),
            classify(
                Exception('The web client only works when logged-in.'),
                platform='vimeo',
            ),
        )

    def test_generic_sign_in_wording_is_not_authentication_evidence(self):
        for message in ('please sign in', 'log in to continue', 'sign in to confirm'):
            with self.subTest(message=message):
                self.assertEqual(
                    ('EXTRACTION_FAILED', 'extraction'),
                    classify(Exception(message)),
                )

    def test_youtube_bot_challenge_is_not_reported_as_login_required(self):
        self.assertEqual(
            ('BOT_DETECTED', 'extraction'),
            classify(Exception("Sign in to confirm you're not a bot")),
        )
        challenge = Exception("Sign in to confirm you're not a bot")
        challenge.status = 403
        self.assertEqual(('BOT_DETECTED', 'extraction'), classify(challenge))
        self.assertEqual(
            ('AUTHENTICATION_REQUIRED', 'extraction'),
            classify(Exception('Sign in to confirm your age')),
        )

    def test_explicit_private_content_takes_precedence_over_login_hint(self):
        self.assertEqual(
            ('PRIVATE_CONTENT', 'extraction'),
            classify(Exception('Private video. Sign in if you have permission.')),
        )

    def test_real_http_status_keeps_precedence(self):
        error = Exception('Facebook login page was returned')
        error.status = 403

        self.assertEqual(
            ('HTTP_FORBIDDEN', 'http'),
            classify(error, platform='facebook'),
        )
        expired = Exception('The media URL is no longer valid')
        expired.status = 410
        self.assertEqual(('MEDIA_URL_EXPIRED', 'http'), classify(expired))
        rate_limited = Exception('Too many requests')
        rate_limited.status = 429
        self.assertEqual(('RATE_LIMITED', 'http'), classify(rate_limited))

    def test_network_and_format_failures_keep_their_categories(self):
        self.assertEqual(('DNS_FAILED', 'dns'), classify(socket.gaierror('name resolution failed')))
        self.assertEqual(
            ('FORMAT_UNAVAILABLE', 'extraction'),
            classify(Exception('Requested format is not available')),
        )


class PlaylistAvailabilityTest(unittest.TestCase):
    def test_private_entry_is_not_mislabeled_as_login_required(self):
        result = playlist_page(
            {'_type': 'playlist', 'entries': [{
                'id': 'private-id',
                'webpage_url': 'https://www.youtube.com/watch?v=private-id',
                'availability': 'private',
            }]},
            'https://www.youtube.com/playlist?list=example',
            0,
            10,
            lambda value: value,
            lambda: None,
        )

        self.assertEqual('PRIVATE_CONTENT', result['items'][0]['reason'])

    def test_explicit_auth_availability_still_requires_authentication(self):
        result = playlist_page(
            {'_type': 'playlist', 'entries': [{
                'id': 'auth-id',
                'webpage_url': 'https://www.youtube.com/watch?v=auth-id',
                'availability': 'needs_auth',
            }]},
            'https://www.youtube.com/playlist?list=example',
            0,
            10,
            lambda value: value,
            lambda: None,
        )

        self.assertEqual('AUTHENTICATION_REQUIRED', result['items'][0]['reason'])


if __name__ == '__main__':
    unittest.main()
