import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const generated = process.env.MEDIAFLOW_YOUTUBE_SCRIPTLET_ASSET ??
  new URL('../../../build/generated/adblockAssets/adblock/youtube-player-document-start.js', import.meta.url);
const script = readFileSync(generated, 'utf8');
const youtube = 'https://m.youtube.com';
const playerPath = '/youtubei/v1/player';
const watchPath = '/youtubei/v1/get_watch';
const reelPath = '/youtubei/v1/reel/reel_item_watch';
const sequencePath = '/youtubei/v1/reel/reel_watch_sequence';
const content = {
  playabilityStatus: { status: 'OK' },
  videoDetails: { videoId: 'abc1234_-90', title: 'A "quote" and a space' },
  streamingData: { formats: [{ url: 'https://media.example.test/content?value=a%20b' }] },
  account: { id: '9007199254740993', adSlots: ['not player data'] }
};
const withAds = { ...content, playerAds: [{}], adPlacements: [{}], adSlots: [{}] };

function page({ origin = youtube, initiallyAllowed = true, iframe = false } = {}) {
  const policy = { enabled: initiallyAllowed };
  const routes = new Map();
  const context = vm.createContext({ URL, Request, routes, allow: () => policy.enabled, origin, iframe });
  const run = js => vm.runInContext(js, context);
  run(`
    globalThis.window = globalThis;
    window.top = iframe ? {} : window;
    window.location = new URL(origin + '/watch');
    window.document = { baseURI: location.href };
    const original = ${JSON.stringify(withAds)};
    let fetchCalls = 0;
    let nativeClones = 0;
    function requestUrl(input) { return input instanceof Request ? input.url : new URL(input, document.baseURI).href; }
    function lookup(input) {
      const target = requestUrl(input);
      const fixture = routes.get(target);
      if (!fixture) throw new Error('fixture missing');
      return { url: target, status: 200, mime: 'application/json', ...fixture };
    }
    window.Response = class Response {
      constructor(fixture) {
        this.fixture = fixture;
        this.url = fixture.url;
        this.status = fixture.status;
        this.ok = this.status >= 200 && this.status < 300;
        this.headers = new Map([['content-type', fixture.mime]]);
        this.bodyUsed = false;
      }
      async text() {
        if (this.bodyUsed) throw new TypeError('body used');
        this.bodyUsed = true;
        return this.fixture.text;
      }
      async json() {
        if (this.bodyUsed) throw new TypeError('body used');
        this.bodyUsed = true;
        return JSON.parse(this.fixture.text);
      }
      clone() {
        if (this.bodyUsed) throw new TypeError('body used');
        nativeClones++;
        return new Response(this.fixture);
      }
    };
    window.fetch = async input => { fetchCalls++; return new Response(lookup(input)); };
    window.XMLHttpRequest = class XMLHttpRequest {
      responseType = '';
      open(method, input) { this.input = input; this.readyState = 1; }
      send() {
        this.fixture = lookup(this.input);
        this.status = this.fixture.status;
        this.responseURL = this.fixture.url;
        this.readyState = 4;
        this.decoded = this.responseType === 'json' ? JSON.parse(this.fixture.text) : this.fixture.text;
      }
      getResponseHeader(name) { return name === 'content-type' ? this.fixture.mime : null; }
      get response() { return this.decoded; }
      get responseText() {
        if (this.responseType && this.responseType !== 'text') throw new TypeError('not text');
        return this.decoded;
      }
    };
    const originalFetch = fetch;
    const status = () => window[Symbol.for('mediaflow.youtube-player-scriptlet.v1')]?.();
  `);
  return {
    run,
    load: () => run(`(() => {
      const mfAllowsYoutubePlayerScriptlet = allow;
      ${script}
    })()`),
    response(path, payload, overrides = {}) {
      routes.set(new URL(path, origin).href, { text: JSON.stringify(payload), ...overrides });
    },
    policy
  };
}

const plain = value => JSON.parse(JSON.stringify(value));

test('the first page script sees pruned bootstrap assignments, with the content objects intact', () => {
  const browser = page();
  browser.load();
  assert.equal(browser.run(`
    const originalStream = original.streamingData;
    var ytInitialPlayerResponse = original;
    playerResponse = ${JSON.stringify(withAds)};
    ytInitialPlayerResponse === original && ytInitialPlayerResponse.streamingData === originalStream;
  `), true);
  assert.deepEqual(plain(browser.run('[ytInitialPlayerResponse, playerResponse]')), [content, content]);
});

test('Fetch and XHR cover the supported player, get_watch and reel envelopes without touching siblings', async () => {
  const browser = page();
  browser.load();
  const envelopes = [
    [playerPath, withAds, content],
    [watchPath, [{ playerResponse: withAds }, { response: withAds }],
      [{ playerResponse: content }, { response: withAds }]],
    [reelPath, { playerResponse: withAds, tracking: { adPlacements: [1] } },
      { playerResponse: content, tracking: { adPlacements: [1] } }],
    [sequencePath, { playerResponse: withAds, entries: [{ command: { adClientParams: { isAd: true } } }] },
      { playerResponse: content, entries: [{ command: { adClientParams: { isAd: true } } }] }]
  ];
  for (const [endpoint, input, expected] of envelopes) {
    const path = `${endpoint}?fixture=yes`;
    const url = JSON.stringify(new URL(path, youtube).href);
    browser.response(path, input);
    assert.deepEqual(plain(await browser.run(`fetch(new Request(${url})).then(r => r.json())`)), expected, path);
    assert.deepEqual(plain(browser.run(`(() => {
      const xhr = new XMLHttpRequest();
      xhr.open('POST', new URL(${url}));
      xhr.responseType = 'json';
      xhr.send();
      if (xhr.response !== xhr.response) throw new Error('changed response identity');
      return xhr.response;
    })()`)), expected, path);
  }
  assert.equal(browser.run('fetchCalls'), envelopes.length);
});

test('native response metadata, body consumption and clone behavior survive filtering', async () => {
  const browser = page();
  browser.load();
  browser.response(playerPath, withAds, { text: JSON.stringify(withAds, null, 2) });
  assert.deepEqual(plain(await browser.run(`(async () => {
    const response = await fetch('${playerPath}');
    const clone = response.clone();
    const filtered = JSON.parse(await response.text());
    const cloneData = await clone.json();
    return { filtered, cloneData, bodyUsed: response.bodyUsed, url: response.url, status: response.status };
  })()`)), { filtered: content, cloneData: content, bodyUsed: true, url: youtube + playerPath, status: 200 });
  assert.equal(browser.run('fetchCalls'), 1);
  assert.equal(browser.run('nativeClones'), 1);
  await assert.rejects(browser.run(`fetch('${playerPath}').then(async r => { await r.json(); return r.json(); })`), /body used/);
});

test('non-target paths, wrong MIME and status, and cross-origin or mismatched final URLs pass unchanged', async () => {
  const browser = page();
  browser.load();
  for (const [path, metadata] of [
    ['/youtubei/v1/player/ad_break', {}],
    ['/api?target=/youtubei/v1/player', {}],
    [`https://www.youtube.com${playerPath}`, {}],
    [playerPath, { status: 403 }],
    [playerPath, { mime: 'text/plain' }],
    [playerPath, { url: `https://www.youtube.com${playerPath}` }],
    [playerPath, { url: `${youtube}${watchPath}` }]
  ]) {
    browser.response(path, withAds, metadata);
    assert.deepEqual(plain(await browser.run(`fetch(${JSON.stringify(path)}).then(r => r.json())`)), withAds);
  }
});

test('XHR does not rewrite partial, oversized, lossy, malformed or reopened responses', () => {
  const browser = page();
  browser.load();
  const raw = JSON.stringify(withAds, null, 2);
  browser.response(playerPath, withAds, { text: raw });
  browser.response('/other', withAds, { text: raw });
  browser.run(`const xhr = new XMLHttpRequest(); xhr.open('POST', '${playerPath}'); xhr.send();`);
  assert.equal(browser.run('xhr.readyState = 3; xhr.responseText'), raw);
  assert.deepEqual(JSON.parse(browser.run('xhr.readyState = 4; xhr.responseText')), content);
  assert.equal(browser.run('xhr.response === xhr.responseText'), true);
  assert.equal(browser.run("xhr.open('GET', '/other'); xhr.send(); xhr.responseText"), raw);
  for (const text of [
    raw.replace('"9007199254740993"', '9007199254740993'),
    raw.replace('"account": {', '"account": null, "account": {'),
    `)]}'\n${raw}`,
    `${raw}${' '.repeat(1024 * 1024)}`
  ]) {
    browser.response(playerPath, withAds, { text });
    assert.equal(browser.run(`xhr.open('POST', '${playerPath}'); xhr.send(); xhr.responseText`), text);
  }
});

test('unknown or unavailable player structures and immutable/accessor fields are not rewritten', () => {
  const browser = page();
  browser.run("Object.defineProperty(window, 'playerResponse', { configurable: true, get() { throw new Error('unread'); } });");
  browser.load();
  assert.equal(browser.run(`
    const frozen = Object.freeze({ ...original });
    const unavailable = { ...original, playabilityStatus: { status: 'LOGIN_REQUIRED' } };
    const unknown = { ...original, streamingData: null };
    const wrongFields = { ...original, playerAds: { value: [] }, adSlots: [], adPlacements: null };
    const untouched = [frozen, unavailable, unknown, wrongFields].every(value => {
      ytInitialPlayerResponse = value;
      return ytInitialPlayerResponse === value && 'playerAds' in value &&
        'adPlacements' in value && 'adSlots' in value;
    });
    untouched;
  `), true);
  assert.throws(() => browser.run('playerResponse'), /unread/);
});

test('changes stop in the current document when disabled, and repeated injection and SPA do not stack hooks', async () => {
  const browser = page();
  browser.response(playerPath, withAds);
  browser.load();
  browser.run('const wrapped = fetch;');
  browser.load();
  browser.run("location = new URL(origin + '/shorts/synthetic'); document.baseURI = location.href;");
  browser.policy.enabled = false;
  assert.equal(browser.run('ytInitialPlayerResponse = original; original.adSlots.length'), 1);
  assert.deepEqual(plain(await browser.run(`fetch('${playerPath}').then(r => r.json())`)), withAds);
  browser.policy.enabled = true;
  assert.deepEqual(plain(await browser.run(`fetch('${playerPath}').then(r => r.json())`)), content);
  assert.equal(browser.run('fetch === wrapped'), true);
});

test('a fresh disabled document, other origins and subframes retain the original platform APIs', () => {
  for (const options of [
    { initiallyAllowed: false },
    { iframe: true },
    { origin: 'http://m.youtube.com' },
    { origin: 'https://m.youtube.com:8080' },
    { origin: 'https://tv.youtube.com' },
    { origin: 'https://youtube.com.example.test' }
  ]) {
    const browser = page(options);
    browser.load();
    assert.equal(browser.run(`fetch === originalFetch && status() === undefined &&
      !Object.hasOwn(window, 'ytInitialPlayerResponse')`), true, JSON.stringify(options));
  }
});
