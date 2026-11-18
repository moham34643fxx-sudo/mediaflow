"""Reuse immutable extractor registration, never downloader/cookie/transport instances.

yt-dlp 2026.8.19 rebuilds its default registry with orderedSet_from_options on
every YoutubeDL construction. The enabled default set is fixed in this bundled
process. Preserve its exact upstream ordering and all supported extractors.
"""
import threading
from yt_dlp import YoutubeDL

_registry = None
_registry_lock = threading.Lock()


class AnalysisYoutubeDL(YoutubeDL):
    def add_default_info_extractors(self):
        global _registry
        if self.params.get('allowed_extractors') not in (None, ['default']):
            return super().add_default_info_extractors()
        with _registry_lock:
            if _registry is None:
                super().add_default_info_extractors()
                _registry = tuple((entry if isinstance(entry, type) else type(entry),
                                   not isinstance(entry, type)) for entry in self._ies.values())
                return
            registry = _registry
        for cls, instantiate in registry:
            self.add_info_extractor(cls() if instantiate else cls)
