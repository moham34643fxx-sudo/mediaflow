"""Bound native transfer responses across downloader instances, not just per track."""
import http.client
import socket
import threading

from yt_dlp.networking.common import Response
from yt_dlp.networking.exceptions import HTTPError


_responses = threading.BoundedSemaphore(32)


def _urllib_socket(response):
    # The bundled urllib adapter wraps HTTPResponse -> BufferedReader -> SocketIO.
    # Capture the socket before EOF clears HTTPResponse.fp; do not probe other backends.
    raw_response = response.fp
    if not isinstance(raw_response, http.client.HTTPResponse):
        return None
    raw_socket = getattr(getattr(raw_response.fp, 'raw', None), '_sock', None)
    return raw_socket if isinstance(raw_socket, socket.socket) else None


class _OwnedResponse(Response):
    def __init__(self, response, owner):
        # IOBase may finalize a partially constructed instance after an error.
        self._release_lock = threading.Lock()
        self._released = True
        super().__init__(response, response.url, response.headers, response.status,
                         response.reason, response.extensions)
        self.headers = response.headers
        self.owner = owner
        self._socket = _urllib_socket(response)
        self._eof = False
        length = response.headers.get('Content-Length', '')
        self._remaining = (int(length) if len(length) <= 20 and length.isascii()
                           and length.isdecimal() and not response.headers.get('Content-Encoding') else None)
        self._released = False

    def read(self, amt=None):
        if self._eof:
            return b''
        try:
            self.owner.check()
            payload = self.fp.read(amt)
            self.owner.check()
            if amt != 0:
                if self._remaining is not None:
                    self._remaining -= len(payload)
                if not payload or amt is None or amt < 0 or self._remaining == 0:
                    self._eof = True
                    self.close()
            return payload
        except BaseException as error:
            try:
                self.close()
            except Exception as cleanup:
                error.add_note('Native response cleanup failed: ' + type(cleanup).__name__)
            raise

    def interrupt(self):
        with self._release_lock:
            if self._released or self._socket is None:
                return
            transport_socket = self._socket
        try:
            # close() alone can wait for the BufferedReader lock held by read().
            # Shutdown wakes that read; its owning worker still closes/releases it.
            transport_socket.shutdown(socket.SHUT_RDWR)
        except OSError:
            # Completion may close the socket between the snapshot and shutdown.
            pass

    def close(self):
        with self._release_lock:
            if self._released:
                return
            self._released = True
        try:
            super().close()
        finally:
            self.owner.release(self)


class NativeResponseBudget:
    """One engine owns its open bodies; the process shares their connection ceiling."""
    def __init__(self, engine, check, semaphore=None):
        self.engine = engine
        self.check = check
        self.semaphore = semaphore if semaphore is not None else _responses
        self._open = engine.urlopen
        self._owned = set()
        self._lock = threading.Lock()

    def __enter__(self):
        self.engine.urlopen = self.open
        return self

    def open(self, request):
        self.check()
        while not self.semaphore.acquire(timeout=0.1):
            self.check()
        response = None
        try:
            self.check()
            response = self._open(request)
            owned = _OwnedResponse(response, self)
            with self._lock:
                self._owned.add(owned)
            return owned
        except BaseException as error:
            # Native retry/status handling uses HTTPError's status and headers,
            # not its body. Do not leave failed responses holding connection slots.
            failed = error.response if isinstance(error, HTTPError) else response
            try:
                if failed is not None:
                    failed.close()
            except Exception as cleanup:
                error.add_note('Native response cleanup failed: ' + type(cleanup).__name__)
            finally:
                self.semaphore.release()
            raise

    def release(self, response):
        with self._lock:
            self._owned.discard(response)
        self.semaphore.release()

    def cancel(self):
        with self._lock:
            responses = tuple(self._owned)
        for response in responses:
            response.interrupt()

    def __exit__(self, error_type, error, traceback):
        self.engine.urlopen = self._open
        with self._lock:
            responses = tuple(self._owned)
        failure = None
        for response in responses:
            try:
                response.close()
            except Exception as cleanup:
                if failure is None:
                    failure = cleanup
        if failure is not None:
            if error is None:
                raise failure
            error.add_note('Native response cleanup failed: ' + type(failure).__name__)
