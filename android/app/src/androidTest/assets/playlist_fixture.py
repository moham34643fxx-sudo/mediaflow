"""Instrumentation-only extractor, never packaged in the application APK."""
from yt_dlp.extractor.common import InfoExtractor
from yt_dlp.utils import ExtractorError
import zextractor
import json


class MediaFlowFixtureIE(InfoExtractor):
    _VALID_URL = r'https://mediaflow\.fixture\.invalid/(?P<kind>playlist|video)/(?P<id>[^/?#]+)'
    def _real_extract(self, url):
        match = self._match_valid_url(url)
        identity = match.group('id')
        if match.group('kind') == 'playlist':
            count = 5 if identity in ('five', 'known-five') else 11 if identity == 'partial' else 10
            def entries():
                for i in range(1, count + 1):
                    if identity == 'known-five':
                        # Already-described single representations: no secondary
                        # extraction/network request is needed for the size hint.
                        yield self._real_extract(f'https://mediaflow.fixture.invalid/video/known{i}')
                        continue
                    key = 'missing' if i == 11 else str(i)
                    yield self.url_result(f'https://mediaflow.fixture.invalid/video/{key}', ie=MediaFlowFixtureIE,
                                          video_id=key, video_title=f'MediaFlow controlled item {i}')
            return self.playlist_result(entries(), identity, f'MediaFlow fixture {identity}')
        if identity == 'missing':
            raise ExtractorError('Video not found', expected=True)
        size = mediaflow_fixture_bytes + (8 + 1000 * int(identity[5:]) if identity.startswith('known') else 0)
        fmt = {'format_id': 'fixture-muxed', 'url': f'https://mediaflow.fixture.invalid/media/{identity}.mp4',
               'ext': 'mp4', 'protocol': 'https', 'width':128, 'height':96, 'fps':8,
               'vcodec':'av1', 'acodec':'aac', 'filesize': size}
        return {'id':identity,'title':f'MediaFlow controlled item {identity}', 'duration':2.0, 'formats':[fmt],
                'webpage_url': f'https://mediaflow.fixture.invalid/video/{identity}'}


mediaflow_original_registry = zextractor.AnalysisYoutubeDL.add_default_info_extractors
def mediaflow_fixture_registry(self):
    self.add_info_extractor(MediaFlowFixtureIE())
    mediaflow_original_registry(self)
zextractor.AnalysisYoutubeDL.add_default_info_extractors = mediaflow_fixture_registry

# The fixture has no authentication/session requirements. Strip yt-dlp's optional
# default transport headers so the isolated OkHttp response adapter owns the bytes;
# extraction/format normalization above still executes the actual installed API.
mediaflow_original_analyze = zextractor.analyze
def mediaflow_fixture_analyze(url, *args, **kwargs):
    result = mediaflow_original_analyze(url, *args, **kwargs)
    if url.startswith('https://mediaflow.fixture.invalid/'):
        data = json.loads(result)
        for fmt in data.get('formats', []):
            fmt['http_headers'] = {}
            fmt.pop('cookies', None)
            fmt.pop('downloader_options', None)
        return json.dumps(data)
    return result
zextractor.analyze = mediaflow_fixture_analyze
