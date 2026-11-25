"""Adapt bounded Android HTTP bytes to urllib; parsing/cookies/redirects stay upstream."""
import email.message
import io
import json
import re
import urllib.error
import urllib.response
from java import jclass


class MetadataTransportError(OSError):
    def __init__(self, code, safe_exception_classes=()):
        super().__init__(code)
        self.transport_code = code
        self.safe_exception_classes = tuple(safe_exception_classes)


def open_response(bridge, request, transport):
    headers = dict(request.unredirected_hdrs)
    headers.update({name: value for name, value in request.headers.items() if name not in headers})
    try:
        config = dict(transport, url=request.full_url, method=request.get_method(), headers=list(headers.items()))
        result = bridge.execute(json.dumps(config), request.data)
    except jclass('com.zdownloader.app.engine.MetadataTransportException') as error:
        code = str(error.getCode())
        if code == 'METADATA_LIMIT':
            raise ValueError('METADATA_LIMIT') from error
        try:
            classes = json.loads(str(error.getSafeCauseClassesJson()))
        except (AttributeError, TypeError, ValueError):
            classes = []
        if not isinstance(classes, list):
            classes = []
        safe_classes = [name for name in classes[:12]
                        if isinstance(name, str) and re.fullmatch(r'[A-Za-z0-9_.$]{1,120}', name)]
        raise urllib.error.URLError(MetadataTransportError(code, safe_classes)) from error
    message = email.message.Message()
    for name, value in json.loads(str(result.getHeadersJson())):
        message[name] = value
    response = urllib.response.addinfourl(io.BytesIO(bytes(result.getBody())), message,
                                         request.full_url, int(result.getStatus()))
    response.msg = str(result.getReason())
    return response
