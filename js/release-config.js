/* Public metadata only. Never put a GitHub token in this file. */
(function (root) {
  'use strict';
  const config = Object.freeze({
    repository: 'moham34643fxx-sudo/mediaflow',
    // Same-origin public metadata API on this Vercel deployment.
    feedUrl: root.location && root.location.protocol === 'https:' ? root.location.origin + '/api/update' : '',
    // The public website always discovers stable. URL query strings cannot enable QA.
    fallback: Object.freeze({
      versionCode: 53,
      versionName: '0.9.20',
      minSdk: 24,
      channel: 'qa',
      abi: 'arm64-v8a',
      path: 'downloads/MediaFlow-latest.apk',
      bytes: 56779164,
      sha256: '9a1f13a1bf5965b56e79f4e1c2cfc016e4316e9232592706ef30ce9f5abac596',
    }),
  });
  if (typeof module === 'object' && module.exports) module.exports = config;
  else root.MEDIAFLOW_RELEASE_CONFIG = config;
})(typeof window === 'object' ? window : globalThis);
