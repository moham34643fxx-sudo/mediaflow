/* Progressive enhancement of the existing site. No dependencies or browser secrets. */
(function (root, factory) {
  'use strict';
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.MediaFlowReleases = api;
})(typeof window === 'object' ? window : globalThis, function () {
  'use strict';
  const MAX_BYTES = 2147483647;
  const SAFE_NAME = /^[A-Za-z0-9][A-Za-z0-9._+-]{0,127}$/;
  const ABIS = new Set(['arm64-v8a', 'x86_64', 'universal']);
  const DOWNLOAD_ORIGINS = new Set(['https://release-assets.githubusercontent.com',
    'https://objects.githubusercontent.com', 'https://github-releases.githubusercontent.com']);
  const documents = new WeakMap();

  function need(condition) {
    if (!condition) throw new Error('invalid_release_metadata');
  }
  function https(value) {
    need(typeof value === 'string' && value.length <= 8192);
    const url = new URL(value);
    need(url.protocol === 'https:' && !url.username && !url.password && !url.port && !url.hash);
    return url;
  }
  function integer(value, min, max) {
    return Number.isSafeInteger(value) && value >= min && value <= max;
  }
  function text(value, max) {
    return typeof value === 'string' && value.trim().length > 0 && value.length <= max &&
      !/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/.test(value);
  }
  function timestamp(value) {
    need(typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?Z$/.test(value) &&
      Number.isFinite(Date.parse(value)) && new Date(value).toISOString().slice(0, 19) === value.slice(0, 19));
    return value;
  }
  function owned(value, repository, tag, filename) {
    const url = https(value);
    need(url.origin === 'https://github.com' && !url.search && SAFE_NAME.test(tag));
    const parts = url.pathname.split('/').slice(1).map(decodeURIComponent);
    const expected = filename === undefined ? [...repository.split('/'), 'releases', 'tag', tag]
      : [...repository.split('/'), 'releases', 'download', tag, filename];
    need(parts.length === expected.length && parts.every((p, i) =>
      i < 2 ? p.toLowerCase() === expected[i].toLowerCase() : p === expected[i]));
    return `https://github.com/${repository}/releases/` + (filename === undefined
      ? `tag/${encodeURIComponent(tag)}` : `download/${encodeURIComponent(tag)}/${encodeURIComponent(filename)}`);
  }
  function selectRelease(rows, channel = 'stable') {
    need(['stable', 'qa'].includes(channel) && Array.isArray(rows));
    need(rows.every(row => row && typeof row.tag_name === 'string' && typeof row.draft === 'boolean' &&
      typeof row.prerelease === 'boolean' && Array.isArray(row.assets) &&
      row.assets.every(asset => asset && typeof asset.name === 'string')));
    const matching = rows.filter(row => !row.draft && row.published_at &&
      (channel === 'qa' ? row.prerelease && /^qa-/i.test(row.tag_name) : !row.prerelease && !/^qa-/i.test(row.tag_name)) &&
      row.assets.some(asset => asset.name === 'mediaflow-update.json'));
    matching.forEach(row => timestamp(row.published_at));
    matching.sort((a, b) => Date.parse(b.published_at) - Date.parse(a.published_at) || (b.id || 0) - (a.id || 0));
    return matching[0] || null;
  }
  function validateManifest(data, repository, release = null, channel = 'stable') {
    need(data && data.schema === 1 && data.appId === 'com.zdownloader.app' && data.channel === channel &&
      integer(data.versionCode, 1, 2147483647) && text(data.versionName, 100) && !/[\r\n\t]/.test(data.versionName) &&
      integer(data.minSdk, 24, 1000) && data.notes && text(data.notes.ar, 16000) && text(data.notes.en, 16000) &&
      Array.isArray(data.assets) && data.assets.length >= 1 && data.assets.length <= 3);
    if (data.publishedAt !== undefined) timestamp(data.publishedAt);
    const first = https(data.assets[0]?.url);
    const tag = release ? release.tag_name : decodeURIComponent(first.pathname.split('/')[5] || '');
    need(SAFE_NAME.test(tag) && (/^qa-/i.test(tag) === (channel === 'qa')));
    if (release) {
      need(selectRelease([release], channel) === release && release.assets.length <= 100);
      owned(release.html_url, repository, tag);
    }
    if (data.releaseUrl !== undefined) owned(data.releaseUrl, repository, tag);
    const abis = new Set();
    const urls = new Set();
    const assets = data.assets.map(asset => {
      need(asset && ABIS.has(asset.abi) && !abis.has(asset.abi) && integer(asset.bytes, 1, MAX_BYTES) &&
        typeof asset.sha256 === 'string' && /^[a-f0-9]{64}$/.test(asset.sha256));
      const name = decodeURIComponent(https(asset.url).pathname.split('/').pop());
      need(SAFE_NAME.test(name) && /\.apk$/i.test(name));
      const url = owned(asset.url, repository, tag, name);
      need(!urls.has(url));
      if (release) {
        const matches = release.assets.filter(entry => entry.name === name);
        need(matches.length === 1 && matches[0].state === 'uploaded' && matches[0].size === asset.bytes &&
          owned(matches[0].browser_download_url, repository, tag, name) === url);
        if (matches[0].digest !== undefined && matches[0].digest !== null) {
          need(matches[0].digest === `sha256:${asset.sha256}`);
        }
      }
      abis.add(asset.abi);
      urls.add(url);
      return { abi: asset.abi, url, bytes: asset.bytes, sha256: asset.sha256 };
    });
    return { schema: 1, appId: data.appId, channel, versionCode: data.versionCode,
      versionName: data.versionName, minSdk: data.minSdk, notes: { ar: data.notes.ar, en: data.notes.en },
      assets, releaseUrl: owned(`https://github.com/${repository}/releases/tag/${encodeURIComponent(tag)}`, repository, tag) };
  }
  async function readJson(response, limit) {
    if (response.status !== 200) throw new Error(`release_http_${response.status}`);
    const length = response.headers.get('content-length');
    need(!length || (/^\d+$/.test(length) && Number(length) <= limit));
    need(response.body);
    const reader = response.body.getReader();
    const chunks = [];
    let size = 0;
    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        size += value.byteLength;
        need(size <= limit);
        chunks.push(value);
      }
    } catch (error) {
      await reader.cancel();
      throw error;
    } finally { reader.releaseLock(); }
    const bytes = new Uint8Array(size);
    let offset = 0;
    chunks.forEach(chunk => { bytes.set(chunk, offset); offset += chunk.byteLength; });
    return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
  }
  async function discover(config, fetchImpl, signal) {
    need(/^[A-Za-z0-9][A-Za-z0-9-]{0,38}\/[A-Za-z0-9][A-Za-z0-9._-]{0,99}$/.test(config.repository));
    const options = { signal, credentials: 'omit', referrerPolicy: 'no-referrer' };
    if (config.feedUrl) {
      const url = https(config.feedUrl);
      need(!url.search && !url.username && !url.password);
      url.searchParams.set('channel', 'stable');
      const response = await fetchImpl(url.href, { ...options, redirect: 'error', headers: { Accept: 'application/json' } });
      return validateManifest(await readJson(response, 65536), config.repository);
    }
    const rows = [];
    for (let page = 1; page <= 5; page++) {
      const url = `https://api.github.com/repos/${config.repository}/releases?per_page=20` + (page === 1 ? '' : `&page=${page}`);
      const response = await fetchImpl(url, { ...options, redirect: 'error', headers: { Accept: 'application/vnd.github+json' } });
      const batch = await readJson(response, 2 * 1024 * 1024);
      need(Array.isArray(batch) && batch.length <= 20);
      rows.push(...batch);
      if (batch.length < 20) break;
      if (page === 5) throw new Error('release_discovery_limit');
    }
    const release = selectRelease(rows);
    if (!release) return null;
    const candidates = release.assets.filter(asset => asset.name === 'mediaflow-update.json');
    need(candidates.length === 1);
    const asset = candidates[0];
    need(integer(asset.id, 1, Number.MAX_SAFE_INTEGER) && asset.state === 'uploaded' && integer(asset.size, 1, 65536));
    owned(asset.browser_download_url, config.repository, release.tag_name, asset.name);
    // Public REST binary endpoint avoids assuming github.com download links have browser CORS.
    const url = `https://api.github.com/repos/${config.repository}/releases/assets/${asset.id}`;
    const response = await fetchImpl(url, { ...options, redirect: 'follow', headers: { Accept: 'application/octet-stream' } });
    const final = https(response.url || url);
    need(final.href === url || DOWNLOAD_ORIGINS.has(final.origin));
    return validateManifest(await readJson(response, 65536), config.repository, release);
  }
  function createController({ document, translations, config, fetchImpl = globalThis.fetch, timeoutMs = 20000 }) {
    const fallbackUrl = new URL(config.fallback.path, document.baseURI).href;
    const links = [...document.querySelectorAll('a[href]')].filter(link =>
      new URL(link.getAttribute('href'), document.baseURI).href === fallbackUrl);
    let selected = { ...config.fallback, url: fallbackUrl };
    let versionCode = config.fallback.versionCode;
    let status = 'fallback';
    let started = false;
    function render(lang = document.documentElement.lang || 'ar') {
      const words = translations[lang]?.release || translations.en.release;
      const kind = words[selected.channel];
      const abi = selected.abi === 'arm64-v8a' ? 'ARM64' : selected.abi === 'universal' ? 'ARM64 / x86_64' : 'x86_64';
      const android = `${words.android} API ${selected.minSdk}+ (${abi})`;
      const labels = {
        'hero.versionSubtitle': lang === 'ar' ? `(الإصدار ${selected.versionName})` : `(v${selected.versionName})`,
        'hero.badge': `${status === 'ready' ? words.stable : words.available} • v${selected.versionName}${selected.channel === 'qa' ? ' (QA)' : ''}`,
        'hero.badgeSize': `${(selected.bytes / (1024 * 1024)).toFixed(1)} MiB`,
        'hero.badgeAndroid': android,
        'hero.badgeClean': kind,
        'cta.spec': `${kind} • ${android}`,
        'footer.cleanBadge': `${kind} • ${words.checksum}`,
        'faq.a5': `${words.requires} ${android}`,
      };
      Object.entries(labels).forEach(([key, value]) => document.querySelectorAll(`[data-i18n="${key}"]`)
        .forEach(element => { element.textContent = value; }));
      links.forEach(link => {
        link.setAttribute('href', 'downloads/MediaFlow-latest.apk');
        link.setAttribute('download', 'MediaFlow-latest.apk');
        link.setAttribute('data-sha256', selected.sha256);
        link.setAttribute('data-bytes', String(selected.bytes));
        link.setAttribute('title', `v${selected.versionName} • ${selected.bytes} bytes • SHA-256: ${selected.sha256}`);
      });
      document.documentElement.setAttribute('data-release-status', status);
    }
    async function load() {
      if (started) return status;
      started = true;
      // Label the audited download immediately, even while discovery is slow/offline.
      render();
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), timeoutMs);
      try {
        const manifest = await discover(config, fetchImpl, controller.signal);
        if (!manifest) status = 'no-release';
        else if (manifest.versionCode < versionCode) status = 'not-newer';
        else {
          // The existing landing page targets ARM64; never silently offer x86-only APKs.
          const asset = manifest.assets.find(entry => entry.abi === 'arm64-v8a') || manifest.assets.find(entry => entry.abi === 'universal');
          need(asset);
          selected = { ...manifest, ...asset };
          versionCode = manifest.versionCode;
          status = 'ready';
        }
      } catch { status = 'unavailable'; }
      finally {
        clearTimeout(timer);
        controller.abort();
        render();
      }
      return status;
    }
    return { render, load, get status() { return status; } };
  }
  return {
    selectRelease, validateManifest, discover, createController,
    start(document, translations) {
      if (!window.MEDIAFLOW_RELEASE_CONFIG) return Promise.resolve('unavailable');
      if (!documents.has(document)) documents.set(document, createController({
        document, translations, config: window.MEDIAFLOW_RELEASE_CONFIG,
      }));
      return documents.get(document).load();
    },
    render(document, lang) { documents.get(document)?.render(lang); },
  };
});
