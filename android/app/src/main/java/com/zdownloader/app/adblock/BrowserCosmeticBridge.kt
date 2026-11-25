package com.zdownloader.app.adblock

import android.os.SystemClock
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicReference

internal class BrowserCosmeticBridge(
    initialPageUrl: String?,
    private val playerScriptletKey: String? = null
) {
    private val topLevelUrl = AtomicReference(initialPageUrl)
    private val youtubeAdRecoveryPolicy = YoutubeAdRecoveryPolicy()

    @JavascriptInterface
    fun selectors(frameUrl: String, classesJson: String, idsJson: String): String {
        val pageUrl = topLevelUrl.get() ?: run {
            BrowserAdblockDiagnostics.cosmetic(BrowserAdblockDiagnostics.CosmeticLookup.MISSING_PAGE)
            return EMPTY_SELECTORS
        }
        return BrowserAdblock.cosmeticSelectors(pageUrl, frameUrl, classesJson, idsJson)
    }

    @JavascriptInterface
    fun youtubeAdGuardEnabled(): Boolean =
        BrowserAdblock.youtubeAdHandlingEnabled(topLevelUrl.get())

    @JavascriptInterface
    fun youtubePlayerScriptletAllowed(key: String?, origin: String?): Boolean =
        // The legacy bridge has no frame identity. Only the exact-origin
        // document-start closure knows this per-WebView capability.
        playerScriptletKey != null && key == playerScriptletKey &&
            BrowserYoutubePlayerScriptlet.isAllowed(origin, BrowserAdblock.state.value)

    @JavascriptInterface
    fun setYoutubeAdActive(active: Boolean) {
        BrowserAdblock.setYoutubeAdActive(topLevelUrl.get(), active)
    }

    @JavascriptInterface
    fun youtubeAdRecoveryDecision(
        active: Boolean,
        videoWasPlaying: Boolean,
        videoPaused: Boolean,
        skipButtonUsable: Boolean,
        userInteracted: Boolean
    ): String {
        val decision = if (BrowserAdblock.youtubeAdHandlingEnabled(topLevelUrl.get())) {
            youtubeAdRecoveryPolicy.update(
                active = active,
                videoWasPlaying = videoWasPlaying,
                videoPaused = videoPaused,
                skipButtonUsable = skipButtonUsable,
                userInteracted = userInteracted,
                nowMillis = SystemClock.elapsedRealtime()
            )
        } else {
            youtubeAdRecoveryPolicy.reset()
            YoutubeAdRecoveryPolicy.Decision(
                action = YoutubeAdRecoveryPolicy.Action.NONE,
                reason = "youtube_ad_handling_disabled"
            )
        }
        return JSONObject()
            .put("action", decision.action.name)
            .put("attempt", decision.attempt)
            .put("reason", decision.reason)
            .toString()
    }

    @JavascriptInterface
    fun reportYoutubeAdAction(action: String, attempt: Int, result: String) {
        BrowserAdblockDiagnostics.playerAction(action, attempt, result)
    }

    companion object {
        const val NAME = "MediaFlowAdblockNative"
        private val installedBridges = WeakHashMap<WebView, BrowserCosmeticBridge>()

        private const val COSMETIC_DOCUMENT_START_SCRIPT = """
            (() => {
              if (window.__mfAdblockInstalled) return;
              const bridge = window.MediaFlowAdblockNative;
              if (!bridge || !document) return;
              window.__mfAdblockInstalled = true;
              const MAX_PER_QUERY = 64;
              const MAX_TOTAL = 256;
              const MAX_SCANNED_NODES = 512;
              const MAX_QUERIES = 4;
              const seenClasses = new Set();
              const seenIds = new Set();
              const applied = new Set();
              let total = 0;
              let scannedNodes = 0;
              let queryCount = 0;
              let style = null;

              function ensureStyle() {
                if (style && style.isConnected) return style;
                style = document.getElementById('__mf_adblock_cosmetic');
                if (!style) {
                  style = document.createElement('style');
                  style.id = '__mf_adblock_cosmetic';
                  (document.head || document.documentElement || document).appendChild(style);
                }
                return style;
              }

              function flush(classes, ids) {
                if ((!classes.length && !ids.length) || queryCount >= MAX_QUERIES) return;
                try {
                  queryCount++;
                  const json = bridge.selectors(location.href, JSON.stringify(classes), JSON.stringify(ids));
                  const selectors = JSON.parse(json);
                  if (!Array.isArray(selectors)) return;
                  let additions = '';
                  for (const selector of selectors) {
                    if (typeof selector !== 'string' || !selector || applied.has(selector)) continue;
                    applied.add(selector);
                    additions += selector + '{display:none!important}';
                  }
                  if (additions) ensureStyle().appendChild(document.createTextNode(additions));
                } catch (_) {}
              }

              function inspect(root) {
                if (!root || total >= MAX_TOTAL || scannedNodes >= MAX_SCANNED_NODES ||
                    queryCount >= MAX_QUERIES) return;
                const classes = [];
                const ids = [];
                const add = (element) => {
                  if (!element || element.nodeType !== 1) return;
                  for (const name of element.classList || []) {
                    if (total >= MAX_TOTAL || classes.length >= MAX_PER_QUERY) break;
                    if (name.length > 256 || seenClasses.has(name)) continue;
                    seenClasses.add(name);
                    classes.push(name);
                    total++;
                  }
                  if (total < MAX_TOTAL && element.id && element.id.length <= 256 &&
                      !seenIds.has(element.id)) {
                    seenIds.add(element.id);
                    ids.push(element.id);
                    total++;
                  }
                };
                let walker;
                try { walker = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT); } catch (_) { return; }
                let element = walker.currentNode;
                while (element && total < MAX_TOTAL && scannedNodes < MAX_SCANNED_NODES &&
                       queryCount < MAX_QUERIES) {
                  add(element);
                  scannedNodes++;
                  if (classes.length >= MAX_PER_QUERY || ids.length >= MAX_PER_QUERY) {
                    flush(classes.splice(0, MAX_PER_QUERY), ids.splice(0, MAX_PER_QUERY));
                  }
                  element = walker.nextNode();
                }
                flush(classes, ids);
              }

              window.__mfAdblockRescan = () => {
                seenClasses.clear();
                seenIds.clear();
                applied.clear();
                total = 0;
                scannedNodes = 0;
                queryCount = 0;
                if (style) style.textContent = '';
                inspect(document.documentElement);
              };

              try {
                new MutationObserver((records) => {
                  for (const record of records) {
                    for (const node of record.addedNodes) {
                      inspect(node);
                      if (total >= MAX_TOTAL) return;
                    }
                  }
                }).observe(document, { childList: true, subtree: true });
              } catch (_) {}
              if (document.documentElement) inspect(document.documentElement);
            })();
        """

        const val REAPPLY_SCRIPT =
            "if (window.__mfAdblockRescan) window.__mfAdblockRescan();" +
                "if (window.__mfYoutubeAdGuardSync) window.__mfYoutubeAdGuardSync();"

        private const val EMPTY_SELECTORS = "[]"

        private const val YOUTUBE_AD_GUARD_SCRIPT = """
            (() => {
              if (window.__mfYoutubeAdGuardInstalled || window.top !== window.self ||
                  !isYoutubePage()) return;
              const bridge = window.MediaFlowAdblockNative;
              if (!bridge || !document) return;
              window.__mfYoutubeAdGuardInstalled = true;
              let rootObserver = null;
              let playerObserver = null;
              let player = null;
              let video = null;
              let reportedAdActive = false;
              let retryTimer = null;
              let retryAt = 0;
              let verificationTimer = null;
              let pendingAttempt = 0;
              let gaveUp = false;
              let lastContentWasPlaying = false;
              let contentVideoBeforeAd = null;
              let userInteractedDuringAd = false;
              let lastActiveCheckAt = 0;

              function isYoutubePage() {
                const host = location.hostname.toLowerCase();
                return host === 'youtube.com' || host.endsWith('.youtube.com');
              }

              function isEnabled() {
                try {
                  return isYoutubePage() && bridge.youtubeAdGuardEnabled() === true;
                } catch (_) {
                  return false;
                }
              }

              function reportAdState(active) {
                if (active === reportedAdActive) return;
                reportedAdActive = active;
                try {
                  bridge.setYoutubeAdActive(active);
                } catch (_) {
                  reportedAdActive = !active;
                }
              }

              function reportAction(action, attempt, result) {
                try {
                  bridge.reportYoutubeAdAction(action, attempt || 0, result || 'none');
                } catch (_) {}
              }

              function activeAd() {
                return !!player && (
                  player.classList.contains('ad-showing') ||
                  player.classList.contains('ad-interrupting')
                );
              }

              function onTrustedInteraction(event) {
                if ((reportedAdActive || activeAd()) && event && event.isTrusted) {
                  userInteractedDuringAd = true;
                }
              }

              function isVisibleSkipControl(control) {
                if (!control.isConnected || control.disabled) return false;
                const style = getComputedStyle(control);
                const rect = control.getBoundingClientRect();
                if (control.getClientRects().length === 0 || rect.width < 2 ||
                    rect.height < 2 || style.display === 'none' ||
                    style.visibility === 'hidden' || style.visibility === 'collapse' ||
                    style.pointerEvents === 'none') return false;
                for (let node = control; node; node = node.parentElement) {
                  if (node.hidden || node.inert || node.getAttribute('aria-hidden') === 'true' ||
                      node.getAttribute('aria-disabled') === 'true' || getComputedStyle(node).opacity === '0') {
                    return false;
                  }
                }
                return true;
              }

              function findUsableSkipButton() {
                if (!player || document.visibilityState !== 'visible') return null;
                const candidates = player.querySelectorAll(
                  '.ytp-ad-skip-button:not([disabled]),' +
                  '.ytp-ad-skip-button-modern:not([disabled]),' +
                  'ytm-skip-button button:not([disabled]),' +
                  'button[aria-label*="skip ad" i]:not([disabled]),' +
                  'button[aria-label*="تخطي"]:not([disabled])'
                );
                for (const candidate of candidates) {
                  const rect = candidate.getBoundingClientRect();
                  if (rect.width < 2 || rect.height < 2) continue;
                  const x = Math.max(0, Math.min(window.innerWidth - 1, rect.left + rect.width / 2));
                  const y = Math.max(0, Math.min(window.innerHeight - 1, rect.top + rect.height / 2));
                  const hit = document.elementFromPoint(x, y);
                  if (!hit || !candidate.contains(hit)) continue;
                  // A matching wrapper can be visible without owning the button's click handler.
                  const control = hit.closest('button,[role="button"]');
                  if (control && player.contains(control) && isVisibleSkipControl(control)) return control;
                }
                return null;
              }

              function clearTimers() {
                if (retryTimer !== null) clearTimeout(retryTimer);
                if (verificationTimer !== null) clearTimeout(verificationTimer);
                retryTimer = null;
                verificationTimer = null;
              }

              function getDecision(active, skipButtonUsable) {
                try {
                  return JSON.parse(bridge.youtubeAdRecoveryDecision(
                    active,
                    contentVideoBeforeAd !== null,
                    !!(video && video.paused),
                    skipButtonUsable,
                    userInteractedDuringAd
                  ));
                } catch (_) {
                  return { action: 'STOP', attempt: 0, reason: 'policy_unavailable' };
                }
              }

              function resumeContentPlayback(contentVideo) {
                try {
                  const result = contentVideo.play();
                  if (result && typeof result.then === 'function') {
                    result.then(
                      () => reportAction('resume_content', 0, 'playback_started'),
                      () => reportAction('resume_content', 0, 'playback_rejected')
                    );
                  } else {
                    reportAction('resume_content', 0, 'playback_requested');
                  }
                } catch (_) {
                  reportAction('resume_content', 0, 'playback_failed');
                }
              }

              function finishAd() {
                clearTimers();
                if (pendingAttempt) {
                  reportAction('skip_result', pendingAttempt, 'ad_cleared');
                  pendingAttempt = 0;
                }
                const decision = getDecision(false, false);
                const contentVideo = contentVideoBeforeAd;
                reportAdState(false);
                gaveUp = false;
                contentVideoBeforeAd = null;
                userInteractedDuringAd = false;
                if (decision.action === 'RESUME' && contentVideo && contentVideo === video &&
                    player?.isConnected && player.contains(contentVideo) && contentVideo.paused &&
                    !contentVideo.ended &&
                    document.visibilityState === 'visible') {
                  resumeContentPlayback(contentVideo);
                } else {
                  reportAction('ad_finished', 0, 'preserve_playback_state');
                }
              }

              function scheduleRetry(delay) {
                if (gaveUp) return;
                const wait = Math.max(100, delay);
                const due = performance.now() + wait;
                if (retryTimer !== null) {
                  if (retryAt <= due) return;
                  clearTimeout(retryTimer);
                }
                retryAt = due;
                retryTimer = setTimeout(() => {
                  retryTimer = null;
                  skipActiveAd();
                }, wait);
              }

              function verifySkip(attempt) {
                if (verificationTimer !== null) clearTimeout(verificationTimer);
                verificationTimer = setTimeout(() => {
                  verificationTimer = null;
                  if (!isEnabled() || !player?.isConnected) {
                    skipActiveAd();
                    return;
                  }
                  if (!activeAd()) {
                    finishAd();
                    return;
                  }
                  reportAction('skip_result', attempt, 'ad_still_active');
                  if (pendingAttempt === attempt) pendingAttempt = 0;
                  skipActiveAd();
                }, 800);
              }

              function skipActiveAd() {
                if (!isEnabled()) {
                  stopYoutubeAdGuard();
                  return;
                }
                if (!player || !player.isConnected) {
                  observeYoutubePlayer();
                  return;
                }
                if (!activeAd()) {
                  if (reportedAdActive) finishAd();
                  return;
                }

                const now = performance.now();
                if (reportedAdActive && now - lastActiveCheckAt < 500) {
                  scheduleRetry(500 - (now - lastActiveCheckAt));
                  return;
                }
                lastActiveCheckAt = now;
                if (!reportedAdActive) {
                  contentVideoBeforeAd = lastContentWasPlaying ? video : null;
                  lastContentWasPlaying = false;
                  gaveUp = false;
                  reportAction(
                    'ad_detected',
                    0,
                    contentVideoBeforeAd ? 'content_was_playing' : 'content_was_not_playing'
                  );
                }
                reportAdState(true);
                if (gaveUp) return;

                const skipButton = findUsableSkipButton();
                const decision = getDecision(true, !!skipButton);
                if (decision.action === 'GIVE_UP' || decision.action === 'STOP') {
                  gaveUp = true;
                  reportAction(
                    'recovery_give_up',
                    decision.attempt,
                    decision.reason || 'bounded_recovery_limit_reached'
                  );
                  return;
                }
                if (decision.action === 'CLICK_SKIP' && skipButton?.isConnected) {
                  pendingAttempt = decision.attempt || 0;
                  reportAction('skip_attempt', pendingAttempt, 'usable_button_click');
                  try {
                    skipButton.click();
                    verifySkip(pendingAttempt);
                    return;
                  } catch (_) {
                    reportAction('skip_result', pendingAttempt, 'click_failed');
                    pendingAttempt = 0;
                  }
                }
                // DOM changes also trigger checks. Probe slowly while a control is unavailable.
                scheduleRetry(skipButton ? 1000 : 4000);
              }

              function onVideoPlaying() {
                if (!activeAd()) lastContentWasPlaying = true;
              }

              function onVideoPaused() {
                if (!activeAd()) lastContentWasPlaying = false;
              }

              function bindVideo(nextVideo) {
                if (video === nextVideo) return;
                video?.removeEventListener('playing', onVideoPlaying);
                video?.removeEventListener('pause', onVideoPaused);
                video?.removeEventListener('ended', onVideoPaused);
                video?.removeEventListener('emptied', onVideoPaused);
                video?.removeEventListener('durationchange', skipActiveAd);
                video = nextVideo;
                lastContentWasPlaying = !!(video && !video.paused && !video.ended && !activeAd());
                video?.addEventListener('playing', onVideoPlaying);
                video?.addEventListener('pause', onVideoPaused);
                video?.addEventListener('ended', onVideoPaused);
                video?.addEventListener('emptied', onVideoPaused);
                video?.addEventListener('durationchange', skipActiveAd);
              }

              function observeYoutubePlayer() {
                if (player && !player.isConnected) {
                  playerObserver?.disconnect();
                  playerObserver = null;
                  player = null;
                  bindVideo(null);
                  contentVideoBeforeAd = null;
                  if (reportedAdActive) {
                    clearTimers();
                    pendingAttempt = 0;
                    getDecision(false, false);
                    reportAdState(false);
                    userInteractedDuringAd = false;
                  }
                }
                if (!player) {
                  player = document.querySelector('.html5-video-player');
                  if (!player) return;
                  playerObserver = new MutationObserver(() => {
                    if (!video || !player.contains(video)) observeYoutubePlayer();
                    else skipActiveAd();
                  });
                  playerObserver.observe(player, {
                    attributes: true,
                    attributeFilter: ['class', 'style', 'disabled', 'aria-disabled', 'aria-hidden', 'hidden', 'aria-label'],
                    childList: true,
                    subtree: true
                  });
                }
                bindVideo(player.querySelector('video'));
                skipActiveAd();
              }

              function stopYoutubeAdGuard() {
                rootObserver?.disconnect();
                playerObserver?.disconnect();
                clearTimers();
                rootObserver = null;
                playerObserver = null;
                player = null;
                bindVideo(null);
                gaveUp = false;
                pendingAttempt = 0;
                lastContentWasPlaying = false;
                contentVideoBeforeAd = null;
                userInteractedDuringAd = false;
                try {
                  bridge.youtubeAdRecoveryDecision(
                    false, false, true, false, true
                  );
                } catch (_) {}
                reportAdState(false);
              }

              function syncYoutubeAdGuard() {
                if (!isEnabled()) {
                  stopYoutubeAdGuard();
                  return;
                }
                if (!rootObserver) {
                  rootObserver = new MutationObserver(() => {
                    if (!player || !player.isConnected) observeYoutubePlayer();
                  });
                  rootObserver.observe(document.documentElement || document, {
                    childList: true,
                    subtree: true
                  });
                }
                observeYoutubePlayer();
              }

              document.addEventListener('pointerdown', onTrustedInteraction, true);
              document.addEventListener('touchstart', onTrustedInteraction, true);
              document.addEventListener('mousedown', onTrustedInteraction, true);
              document.addEventListener('click', onTrustedInteraction, true);
              document.addEventListener('keydown', onTrustedInteraction, true);
              window.__mfYoutubeAdGuardSync = syncYoutubeAdGuard;
              syncYoutubeAdGuard();
            })();
        """

        private const val DOCUMENT_START_SCRIPT =
            COSMETIC_DOCUMENT_START_SCRIPT + YOUTUBE_AD_GUARD_SCRIPT

        fun fallbackScript(): String = DOCUMENT_START_SCRIPT

        private fun installedBridge(webView: WebView): BrowserCosmeticBridge? =
            synchronized(installedBridges) { installedBridges[webView] }

        fun updatePageUrl(webView: WebView, url: String) {
            installedBridge(webView)?.topLevelUrl?.set(url)
        }

        fun beginPage(webView: WebView, url: String) {
            val bridge = installedBridge(webView) ?: return
            bridge.topLevelUrl.set(url)
            // Reloads can keep the URL; same-document history changes must not renew the budget.
            bridge.youtubeAdRecoveryPolicy.reset()
        }

        fun install(webView: WebView, playerScript: String?): Boolean {
            val key = if (!playerScript.isNullOrEmpty() && BrowserAdblockDiagnostics.isEnabledForQa())
                UUID.randomUUID().toString() else null
            val bridge = BrowserCosmeticBridge(webView.url, key)
            webView.addJavascriptInterface(bridge, NAME)
            synchronized(installedBridges) {
                installedBridges[webView] = bridge
            }
            if (key != null && playerScript != null) {
                BrowserYoutubePlayerScriptlet.register(webView, playerScript, key)
            }
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                return false
            }
            return runCatching {
                WebViewCompat.addDocumentStartJavaScript(
                    webView,
                    DOCUMENT_START_SCRIPT,
                    setOf("http://*", "https://*")
                )
                true
            }.getOrDefault(false)
        }
    }
}
