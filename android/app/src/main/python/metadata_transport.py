"""Adapt bounded Android HTTP bytes to urllib; parsing/cookies/redirects stay upstream."""
import email.message
import io
import json
import urllib.error
import urllib.response
from java import jclass


class MetadataTransportError(OSError):
    def __init__(self, code):
        super().__init__(code)
        self.transport_code = code


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
        raise urllib.error.URLError(MetadataTransportError(code)) from error
    message = email.message.Message()
    for name, value in json.loads(str(result.getHeadersJson())):
        message[name] = value
    response = urllib.response.addinfourl(io.BytesIO(bytes(result.getBody())), message,
                                         request.full_url, int(result.getStatus()))
    response.msg = str(result.getReason())
    return response
