"""Reject known label-derived dimensions; retain actual rendition metadata.

The bundled Facebook extractor's legacy *_hd_src path fabricates height=720
from the word HD. It is not evidence that the underlying media is 720p.
"""
import re
from urllib.parse import urlsplit


def reject_label_dimensions(entry, info, source_url):
    extractor = str(info.get('extractor_key') or info.get('extractor') or '').lower()
    host = (urlsplit(source_url).hostname or '').lower()
    facebook = extractor.startswith('facebook') or host == 'facebook.com' or host.endswith('.facebook.com')
    identifier = str(entry.get('format_id') or '').lower()
    legacy = re.fullmatch(r'.+_(?:sd|hd)_src(?:_no_ratelimit)?', identifier)
    unmeasured_label = identifier in ('sd', 'hd') and not entry.get('width')
    if facebook and (legacy or unmeasured_label):
        # Preserve raw fields as track-presence hints for existing audio/video
        # classification. The boundary marks them ineligible as measured geometry.
        entry['mediaflow_dimensions'] = 'unverified_label'
    tiktok = extractor.startswith('tiktok') or host in ('tiktok.com', 'www.tiktok.com', 'vm.tiktok.com', 'vt.tiktok.com')
    if tiktok and re.fullmatch(r'[^_]+_[0-9]+p_[0-9]+(?:-[0-9]+)?', identifier):
        # TikTok's web extractor derives bitrate-rendition geometry from UrlKey
        # labels and the post's aspect ratio, even rewriting 540p to 576p. A CDN
        # rendition can actually be 540x960. Retain IDs/codecs, measure real bytes
        # before allowing numbered caps or validating claimed pixel geometry.
        entry['mediaflow_dimensions'] = 'unverified_label'
    return entry
