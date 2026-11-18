"""Normalize actual extracted representations, never thumbnails or invented qualities."""

IMAGE_EXTENSIONS = frozenset(('jpg', 'jpeg', 'png', 'webp', 'gif', 'avif', 'heic', 'heif', 'bmp', 'tif', 'tiff', 'svg', 'ico'))


def normalize_source(entry, probe_image):
    extension = str(entry.get('ext') or '').lower()
    if extension not in IMAGE_EXTENSIONS and entry.get('vcodec') != 'images':
        return entry
    if entry.get('protocol') == 'mhtml' or entry.get('format_note') == 'storyboard':
        return None
    # Even extractor-provided original-image markers are metadata, not proof of bytes.
    geometry = probe_image(entry)
    if geometry is None:
        return None
    extension, width, height = geometry
    if extension not in ('jpg', 'jpeg', 'png', 'webp'):
        return None
    return {**entry, 'ext': extension, 'width': width, 'height': height,
            'vcodec': 'none', 'acodec': 'none', 'mediaflow_image': 'original',
            'fps': None, 'tbr': None, 'vbr': None, 'abr': None,
            'mediaflow_source_quality_label': None}
