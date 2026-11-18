"""Bound the single-media boundary without merging different videos' formats."""
def first_media(info):
    for _ in range(4):
        if not isinstance(info, dict):
            raise ValueError('Requested format is not available')
        if 'entries' not in info:
            return info
        # analyze() limits upstream processing to one entry. playlist() remains
        # the explicit bounded batch path. Never combine qualities from siblings.
        info = next((entry for entry in info['entries'] or [] if entry), None)
    raise ValueError('Requested format is not available')
