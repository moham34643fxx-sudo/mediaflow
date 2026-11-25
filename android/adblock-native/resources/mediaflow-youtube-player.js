// MediaFlow's local resource. The baseline compiler renders it without arguments.
// The Android host supplies the permission check in the surrounding closure.
(() => {
  'use strict';
  if (window !== window.top) return;
  const origin = location.origin;
  if (!['https://youtube.com', 'https://www.youtube.com', 'https://m.youtube.com'].includes(origin)) return;

  const installed = Symbol.for('mediaflow.youtube-player-scriptlet.v1');
  const descriptor = Object.getOwnPropertyDescriptor;
  const define = Object.defineProperty;
  const prototype = Object.getPrototypeOf;
  const objectPrototype = Object.prototype;
  const isArray = Array.isArray;
  const apply = Reflect.apply;
  const remove = Reflect.deleteProperty;
  const parse = JSON.parse;
  const stringify = JSON.stringify;
  const then = Promise.prototype.then;
  const Url = window.URL;
  const Request = window.Request;
  const urlHref = descriptor(Url.prototype, 'href').get;
  const requestUrl = descriptor(Request.prototype, 'url').get;
  const arrayPrototype = Array.prototype;
  const MAX_TEXT_CHARS = 1024 * 1024;
  const MAX_ENVELOPE_ITEMS = 16;
  const adFields = ['playerAds', 'adPlacements', 'adSlots'];
  const playerPath = '/youtubei/v1/player';
  const watchPath = '/youtubei/v1/get_watch';
  const paths = new Set([
    playerPath, watchPath, '/youtubei/v1/reel/reel_item_watch', '/youtubei/v1/reel/reel_watch_sequence'
  ]);

  function allowed() {
    try {
      return typeof mfAllowsYoutubePlayerScriptlet === 'function' && mfAllowsYoutubePlayerScriptlet() === true;
    } catch (_) {
      return false;
    }
  }

  if (descriptor(window, installed) || !allowed()) return;
  const counts = { bootstrap: 0, fetch: 0, xhr: 0 };
  define(window, installed, { value: () => ({ ...counts }) });

  function observe(source, changes) {
    counts[source] = Math.min(255, counts[source] + changes);
  }

  function isRecord(value) {
    if (!value || typeof value !== 'object') return false;
    const proto = prototype(value);
    return proto === objectPrototype || proto === null;
  }

  function field(record, name) {
    return isRecord(record) ? descriptor(record, name)?.value : undefined;
  }

  function removableAdFields(player) {
    if (field(field(player, 'playabilityStatus'), 'status') !== 'OK') return [];
    const videoId = field(field(player, 'videoDetails'), 'videoId');
    if (typeof videoId !== 'string' || !/^[\w-]{11}$/.test(videoId) ||
        !isRecord(field(player, 'streamingData'))) return [];
    return adFields.filter(name => {
      const property = descriptor(player, name);
      return property?.configurable && isArray(property.value) && property.value.length > 0;
    });
  }

  function playerObjects(body, path) {
    const players = [body];
    if (!path) return players;
    players.push(field(body, 'playerResponse'));
    if ((path === watchPath || path === playerPath) && isArray(body) && body.length <= MAX_ENVELOPE_ITEMS) {
      for (let index = 0; index < body.length; index++) {
        players.push(field(descriptor(body, index)?.value, 'playerResponse'));
      }
    }
    // Shorts continuation entries and adClientParams are not player objects.
    return players;
  }

  function collectRemovals(body, path) {
    return playerObjects(body, path).flatMap(player =>
      removableAdFields(player).map(name => [player, name]));
  }

  function applyRemovals(removals) {
    let count = 0;
    for (const [player, name] of removals) {
      if (descriptor(player, name) && remove(player, name)) count++;
    }
    return count;
  }

  function prunePlayerObject(body, path, source) {
    try {
      const removals = collectRemovals(body, path);
      if (removals.length && allowed()) observe(source, applyRemovals(removals));
    } catch (_) {
      // Unexpected page objects must not interrupt consumers.
    }
  }

  // Text consumers could use a different parser or depend on exact numbers,
  // escapes or duplicate keys. Rewrite only when native JSON round-trips the
  // original tokens exactly (ignoring whitespace outside strings).
  function matchesJsonTokens(text, canonical) {
    let quoted = false;
    let escaped = false;
    let offset = 0;
    for (let index = 0; index < text.length; index++) {
      const char = text[index];
      if (!quoted && (char === ' ' || char === '\t' || char === '\n' || char === '\r')) continue;
      if (char !== canonical[offset++]) return false;
      if (escaped) escaped = false;
      else if (quoted && char === '\\') escaped = true;
      else if (char === '"') quoted = !quoted;
    }
    return offset === canonical.length;
  }

  function filterText(text, path, source) {
    if (typeof text !== 'string' || text.length > MAX_TEXT_CHARS ||
        descriptor(objectPrototype, 'toJSON') || descriptor(arrayPrototype, 'toJSON')) return text;
    try {
      const body = parse(text);
      const removals = collectRemovals(body, path);
      if (!removals.length || !matchesJsonTokens(text, stringify(body))) return text;
      const changes = applyRemovals(removals);
      if (!changes) return text;
      const filtered = stringify(body);
      observe(source, changes);
      return filtered;
    } catch (_) {
      return text;
    }
  }

  function watchBootstrap(name) {
    const previous = descriptor(window, name);
    if (previous && (!previous.configurable || !previous.writable)) return;
    let player = previous?.value;
    define(window, name, {
      configurable: true,
      enumerable: previous?.enumerable ?? true,
      get: () => player,
      set: next => {
        player = next;
        prunePlayerObject(player, null, 'bootstrap');
      }
    });
    prunePlayerObject(player, null, 'bootstrap');
  }

  function playerEndpoint(input) {
    try {
      const urlText = input instanceof Url ? apply(urlHref, input, []) : input;
      if (typeof urlText !== 'string' || !urlText || urlText.length > 8192) return null;
      const url = new Url(urlText, document.baseURI);
      return url.origin === origin && !url.username && !url.password && paths.has(url.pathname)
        ? url.pathname : null;
    } catch (_) {
      return null;
    }
  }

  function fetchEndpoint(input) {
    try {
      return playerEndpoint(input instanceof Request ? apply(requestUrl, input, []) : input);
    } catch (_) {
      return null;
    }
  }

  function isJson(contentType) {
    return typeof contentType === 'string' && /^application\/json(?:\s*;|$)/i.test(contentType);
  }

  function wrapMethod(owner, name, handler) {
    const property = descriptor(owner, name);
    if (typeof property?.value !== 'function' || !property.configurable) return;
    define(owner, name, { ...property, value: new Proxy(property.value, { apply: handler }) });
  }

  function installFetch() {
    const responses = new WeakMap();
    const responsePrototype = window.Response.prototype;
    wrapMethod(responsePrototype, 'json', (target, response, args) => {
      const pending = apply(target, response, args);
      const path = responses.get(response);
      return path ? apply(then, pending, [body => {
        prunePlayerObject(body, path, 'fetch');
        return body;
      }]) : pending;
    });
    wrapMethod(responsePrototype, 'text', (target, response, args) => {
      const pending = apply(target, response, args);
      const path = responses.get(response);
      return path ? apply(then, pending, [text => allowed() ? filterText(text, path, 'fetch') : text]) : pending;
    });
    wrapMethod(responsePrototype, 'clone', (target, response, args) => {
      const clone = apply(target, response, args);
      const path = responses.get(response);
      if (path) responses.set(clone, path);
      return clone;
    });
    wrapMethod(window, 'fetch', (target, receiver, args) => {
      const path = fetchEndpoint(args[0]);
      const pending = apply(target, receiver, args);
      if (!path) return pending;
      return apply(then, pending, [response => {
        try {
          if (response.ok && playerEndpoint(response.url) === path && isJson(response.headers.get('content-type'))) {
            responses.set(response, path);
          }
        } catch (_) {
          // A response with unverifiable metadata is not a candidate.
        }
        return response;
      }]);
    });
  }

  function installXhr() {
    const Xhr = window.XMLHttpRequest;
    const xhrPrototype = Xhr.prototype;
    const getHeader = xhrPrototype.getResponseHeader;
    const requests = new WeakMap();
    wrapMethod(xhrPrototype, 'open', (target, xhr, args) => {
      const path = playerEndpoint(args[1]);
      if (path && xhr instanceof Xhr) requests.set(xhr, { path });
      else requests.delete(xhr);
      try {
        return apply(target, xhr, args);
      } catch (error) {
        requests.delete(xhr);
        throw error;
      }
    });

    function filterXhrBody(xhr, body) {
      const request = requests.get(xhr);
      if (!request || xhr.readyState !== 4) return body;
      if (request.accepted === undefined) {
        request.accepted = xhr.status >= 200 && xhr.status < 300 &&
          playerEndpoint(xhr.responseURL) === request.path && isJson(apply(getHeader, xhr, ['content-type']));
      }
      if (!request.accepted) return body;
      if (xhr.responseType === 'json') prunePlayerObject(body, request.path, 'xhr');
      if ((xhr.responseType === '' || xhr.responseType === 'text') && allowed()) {
        if (request.filteredText === undefined) request.filteredText = filterText(body, request.path, 'xhr');
        return request.filteredText;
      }
      return body;
    }

    for (const name of ['response', 'responseText']) {
      const property = descriptor(xhrPrototype, name);
      if (!property?.configurable || !property.get) continue;
      define(xhrPrototype, name, { ...property, get() {
        const body = apply(property.get, this, []);
        try {
          return filterXhrBody(this, body);
        } catch (_) {
          return body;
        }
      } });
    }
  }

  watchBootstrap('ytInitialPlayerResponse');
  watchBootstrap('playerResponse');
  installFetch();
  installXhr();
})();
