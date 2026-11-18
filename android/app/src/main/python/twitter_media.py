"""Scoped public-metadata recovery; playback bytes remain on X's validated CDN."""
import math
from urllib.parse import urlsplit


def _cdn_url(url, host, validate):
    if not isinstance(url, str) or len(url) > 8192:
        return None
    try:
        parsed = urlsplit(url)
        if (parsed.scheme != 'https' or parsed.hostname != host or parsed.username is not None
                or parsed.password is not None or parsed.port not in (None, 443) or parsed.fragment):
            return None
        validate(url)
    except (ValueError, TypeError):
        return None
    return url


def _number(value, maximum):
    return value if type(value) in (int, float) and math.isfinite(value) and 0 < value <= maximum else None


def _video_formats(media, validate):
    candidates = media.get('formats')
    if not isinstance(candidates, list) or len(candidates) > 32:
        return []
    formats, seen = [], set()
    for candidate in candidates:
        if not isinstance(candidate, dict):
            continue
        url = _cdn_url(candidate.get('url'), 'video.twimg.com', validate)
        if not url or not urlsplit(url).path.endswith('.mp4') or url in seen:
            continue
        seen.add(url)
        source = {'format_id': f'public-http-{len(formats)}', 'url': url, 'ext': 'mp4', 'protocol': 'https'}
        from yt_dlp.extractor.twitter import TwitterBaseIE
        TwitterBaseIE._search_dimensions_in_video_url(source, url)
        bitrate = _number(candidate.get('bitrate'), 1_000_000_000)
        if bitrate is not None:
            source['tbr'] = bitrate / 1000
        if candidate.get('codec') == 'h264' or '/avc1/' in urlsplit(url).path:
            source['vcodec'] = 'h264'
        formats.append(source)
    return formats


def public_result(payload, post_id, selected_index, validate):
    """Treat every provider field as untrusted; never substitute another post or media index."""
    if not isinstance(payload, dict) or payload.get('code') != 200:
        return None
    tweet = payload.get('tweet')
    if not isinstance(tweet, dict) or tweet.get('id') != post_id:
        return None
    author = tweet.get('author')
    if not isinstance(author, dict) or author.get('protected') is not False:
        return None
    media = tweet.get('media')
    entries = media.get('all') if isinstance(media, dict) else None
    if not isinstance(entries, list) or not 1 <= len(entries) <= 16:
        return None
    if selected_index is not None:
        index = int(selected_index) - 1
        entries = entries[index:index + 1] if 0 <= index < len(entries) else []
    videos = [_video_result(entry, tweet, post_id, validate) for entry in entries
              if isinstance(entry, dict) and entry.get('type') == 'video']
    videos = [entry for entry in videos if entry is not None]
    if not videos:
        return None
    if len(videos) == 1:
        return videos[0]
    return {'_type': 'playlist', 'id': post_id, 'entries': videos, 'title': f'X {post_id}'}


def _video_result(media, tweet, post_id, validate):
    formats = _video_formats(media, validate)
    media_id = media.get('id')
    if not formats or not isinstance(media_id, str) or not media_id.isascii() or not media_id.isdigit():
        return None
    author = tweet['author']
    return {'id': media_id, 'display_id': post_id, 'title': f'X {post_id}',
            'webpage_url': f'https://x.com/i/web/status/{post_id}', 'formats': formats,
            'duration': _number(media.get('duration'), 24 * 3600),
            'uploader': author.get('name')[:200] if isinstance(author.get('name'), str) else None,
            'thumbnail': _cdn_url(media.get('thumbnail_url'), 'pbs.twimg.com', validate)}


def configure(engine, url, validate):
    from yt_dlp.extractor.twitter import TwitterIE
    from yt_dlp.utils import ExtractorError
    if not TwitterIE.suitable(url):
        return

    class PublicRecoveryTwitterIE(TwitterIE):
        @classmethod
        def ie_key(cls):
            return TwitterIE.ie_key()

        def _graphql_to_legacy(self, data, post_id):
            result = data.get('tweetResult', {}).get('result', {}) if isinstance(data, dict) else {}
            self._guest_tombstone = isinstance(result, dict) and result.get('__typename') == 'TweetTombstone' and not result.get('tombstone')
            card = result.get('card') if isinstance(result, dict) else None
            card = card.get('legacy') if isinstance(card, dict) else None
            self._public_unified_card = (
                isinstance(result, dict)
                and result.get('__typename') in ('Tweet', 'TweetWithVisibilityResults')
                and isinstance(card, dict)
                and str(card.get('name', '')).split(':')[-1] == 'unified_card'
            )
            return super()._graphql_to_legacy(data, post_id)

        def _is_indexed_unified_card_gap(self, error, index):
            return (
                self._public_unified_card
                and index is not None
                and str(error).strip() == f'Video #{index} is unavailable'
            )

        def _real_extract(self, source_url):
            self._guest_tombstone = False
            self._public_unified_card = False
            try:
                return super()._real_extract(source_url)
            except ExtractorError as original:
                if self.is_logged_in:
                    raise
                post_id, index = self._match_valid_url(source_url).group('id', 'index')
                if not self._guest_tombstone and not self._is_indexed_unified_card_gap(original, index):
                    raise
                # Forward only the post ID; never send cookies, session credentials, or CDN URLs.
                payload = self._download_json(f'https://api.fxtwitter.com/status/{post_id}', post_id,
                                              note='Recovering public X metadata', fatal=False)
                recovered = public_result(payload, post_id, index, validate)
                if recovered is None:
                    raise original
                return recovered

    engine.add_info_extractor(PublicRecoveryTwitterIE())
