"""Opt-in format inventory: dimensions/codecs only, never stream URLs or credentials."""
import json
import re


def inventory(info, normalized):
    raw = info.get('formats') or [info]
    kept = {str(fmt.get('format_id')) for fmt in normalized}
    rows = []
    for index, fmt in enumerate(raw[:1000]):
        reason = None
        if not fmt.get('url'):
            reason = 'missing_url'
        elif fmt.get('has_drm'):
            reason = 'drm'
        elif fmt.get('protocol') == 'mhtml':
            reason = 'storyboard_not_media'
        elif fmt.get('is_live'):
            reason = 'live'
        elif fmt.get('request_data') is not None:
            reason = 'request_body_not_supported'
        elif str(fmt.get('format_id')) not in kept:
            fragments = fmt.get('fragments')
            if isinstance(fragments, list) and len(fragments) > 20000:
                reason = 'fragment_limit'
            elif callable(fragments):
                reason = 'callable_fragments'
            else:
                reason = 'fragment_or_serialization_boundary'
        row = {'index': index, 'normalized': reason is None, 'exclusion': reason}
        for key in ('format_id', 'ext', 'protocol', 'vcodec', 'acodec', 'dynamic_range'):
            # Metadata is untrusted. Never retain a URL passed as an alleged ID/codec.
            value = fmt.get(key)
            if isinstance(value, str) and re.fullmatch(r'[A-Za-z0-9_.:+ -]{1,128}', value):
                row[key] = value
        for key in ('width', 'height', 'fps'):
            value = fmt.get(key)
            if isinstance(value, (int, float)) and 0 < value <= 32768:
                row[key] = value
        rows.append(row)
    return {'rawCount': len(raw), 'normalizedCount': len(normalized),
            'truncated': len(raw) > 1000, 'rawFormats': rows}


def warning_code(message):
    text = str(message).lower()
    for pattern, code in (('sabr', 'sabr_transport'), ('po token', 'po_token_required'),
                          ('signature', 'signature_challenge'), ('n challenge', 'n_challenge'),
                          ('javascript', 'javascript_runtime'), ('challenge solver', 'ejs_solver'),
                          ('formats have been skipped', 'upstream_formats_skipped')):
        if pattern in text:
            return code
    return 'other_upstream_warning'
