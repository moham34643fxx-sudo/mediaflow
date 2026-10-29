/* Public metadata only. Never put a GitHub token in this file. */
(function (root) {
  'use strict';
  const config = Object.freeze({
    repository: 'moham34643fxx-sudo/mediaflow',
    // Optional absolute HTTPS manifest endpoint. Empty = GitHub Releases on Pages.
    feedUrl: root.location && root.location.protocol === 'https:' ? root.location.origin + '/api/update' : '',
    // The public website always discovers stable. URL query strings cannot enable QA.
    fallback: Object.freeze({
      versionCode: 61,
      versionName: '0.9.28',
      minSdk: 24,
      channel: 'qa',
      abi: 'arm64-v8a',
      path: 'downloads/MediaFlow-latest.apk',
      bytes: 54188182,
      sha256: '16fe2d98cc72704c5ad0fa0af23edd6a7c427b03541502f20d7f6a6b607bc367',
    }),
  });
  if (typeof module === 'object' && module.exports) module.exports = config;
  else root.MEDIAFLOW_RELEASE_CONFIG = config;
})(typeof window === 'object' ? window : globalThis);
