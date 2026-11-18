# Android updater core integration

## Build configuration

The integration owner supplies these **String** BuildConfig fields:

```text
UPDATE_FEED_URL = https://api.github.com/repos/moham34643fxx-sudo/mediaflow/releases?per_page=20
UPDATE_REPOSITORY = moham34643fxx-sudo/mediaflow
```

An actual deployed direct-manifest endpoint can replace the feed URL. The core adds/replaces
`channel=qa|stable` for that endpoint. GitHub arrays use published, non-draft releases and
release-owned `mediaflow-update.json`; asset content REST fallback uses octet-stream Accept.
The default channel is `QA_SIGNED_RELEASE ? "qa" : "stable"`. QA requires a prerelease with
a case-insensitive `qa-` tag prefix; stable excludes prereleases and QA tags. Version ordering
is numeric versionCode. There are no tokens or hard-coded signing certificates in the client.

## Manifest entries owned by the integration

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />

<!-- Inside application -->
<receiver android:name=".updates.UpdateInstallReceiver" android:exported="false" />
<receiver android:name=".updates.UpdateReplacedReceiver" android:exported="false">
    <intent-filter>
        <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
    </intent-filter>
</receiver>
```

Both receivers are Hilt Android entry points. No FileProvider, media permission, service,
Room migration, exported install activity, or broad storage access is needed by this core.

## Root / Activity API

Inject the singleton `AppUpdater`. Collect `state: StateFlow<UpdateState>` in the lifecycle-aware
root. Calls return a `Job` except `pause()` and `onBackground()`; UI does not need to await them.

- Call `onForeground(activity)` for every foreground entry. Prefer `onPostResume` or a root
  lifecycle RESUMED effect, so system UI can launch after lifecycle state has become RESUMED.
  This reconciles installed identity/session/permission and performs the cooldown metadata check.
- Call `onBackground(activity)` from pause. Every system UI launch also checks LifecycleOwner
  RESUMED, activity identity, finishing, and destroyed state, including immediately on Main.
- `check(manual = true)` bypasses the six-hour foreground-check cooldown. Metadata discovery
  never requests APK bytes. Automatic checks preserve a paused/retryable transfer.
- `download(installWhenReady = true)` is an explicit user download-and-install request.
  Pass `false` to stop at READY (for example when the user chooses to defer installation).
- `pause()` cancels only the updater request; `download(...)` resumes it. Resuming without
  a strong ETag restarts from byte zero. A successful 200 response to Range truncates the old
  representation. All completed bytes are verified before READY, including recovered full files.
- `install(activity)` explicitly installs/retries a complete APK, or opens the official
  unknown-source permission page. Return to foreground rechecks permission. A denial stays
  WAITING_PERMISSION with INSTALL_PERMISSION; another explicit Install can reopen settings.
- `dismissPrompt()` suppresses the current automatic prompt. `clearError()` clears the error
  and returns an ERROR transfer to PAUSED (or IDLE without a candidate). `retry()` retries
  download/verification or metadata checking; use `install(activity)` for installer retry.

State phases are exactly `IDLE`, `CHECKING`, `AVAILABLE`, `DOWNLOADING`, `PAUSED`, `VERIFYING`,
`READY`, `WAITING_PERMISSION`, `INSTALLING`, `CURRENT`, `NO_RELEASE`, `INSTALLED`, `ERROR`.
`NO_RELEASE` is an empty/unpublished matching channel, **not** CURRENT. Errors are the
`UpdateError` enum. Installer terminal failures return READY with an error, keeping the verified
APK retryable. `SESSION_LOST` also returns READY for explicit restaging. Display notes as plain
text from `release.notes["ar"|"en"]`. `progress` is a 0..1 Float; byte counts are Long.

`automaticPrompt` marks an available automatic discovery. `autoInstall` records explicit
download-and-install consent; `installationPending` marks permission or installer handoff
waiting. No installer Activity is launched in background. The supplied pending-user-action
Intent is held in memory only, never persisted as an arbitrary serialized Intent. An inactive
recovered session is abandoned and its retained APK is offered for explicit retry.

## Isolation and recovery

Only `filesDir/app-updates/{journal.json,payload.apk}` and Android PackageInstaller sessions
are used. The AtomicFile journal is independent of app databases, downloaded media, and player.
New-process recovery validates complete files again; partial files wait for an explicit Resume.
Permission/session state and terminal statuses survive process death. Installed identity and
MY_PACKAGE_REPLACED reconcile success. Installer failures do not uninstall or alter installed data.
The installer receives MODE_FULL_INSTALL, package/size, and API31+ USER_ACTION_REQUIRED. Its
fsynced output stream is closed before commit. Callback PendingIntent is explicit, mutable on
API31+, and reusable for pending and terminal status. Android performs final cryptographic validation.

## Controlled tests

Injection seam:

```kotlin
AppUpdater(context,
    UpdateConfiguration(feedUrl, repository, "qa", storageRoot = isolatedPrivateRoot),
    UpdateHttp(controlledCallFactory)
) { installedIdentityFixture }
```

`UpdateApkVerifier` separately accepts installed identity, SDK, and device ABI overrides.
Production always uses the actual installed PackageManager identity/current signer set.

JVM suites: `com.zdownloader.app.updates.*` (16 focused tests).
Device suites: `com.zdownloader.app.UpdateCoreVerificationTest` and
`com.zdownloader.app.UpdateCoreRecoveryTest`. They use the actual signed installed full APK
as a fixture with an injected older identity, private temporary files, and controlled transfer
interruption/resume. They do not create installer sessions or replace the test host.
The integration owner coordinates their device execution and the separate real A-to-B install.

Normal integrated commands:

```text
gradlew.bat -g C:/gradle-home :app:testDebugUnitTest --tests "com.zdownloader.app.updates.*"
gradlew.bat -g C:/gradle-home :app:compileDebugAndroidTestKotlin
```

Core-only verification during parallel development used an external temporary Gradle init
script to supply the pending BuildConfig fields and compile only this source/test slice with
the actual Android dependency classpath. The focused tests passed and Android test classes
compiled; full-app debug compilation timed out locally and remains an integration check.
