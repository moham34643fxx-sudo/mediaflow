"""Bundled EJS with an embedded JNI QuickJS provider, no subprocesses."""
from java import jclass
from yt_dlp.extractor.youtube.jsc._builtin.ejs import EJSBaseJCP
from yt_dlp.extractor.youtube.jsc.provider import register_provider, register_preference
from yt_dlp.extractor.youtube.pot._provider import BuiltinIEContentProvider
import threading
import json
import time
import hashlib
from player_transform_cache import PlayerTransformCache
from yt_dlp.utils._jsruntime import JsRuntime, JsRuntimeInfo


class EmbeddedRuntime(JsRuntime):
    def _info(self):
        # The legacy registry alias identifies our adapter, not an external executable.
        # Discovering it must not create QuickJS before we have chosen an engine.
        return JsRuntimeInfo(name='quickjs', path='embedded:v8-or-quickjs', version='MediaFlow adapter 1',
                             version_tuple=(1,), supported=True)


def configure(engine):
    # The extractor uses this registry to choose clients requiring JS challenges.
    # An actual in-process runtime replaces executable discovery.
    engine._js_runtimes['quickjs'] = EmbeddedRuntime()

_state = threading.local()
_player_transforms = PlayerTransformCache()

# Instrument exported operations, not the pinned/hash-verified solver sources or player semantics.
_PHASE_HOOKS = r'''
const __mfPhases={library_eval_ms:Date.now()-__mfLibraryStart,parse_ms:0,parse_calls:0,
    full_player_parse_ms:0,generate_ms:0,generate_calls:0};
for(const [object,key,label] of [[meriyah,'parse','parse'],[astring,'generate','generate']]) {
    const original=object[key];
    object[key]=function(...args) {
        const at=Date.now();__mfPhases[label+'_calls']++;
        try{return original.apply(this,args);}
        finally{const elapsed=Date.now()-at;__mfPhases[label+'_ms']+=elapsed;
            if(label==='parse'&&typeof args[0]==='string'&&args[0].length>100000)
                __mfPhases.full_player_parse_ms+=elapsed;}
    };
}
'''


def reset_stats():
    _state.runs = 0
    _state.solved = 0
    _state.failures = 0
    _state.init_ms = 0
    _state.execution_ms = 0
    _state.transform_cache_hits = 0
    _state.transform_cache_misses = 0
    _state.transform_cache_fallbacks = 0
    _state.asset_load_and_hash_ms = 0
    _state.stdin_build_ms = 0
    _state.bridge_runs = []
    _state.solver_phases = []


def stats():
    return {'runs': getattr(_state, 'runs', 0), 'solved': getattr(_state, 'solved', 0),
            'failures': getattr(_state, 'failures', 0),
            'transform_cache_hits': getattr(_state, 'transform_cache_hits', 0),
            'transform_cache_misses': getattr(_state, 'transform_cache_misses', 0),
            'transform_cache_fallbacks': getattr(_state, 'transform_cache_fallbacks', 0),
            'init_ms': round(getattr(_state, 'init_ms', 0)), 'execution_ms': round(getattr(_state, 'execution_ms', 0)),
            'asset_load_and_hash_ms': round(getattr(_state, 'asset_load_and_hash_ms', 0)),
            'stdin_build_ms': round(getattr(_state, 'stdin_build_ms', 0)),
            'bridge_runs': getattr(_state, 'bridge_runs', []), 'solver_phases': getattr(_state, 'solver_phases', [])}


@register_provider
class AndroidQuickJSJCP(EJSBaseJCP, BuiltinIEContentProvider):
    PROVIDER_NAME = 'android_quickjs'
    JS_RUNTIME_NAME = 'quickjs'

    def is_available(self):
        return self._available

    def _construct_stdin(self, player, preprocessed, requests):
        if preprocessed:
            _state.pending_transform = None
            return self._script(player, True, requests)
        started = time.monotonic()
        key = (hashlib.sha256(player.encode('utf-8')).digest(), self._lib_script.hash, self._core_script.hash)
        _state.asset_load_and_hash_ms = getattr(_state, 'asset_load_and_hash_ms', 0) + (time.monotonic()-started)*1000
        transformed = _player_transforms.get(key)
        hit = transformed is not None
        counter = 'transform_cache_hits' if hit else 'transform_cache_misses'
        setattr(_state, counter, getattr(_state, counter, 0) + 1)
        # Original source/requests live only until this invocation completes, never in the cache.
        stdin = self._script(transformed if hit else player, hit, requests)
        _state.pending_transform = (key, hit, lambda: self._script(player, False, requests))
        return stdin

    def _script(self, player, preprocessed, requests):
        started = time.monotonic()
        # Same EJS wire protocol as the pinned EJSBaseJCP; request data is JSON-escaped, not code.
        data = {'type': 'preprocessed', 'preprocessed_player': player} if preprocessed else {
            'type': 'player', 'player': player, 'output_preprocessed': True}
        data['requests'] = [{'type': request.type.value, 'challenges': request.input.challenges} for request in requests]
        script = ('const __mfLibraryStart=Date.now();\n' + self._lib_script.code
                  + '\nObject.assign(globalThis, lib);\n' + _PHASE_HOOKS + '\n' + self._core_script.code
                  + '\nconst __mfSolveStart=Date.now();const __mfResult=jsc(' + json.dumps(data) + ');'
                  + '__mfPhases.solver_ms=Date.now()-__mfSolveStart;__mfResult.mediaflow_phases=__mfPhases;'
                  + 'console.log(JSON.stringify(__mfResult));')
        _state.stdin_build_ms = getattr(_state, 'stdin_build_ms', 0) + (time.monotonic()-started)*1000
        return script

    def _run_js_runtime(self, stdin):
        pending = getattr(_state, 'pending_transform', None)
        _state.pending_transform = None
        if pending is None:
            return _evaluate(stdin)[0]
        key, hit, original_stdin = pending
        try:
            result, parsed = _evaluate(stdin)
        except (jclass('com.dokar.quickjs.QuickJsException'), json.JSONDecodeError):
            if not hit:
                raise
        else:
            if not hit or _successful(parsed):
                _remember_transform(key, parsed)
                return result
        # Only an unusable cached transformation gets one full-source retry.
        # Cancellation and transport exceptions are not caught or retried here.
        _player_transforms.discard(key)
        _state.transform_cache_fallbacks = getattr(_state, 'transform_cache_fallbacks', 0) + 1
        result, parsed = _evaluate(original_stdin())
        _remember_transform(key, parsed)
        return result


def _successful(parsed):
    responses = parsed.get('responses')
    return parsed.get('type') == 'result' and isinstance(responses, list) and bool(responses) and all(
        isinstance(response, dict) and response.get('type') == 'result' for response in responses)


def _remember_transform(key, parsed):
    transformed = parsed.get('preprocessed_player')
    if _successful(parsed) and isinstance(transformed, str) and transformed:
        _player_transforms.put(key, transformed)


def _evaluate(stdin):
    _state.runs = getattr(_state, 'runs', 0) + 1
    started = time.monotonic()
    import sys
    boundary = sys.modules.get('zextractor')
    event = getattr(boundary._current, 'event', None) if boundary else None
    request_id = getattr(event, 'request_id', '')
    if boundary: boundary.checkpoint(request_id, 'quickjs_start')
    try:
        envelope = json.loads(str(jclass('com.zdownloader.app.engine.JsBridge').runEjs(stdin)))
        result = envelope['output']
        if not hasattr(_state, 'bridge_runs'):
            _state.bridge_runs = []
        _state.bridge_runs.append(envelope['bridge'])
        retried = envelope['bridge'].get('attempts', 1) - 1
        _state.runs += retried
        _state.failures = getattr(_state, 'failures', 0) + retried
    except Exception:
        _state.failures = getattr(_state, 'failures', 0) + 1
        raise
    finally:
        _state.execution_ms = getattr(_state, 'execution_ms', 0) + (time.monotonic()-started)*1000
        if boundary: boundary.checkpoint(request_id, 'quickjs_finished')
    parsed = json.loads(result)
    if isinstance(parsed.get('mediaflow_phases'), dict):
        if not hasattr(_state, 'solver_phases'):
            _state.solver_phases = []
        _state.solver_phases.append(parsed['mediaflow_phases'])
    _state.solved = getattr(_state, 'solved', 0) + sum(1 for response in parsed.get('responses', []) if response.get('type') != 'error')
    return result, parsed


@register_preference(AndroidQuickJSJCP)
def preference(provider, requests):
    return 900
