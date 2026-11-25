import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { runInNewContext } from 'node:vm';

const bridgeFile = new URL(
  '../../main/java/com/zdownloader/app/adblock/BrowserCosmeticBridge.kt',
  import.meta.url
);
const script = readFileSync(bridgeFile, 'utf8')
  .match(/private const val YOUTUBE_AD_GUARD_SCRIPT = """([\s\S]*?)"""/)[1];

function media(paused) {
  const target = new EventTarget();
  return Object.assign(target, {
    paused,
    ended: false,
    playCount: 0,
    play() { this.playCount++; }
  });
}

// Execute the shipped JS against a minimal player/bridge, not a copy of the JS.
// The real Kotlin recovery policy is exercised by the JVM suite.
function setup({ preRoll = false, candidate = null, hitTarget = candidate } = {}) {
  const callbacks = new Map();
  const decisions = [];
  const timers = new Map();
  const player = {
    firstVideo: media(!preRoll),
    adShowing: preRoll,
    isConnected: true,
    classList: { contains: name => name === 'ad-showing' && player.adShowing },
    querySelector: () => player.firstVideo,
    querySelectorAll: () => candidate ? [candidate] : [],
    contains: child => child === player.firstVideo || child === candidate || child === hitTarget
  };
  const document = {
    visibilityState: 'visible',
    documentElement: {},
    querySelector: () => player,
    addEventListener() {},
    elementFromPoint: () => hitTarget
  };
  const window = {
    innerWidth: 800,
    innerHeight: 600,
    MediaFlowAdblockNative: {
      youtubeAdGuardEnabled: () => true,
      setYoutubeAdActive() {},
      reportYoutubeAdAction() {},
      youtubeAdRecoveryDecision(active, wasPlaying, paused, usable) {
        decisions.push({ active, wasPlaying, paused, usable });
        const action = !active && wasPlaying && paused ? 'RESUME'
          : active && usable ? 'CLICK_SKIP' : 'WAIT';
        return JSON.stringify({ action, attempt: 1 });
      }
    }
  };
  window.self = window.top = window;
  let now = 0;
  let nextTimer = 0;
  runInNewContext(script, {
    window, document,
    location: { hostname: 'm.youtube.com' },
    MutationObserver: class {
      constructor(callback) { this.callback = callback; }
      observe(node) { callbacks.set(node, this.callback); }
      disconnect() {}
    },
    Date: { now: () => now },
    performance: { now: () => now },
    setTimeout: (callback, delay) => {
      timers.set(++nextTimer, { due: now + delay, callback });
      return nextTimer;
    },
    clearTimeout: id => timers.delete(id),
    getComputedStyle: element => ({ ...element.style, opacity: element.style?.opacity ?? '1' })
  });
  return {
    player, decisions, document,
    advance(elapsed) {
      now += elapsed;
      for (const [id, timer] of [...timers]) {
        if (timer.due <= now && timers.delete(id)) timer.callback();
      }
    },
    changed(elapsed = 1_000) { now += elapsed; callbacks.get(player)(); }
  };
}

test('a running pre-roll is not evidence that content was playing', () => {
  const fixture = setup({ preRoll: true });
  assert.equal(fixture.decisions[0].wasPlaying, false);
  fixture.player.adShowing = false;
  fixture.player.firstVideo.paused = true;
  fixture.changed();
  assert.equal(fixture.player.firstVideo.playCount, 0);
});

test('paused content may resume only on the observed, still attached video', () => {
  const fixture = setup();
  const original = fixture.player.firstVideo;
  original.paused = false;
  original.dispatchEvent(new Event('playing'));
  fixture.player.adShowing = true;
  original.paused = true;
  fixture.changed();
  fixture.player.adShowing = false;
  fixture.changed();
  assert.equal(original.playCount, 1);
});

test('replacing a video inside the same player cannot resume either video', () => {
  const fixture = setup();
  const original = fixture.player.firstVideo;
  original.paused = false;
  original.dispatchEvent(new Event('playing'));
  fixture.player.adShowing = true;
  original.paused = true;
  fixture.changed();
  fixture.player.firstVideo = media(true);
  fixture.player.adShowing = false;
  fixture.changed();
  assert.equal(original.playCount, 0);
  assert.equal(fixture.player.firstVideo.playCount, 0);
});

function skipButton(parent = null) {
  const rect = { left: 20, top: 20, width: 80, height: 30 };
  return {
    isConnected: true,
    parentElement: parent,
    getAttribute: () => null,
    contains(child) { return child === this; },
    closest() { return this; },
    getBoundingClientRect: () => rect,
    getClientRects: () => [rect],
    clicks: 0,
    click() { this.clicks++; }
  };
}

test('a hit-testable Skip button under an invisible parent is not clicked', () => {
  const parent = { style: { opacity: '0' }, getAttribute: () => null };
  const button = skipButton(parent);
  const fixture = setup({ preRoll: true, candidate: button });
  assert.equal(button.clicks, 0);
  parent.style.opacity = '1';
  fixture.changed();
  assert.equal(button.clicks, 1);
});

test('a late enabled control cannot be clicked in a background document', () => {
  const parent = { style: { opacity: '0' }, getAttribute: () => null };
  const button = skipButton(parent);
  const fixture = setup({ preRoll: true, candidate: button });
  fixture.document.visibilityState = 'hidden';
  parent.style.opacity = '1';
  fixture.changed(92_000);
  assert.equal(button.clicks, 0);
  fixture.document.visibilityState = 'visible';
  fixture.changed();
  assert.equal(button.clicks, 1);
});

test('passive waiting probes less often but reacts to the first visible control', () => {
  const parent = { style: { opacity: '0' }, getAttribute: () => null };
  const button = skipButton(parent);
  const fixture = setup({ preRoll: true, candidate: button });
  fixture.advance(1_000);
  assert.equal(fixture.decisions.length, 1);
  parent.style.opacity = '1';
  fixture.changed(0);
  assert.equal(button.clicks, 1);
});

test('a recognized Skip wrapper delegates to the hit button that owns the handler', () => {
  const wrapper = skipButton();
  const button = skipButton(wrapper);
  wrapper.contains = child => child === wrapper || child === button;
  setup({ preRoll: true, candidate: wrapper, hitTarget: button });
  assert.equal(button.clicks, 1);
  assert.equal(wrapper.clicks, 0);
});
