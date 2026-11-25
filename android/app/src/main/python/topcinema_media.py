"""Recover TopCinema watch-page HLS metadata without evaluating page JavaScript."""
import html
from dataclasses import dataclass
from html.parser import HTMLParser
import io
import math
import re
from urllib.parse import urljoin, urlsplit, urlunsplit

from yt_dlp.extractor.generic import GenericIE
from yt_dlp.utils import ExtractorError


_HOSTS = frozenset((
    'topcinemaa.one', 'www.topcinemaa.one',
    'topcinema.cam', 'www.topcinema.cam',
))
_PAGE_LIMIT = 8 * 1024 * 1024
_PACK_MARKER = 'return p}('
_RADIX_DIGITS = '0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ'
_MAX_WORDS = 4096
_MAX_UNPACKED = 512 * 1024
_MAX_EXPANDED_WORDS = 2 * 1024 * 1024
_MAX_IFRAMES = 32
_MAX_PAGE_LINKS = 10000
_MAX_SEASON_PAGES = 40
_MAX_EPISODES = 5000
_WORD_TOKEN_RE = re.compile(r'[A-Za-z0-9_]+')
_MEDIA_RE = re.compile(
    r'\bfile\s*:\s*["\'](https?:[^"\']*?\.m3u8[^"\']*)["\']', re.I)
_DURATION_RE = re.compile(
    r'\bduration\s*:\s*(["\']?)(\d+(?:\.\d+)?)\1', re.I)
_EMBED_ID_RE = re.compile(r'embed-([A-Za-z0-9_-]+)', re.I)
_TITLE_RE = re.compile(r'<title\b[^>]*>([^<]+)</title>', re.I)


def matches(url):
    """Return True only for HTTPS watch routes on the explicitly supported hosts."""
    if not isinstance(url, str):
        return False
    try:
        parts = urlsplit(url)
        host = (parts.hostname or '').lower()
        port = parts.port
    except (TypeError, ValueError, UnicodeError):
        return False
    return (
        parts.scheme == 'https'
        and host in _HOSTS
        and port in (None, 443)
        and parts.username is None
        and parts.password is None
        and (parts.path == '/watch' or parts.path.endswith('/watch') or parts.path.endswith('/watch/'))
    )


class _IframeParser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.sources = []

    def handle_starttag(self, tag, attrs):
        if tag.lower() != 'iframe':
            return
        for key, value in attrs:
            if key.lower() == 'src' and value:
                if len(self.sources) <= _MAX_IFRAMES:
                    self.sources.append(value)
                return

    handle_startendtag = handle_starttag


class _SourcePageParser(HTMLParser):
    """Collect bounded display metadata and links from TopCinema source pages."""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.anchors = []
        self.meta = {}
        self.headings = []
        self._anchor = None
        self._heading = None

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        tag = tag.lower()
        if tag == 'meta':
            key = (attrs.get('property') or attrs.get('name') or '').lower()
            if key in {'og:title', 'og:site_name', 'og:image', 'description'}:
                meta_content = attrs.get('content', '')
                if isinstance(meta_content, str):
                    self.meta[key] = meta_content[:2048]
        elif tag == 'a' and len(self.anchors) < _MAX_PAGE_LINKS:
            self._anchor = [attrs.get('href', ''), [], 0]
        elif tag in {'h1', 'h2', 'h3'}:
            self._heading = [tag, []]

    def handle_endtag(self, tag):
        tag = tag.lower()
        if tag == 'a' and self._anchor is not None:
            href, chunks, _ = self._anchor
            label = ' '.join(' '.join(chunks).split())
            self.anchors.append((href, label[:2048]))
            self._anchor = None
        if self._heading is not None and tag == self._heading[0]:
            self.headings.append(' '.join(' '.join(self._heading[1]).split())[:2048])
            self._heading = None

    def handle_data(self, data):
        if self._anchor is not None and self._anchor[2] < 2048:
            remaining = 2048 - self._anchor[2]
            chunk = data[:remaining]
            self._anchor[1].append(chunk)
            self._anchor[2] += len(chunk)
        if self._heading is not None:
            self._heading[1].append(data[:2048])


@dataclass(frozen=True)
class _CollectionPage:
    url: str
    title: str
    season_number: int
    parser: _SourcePageParser


@dataclass(frozen=True)
class _SeasonLink:
    url: str
    title: str
    season_number: int


@dataclass(frozen=True)
class _EpisodeLink:
    url: str
    title: str
    season_number: int
    episode_number: int


def _supported_site_url(url):
    if not isinstance(url, str):
        return False
    try:
        parts = urlsplit(url)
        return (
            parts.scheme == 'https'
            and (parts.hostname or '').lower() in _HOSTS
            and parts.port in (None, 443)
            and parts.username is None
            and parts.password is None
        )
    except (TypeError, ValueError, UnicodeError):
        return False


def is_collection_url(url):
    """True for TopCinema series and season listings (both use /series/<slug>)."""
    if not _supported_site_url(url):
        return False
    parts = urlsplit(url)
    segments = [segment for segment in parts.path.split('/') if segment]
    return len(segments) == 2 and segments[0].lower() == 'series'


def _is_article_candidate(url):
    if not _supported_site_url(url) or matches(url) or is_collection_url(url):
        return False
    segments = [segment for segment in urlsplit(url).path.split('/') if segment]
    return len(segments) == 1


def _source_page(webpage):
    if not isinstance(webpage, str) or len(webpage) > _PAGE_LIMIT:
        raise ExtractorError('TopCinema source page exceeds its metadata limit', expected=True)
    parser = _SourcePageParser()
    try:
        parser.feed(webpage)
        parser.close()
    except (ValueError, AssertionError) as error:
        raise ExtractorError('TopCinema source page could not be parsed', expected=True) from error
    match = _TITLE_RE.search(webpage)
    raw_title = parser.meta.get('og:title') or (
        html.unescape(re.sub(r'<[^>]*>', '', match.group(1))).strip()
        if match else next((heading for heading in parser.headings if heading), ''))
    site_name = parser.meta.get('og:site_name', '').strip()
    if site_name and raw_title.endswith(' - ' + site_name):
        raw_title = raw_title[:-(len(site_name) + 3)].rstrip()
    return ' '.join(html.unescape(raw_title).split()), parser


def _arabic_digits(text):
    return text.translate(str.maketrans('٠١٢٣٤٥٦٧٨٩۰۱۲۳۴۵۶۷۸۹', '01234567890123456789'))


_ARABIC_ORDINALS = (
    ('الأولى', 'الأول', 'الاولى', 'الاول', 'اولى', 'اول'),
    ('الثانية', 'الثاني', 'الثانيه'),
    ('الثالثة', 'الثالث', 'الثالثه'),
    ('الرابعة', 'الرابع', 'الرابعه'),
    ('الخامسة', 'الخامس', 'الخامسه'),
    ('السادسة', 'السادس', 'السادسه'),
    ('السابعة', 'السابع', 'السابعه'),
    ('الثامنة', 'الثامن', 'الثامنه'),
    ('التاسعة', 'التاسع', 'التاسعه'),
    ('العاشرة', 'العاشر', 'العاشره'),
)


def season_number(text):
    if not isinstance(text, str):
        return None
    text = _arabic_digits(text)
    match = re.search(r'(?:season|الموسم|موسم)\s*[-#:]?\s*(\d{1,3})', text, re.I)
    if match is None:
        match = re.search(r'(\d{1,3})\s*(?:موسم|season)', text, re.I)
    if match is not None:
        number = int(match.group(1))
        return number if 1 <= number <= 999 else None
    for number, forms in enumerate(_ARABIC_ORDINALS, 1):
        if any(form in text for form in forms):
            return number
    return None


def episode_number(text):
    if not isinstance(text, str):
        return None
    match = re.search(
        r'(?:episode|ep\.?|الحلقة|حلقة)\s*[-#:]?\s*(\d{1,4})',
        _arabic_digits(text), re.I)
    if match is None:
        return None
    number = int(match.group(1))
    return number if 1 <= number <= 9999 else None


def _series_name(title):
    clean_title = re.sub(r'^مسلسل\s+', '', title.strip(), flags=re.I)
    marker = re.search(r'\s+(?:الموسم|موسم|season|الحلقة|episode)\b', clean_title, re.I)
    if marker:
        clean_title = clean_title[:marker.start()]
    else:
        clean_title = re.sub(
            r'\s+(?:مترجم|مترجمة|مدبلج|مدبلجة)\s*$', '', clean_title, flags=re.I)
    return ' '.join(clean_title.split()).strip()


def _source_links(parser, page_url, validate):
    links = []
    for raw, label in parser.anchors:
        if not raw:
            continue
        try:
            target = urlsplit(urljoin(page_url, html.unescape(raw.strip())))
            if (target.scheme != 'https'
                    or (target.hostname or '').lower() not in _HOSTS
                    or target.port not in (None, 443)
                    or target.username is not None
                    or target.password is not None):
                continue
            candidate = urlunsplit((
                target.scheme, target.netloc, target.path, target.query, ''))
            validate(candidate)
            links.append((candidate, label))
        except (TypeError, ValueError, UnicodeError):
            continue
    return links


def _series_season_links(parser, page_url, series_name, validate):
    if not series_name:
        return []
    seasons = {}
    for season_url, label in _source_links(parser, page_url, validate):
        number = season_number(label)
        if number is None or not is_collection_url(season_url):
            continue
        if series_name.casefold() not in label.casefold():
            continue
        seasons.setdefault((number, season_url), _SeasonLink(season_url, label, number))
    return [seasons[key] for key in sorted(seasons)]


def _episodes_on_page(collection_page, series_name, validate):
    page_series = series_name or _series_name(collection_page.title)
    episodes = []
    for article_url, label in _source_links(
            collection_page.parser, collection_page.url, validate):
        number = episode_number(label)
        if number is None or not _is_article_candidate(article_url):
            continue
        if page_series and page_series.casefold() not in label.casefold():
            continue
        episodes.append(_EpisodeLink(
            article_url, label, collection_page.season_number, number))
    return episodes


def _playlist_episode(episode, position):
    return {
        '_type': 'url',
        'url': episode.url,
        'webpage_url': episode.url,
        'id': urlsplit(episode.url).path.rstrip('/').rsplit('/', 1)[-1][:256],
        'title': episode.title[:512] or f'Episode {episode.episode_number}',
        'ie_key': 'Generic',
        'extractor_key': 'TopCinema',
        'playlist_index': position,
        'season_number': episode.season_number,
        'episode_number': episode.episode_number,
    }


def _collection_entries(collection_pages, series_name, validate):
    episodes = {}
    warnings = []
    for collection_page in collection_pages:
        season_episodes = _episodes_on_page(collection_page, series_name, validate)
        if not season_episodes:
            warnings.append({
                'season_number': collection_page.season_number,
                'title': collection_page.title[:512] or f'Season {collection_page.season_number}',
            })
        for episode in season_episodes:
            key = (episode.season_number, episode.episode_number)
            if key not in episodes and len(episodes) >= _MAX_EPISODES:
                warnings.append({
                    'season_number': episode.season_number,
                    'title': 'The source episode count exceeds the supported limit',
                })
                break
            episodes.setdefault(key, episode)
    return [
        _playlist_episode(episodes[key], position)
        for position, key in enumerate(sorted(episodes), 1)
    ], warnings


def _embed_url(webpage, page_url):
    """Choose an unambiguous HTTPS iframe URL; the caller applies URL policy."""
    if not isinstance(webpage, str) or len(webpage) > _PAGE_LIMIT:
        return None
    parser = _IframeParser()
    try:
        parser.feed(webpage)
        parser.close()
    except (ValueError, AssertionError):
        return None
    if not parser.sources or len(parser.sources) > _MAX_IFRAMES:
        return None

    candidates = []
    for raw in parser.sources:
        try:
            joined = urljoin(page_url, raw.strip())
            parts = urlsplit(joined)
            if (
                parts.scheme != 'https'
                or not parts.hostname
                or parts.username is not None
                or parts.password is not None
                or parts.port not in (None, 443)
            ):
                continue
            candidates.append(urlunsplit((
                parts.scheme, parts.netloc, parts.path, parts.query, '')))
        except (TypeError, ValueError, UnicodeError):
            continue

    if not candidates:
        return None
    embedded = [candidate for candidate in candidates
                if 'embed' in urlsplit(candidate).path.lower()]
    if len(embedded) == 1:
        return embedded[0]
    if len(embedded) > 1:
        return None
    return candidates[0] if len(candidates) == 1 else None


def _to_base(number, radix):
    if not isinstance(number, int) or number < 0:
        raise ValueError('number')
    if not isinstance(radix, int) or not 2 <= radix <= len(_RADIX_DIGITS):
        raise ValueError('radix')
    if number == 0:
        return '0'
    digits = []
    while number:
        number, remainder = divmod(number, radix)
        digits.append(_RADIX_DIGITS[remainder])
    return ''.join(reversed(digits))


def _js_string(text, index):
    """Parse one bounded single-quoted JavaScript string without evaluating it."""
    if index >= len(text) or text[index] != "'":
        raise ValueError('string')
    index += 1
    out = []
    while index < len(text):
        char = text[index]
        if char == '\\':
            if index + 1 >= len(text):
                raise ValueError('escape')
            esc = text[index + 1]
            simple = {
                'n': '\n', 't': '\t', 'r': '\r', 'b': '\b',
                'f': '\f', 'v': '\v', '0': '\0',
            }
            if esc in simple:
                out.append(simple[esc])
                index += 2
            elif esc in "'\"\\/":
                out.append(esc)
                index += 2
            elif esc == 'x':
                if index + 3 >= len(text):
                    raise ValueError('hex escape')
                out.append(chr(int(text[index + 2:index + 4], 16)))
                index += 4
            elif esc == 'u':
                if index + 5 >= len(text):
                    raise ValueError('unicode escape')
                out.append(chr(int(text[index + 2:index + 6], 16)))
                index += 6
            elif esc in '1234567':
                end = index + 2
                while end < len(text) and end < index + 4 and text[end] in '01234567':
                    end += 1
                out.append(chr(int(text[index + 1:end], 8) & 0xff))
                index = end
            elif esc in '\r\n':
                index += 2
                if esc == '\r' and index < len(text) and text[index] == '\n':
                    index += 1
            else:
                out.append(esc)
                index += 2
        elif char == "'":
            return ''.join(out), index + 1
        else:
            out.append(char)
            index += 1
    raise ValueError('unterminated string')


def _skip_pack_separators(text, index):
    while index < len(text) and text[index] in ' \t\r\n,':
        index += 1
    return index


def _bounded_replace_tokens(packed, word_map, output_limit=_MAX_UNPACKED):
    """Do one linear token pass and stop before expanded output exceeds its cap."""
    output = io.StringIO()
    cursor = 0
    output_length = 0
    for match in _WORD_TOKEN_RE.finditer(packed):
        replacement = word_map.get(match.group(0))
        if replacement is None:
            continue
        prefix = packed[cursor:match.start()]
        added = len(prefix) + len(replacement)
        if output_length + added > output_limit:
            return None
        output.write(prefix)
        output.write(replacement)
        output_length += added
        cursor = match.end()
    tail = packed[cursor:]
    if output_length + len(tail) > output_limit:
        return None
    output.write(tail)
    return output.getvalue()


def _unpack(page):
    """Expand a Dean-Edwards packed string with bounded work; never run its JS."""
    if not isinstance(page, str) or len(page) > _PAGE_LIMIT:
        return None
    marker = page.find(_PACK_MARKER)
    if marker < 0:
        return None
    try:
        index = _skip_pack_separators(page, marker + len(_PACK_MARKER))
        packed, index = _js_string(page, index)
        index = _skip_pack_separators(page, index)
        match = re.match(r'\d{1,4}(?!\d)', page[index:])
        if not match:
            return None
        radix = int(match.group(0))
        index = _skip_pack_separators(page, index + match.end())
        match = re.match(r'\d{1,5}(?!\d)', page[index:])
        if not match:
            return None
        count = int(match.group(0))
        index = _skip_pack_separators(page, index + match.end())
        dictionary, _ = _js_string(page, index)
        if (
            not 2 <= radix <= len(_RADIX_DIGITS)
            or count > _MAX_WORDS
            or len(packed) > _MAX_UNPACKED
            or len(dictionary) > _MAX_UNPACKED
        ):
            return None
        words = dictionary.split('|')
        if not 1 <= len(words) <= _MAX_WORDS:
            return None
        word_map = {}
        expanded_word_bytes = 0
        for word_index, word in enumerate(words[:min(count, len(words))]):
            if not word:
                continue
            expanded = _bounded_replace_tokens(word, word_map)
            if expanded is None:
                return None
            expanded_word_bytes += len(expanded)
            if expanded_word_bytes > _MAX_EXPANDED_WORDS:
                return None
            word_map[_to_base(word_index, radix)] = expanded
        return _bounded_replace_tokens(packed, word_map)
    except (ValueError, OverflowError, TypeError, RecursionError, re.error):
        return None


def _player_config(player_page):
    """Return the HLS URL and duration found in a bounded player page."""
    if not isinstance(player_page, str) or len(player_page) > _PAGE_LIMIT:
        return None, None
    unpacked = _unpack(player_page)
    code = unpacked or player_page
    source = _MEDIA_RE.search(code)
    if source is None and unpacked is not None:
        source = _MEDIA_RE.search(player_page)
    if source is None:
        return None, None
    media = source.group(1).replace('\\/', '/').replace('&amp;', '&')
    duration_match = _DURATION_RE.search(code)
    duration = None
    if duration_match:
        try:
            value = float(duration_match.group(2))
            if math.isfinite(value) and 0 < value <= 24 * 3600 * 10:
                duration = value
        except (ValueError, OverflowError):
            pass
    return media, duration


def _response_url(response, fallback):
    candidate = getattr(response, 'url', None)
    if not candidate:
        geturl = getattr(response, 'geturl', None)
        candidate = geturl() if callable(geturl) else None
    return candidate if isinstance(candidate, str) and candidate else fallback


def _set_referer(headers, referer):
    result = dict(headers) if isinstance(headers, dict) else {}
    for key in tuple(result):
        if isinstance(key, str) and key.lower() == 'referer':
            del result[key]
    result['Referer'] = referer
    return result


def _apply_referer(fmt, referer):
    fmt['http_headers'] = _set_referer(fmt.get('http_headers'), referer)
    fragments = fmt.get('fragments')
    if isinstance(fragments, list):
        for fragment in fragments:
            if isinstance(fragment, dict) and fragment.get('http_headers') is not None:
                fragment['http_headers'] = _set_referer(
                    fragment.get('http_headers'), referer)


def _correct_verified_videotube_fps(format_info):
    """Correct the verified false FRAME-RATE on VideoTube's 1920x800 entry."""
    if not isinstance(format_info, dict):
        return format_info
    try:
        width = int(format_info.get('width') or 0)
        height = int(format_info.get('height') or 0)
        fps = float(format_info.get('fps'))
    except (TypeError, ValueError, OverflowError):
        return format_info
    if (width, height) == (1920, 800) and math.isclose(
            fps, 95.948, rel_tol=0.0, abs_tol=0.001):
        # Bounded bitstream inspection verified this rendition is 24 fps.
        # Do not infer or rewrite frame rates for other variants.
        format_info['fps'] = 24
    return format_info


def configure(engine, url, validate):
    """Install bounded series, episode-page, and watch-page support."""
    if not _supported_site_url(url):
        return

    class MediaFlowCinemaIE(GenericIE):
        IE_NAME = 'topcinema'

        @classmethod
        def ie_key(cls):
            # Generic is the final fallback entry; replacing its key preserves
            # registry order and lets every other Generic URL keep stock behavior.
            return GenericIE.ie_key()

        def _download_source_page(self, source_url, video_id, note):
            validate(source_url)
            webpage, response = self._download_webpage_handle(
                source_url, video_id, note=note)
            page_url = _response_url(response, source_url)
            validate(page_url)
            if not _supported_site_url(page_url):
                raise ExtractorError('TopCinema redirected outside its supported hosts', expected=True)
            return page_url, webpage

        def _collection_pages(self, page_url, title, parser):
            declared_season = season_number(title)
            root_name = _series_name(title)
            if declared_season is not None:
                return 'topcinema_season', declared_season, [
                    _CollectionPage(page_url, title, declared_season, parser)], [], root_name
            season_links = _series_season_links(
                parser, page_url, root_name, validate)
            if len(season_links) > _MAX_SEASON_PAGES:
                raise ExtractorError(
                    'TopCinema season count exceeds its metadata limit', expected=True)
            pages, warnings = self._download_season_pages(season_links)
            if not season_links:
                warnings.append({
                    'season_number': None,
                    'title': 'No verifiable season links were exposed',
                })
            return 'topcinema_series', None, pages, warnings, root_name

        def _extract_collection(self, source_url):
            video_id = self._generic_id(source_url)
            page_url, webpage = self._download_source_page(
                source_url, video_id, 'Downloading TopCinema listing')
            if not is_collection_url(page_url):
                raise ExtractorError('TopCinema listing route changed', expected=True)
            title, parser = _source_page(webpage)
            collection_type, declared_season, season_pages, warnings, root_name = (
                self._collection_pages(page_url, title, parser))
            entries, episode_warnings = _collection_entries(
                season_pages, root_name, validate)
            warnings.extend(episode_warnings)
            return {
                '_type': 'playlist',
                'id': video_id,
                'title': title or root_name or 'TopCinema',
                'webpage_url': page_url,
                'playlist_count': len(entries),
                'entries': entries,
                'mediaflow_collection_type': collection_type,
                'mediaflow_season_number': declared_season,
                'mediaflow_unverified_seasons': warnings[:_MAX_SEASON_PAGES],
                'extractor_key': 'TopCinema',
            }

        def _download_season_pages(self, season_links):
            collection_pages = []
            warnings = []
            for link in season_links:
                try:
                    season_url, webpage = self._download_source_page(
                        link.url, self._generic_id(link.url), 'Downloading TopCinema season')
                    if not is_collection_url(season_url):
                        raise ExtractorError('TopCinema season route changed', expected=True)
                    title, parser = _source_page(webpage)
                    number = season_number(title) or link.season_number
                    collection_pages.append(_CollectionPage(
                        season_url, title, number, parser))
                except ExtractorError:
                    warnings.append({
                        'season_number': link.season_number,
                        'title': link.title[:512] or f'Season {link.season_number}',
                    })
            return collection_pages, warnings

        def _extract_episode_article(self, source_url):
            video_id = self._generic_id(source_url)
            page_url, webpage = self._download_source_page(
                source_url, video_id, 'Checking TopCinema episode page')
            title, parser = _source_page(webpage)
            if (episode_number(title) is None
                    or not re.search(r'(?:مسلسل|series|season|episode|الحلقة|حلقة)', title, re.I)):
                return super()._real_extract(source_url)
            playback = {
                target for target, label in _source_links(parser, page_url, validate)
                if matches(target)
            }
            if len(playback) != 1:
                return super()._real_extract(source_url)
            return self._extract_watch(next(iter(playback)))

        def _extract_watch(self, source_url):
            video_id = self._generic_id(source_url)
            webpage, watch_response = self._download_webpage_handle(
                source_url, video_id, note='Downloading watch page')
            page_url = _response_url(watch_response, source_url)
            validate(page_url)

            embed_url = _embed_url(webpage, page_url)
            if embed_url is None:
                return super()._real_extract(source_url)
            validate(embed_url)

            player_page, player_response = self._download_webpage_handle(
                embed_url, video_id, note='Downloading player page',
                headers={'Referer': page_url})
            player_url = _response_url(player_response, embed_url)
            validate(player_url)
            media, duration = _player_config(player_page)
            if media is None:
                return super()._real_extract(source_url)
            media = urljoin(player_url, media)
            validate(media)

            formats = self._extract_m3u8_formats(
                media, video_id, 'mp4', m3u8_id='hls',
                headers={'Referer': player_url})
            if not formats:
                raise ExtractorError(
                    'No supported HLS formats were found', expected=True)
            for fmt in formats:
                _correct_verified_videotube_fps(fmt)
                _apply_referer(fmt, player_url)

            identity = _EMBED_ID_RE.search(urlsplit(player_url).path)
            title = self._og_search_title(webpage, default=None)
            if not title:
                found = _TITLE_RE.search(webpage)
                title = found.group(1).strip() if found else None
            if title:
                site_name = self._og_search_property(
                    'site_name', webpage, fatal=False)
                if site_name and title.endswith(' - ' + site_name):
                    title = title[:-(len(site_name) + 3)].rstrip() or title
            if not title:
                title = identity.group(1) if identity else video_id

            thumbnail = None
            raw_thumbnail = self._og_search_thumbnail(webpage)
            if raw_thumbnail:
                candidate = urljoin(page_url, raw_thumbnail)
                try:
                    validate(candidate)
                    thumbnail = candidate
                except (TypeError, ValueError):
                    pass

            return {
                'id': identity.group(1) if identity else video_id,
                'title': title,
                'formats': formats,
                'thumbnail': thumbnail,
                'duration': duration,
                'webpage_url': page_url,
                'extractor_key': 'TopCinema',
            }

        def _real_extract(self, source_url):
            if is_collection_url(source_url):
                return self._extract_collection(source_url)
            if matches(source_url):
                return self._extract_watch(source_url)
            if _is_article_candidate(source_url):
                return self._extract_episode_article(source_url)
            return super()._real_extract(source_url)

    engine.add_info_extractor(MediaFlowCinemaIE(engine))
