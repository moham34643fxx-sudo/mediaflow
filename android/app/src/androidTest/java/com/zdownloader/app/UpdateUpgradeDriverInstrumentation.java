package com.zdownloader.app;

import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Xml;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONObject;
import org.xmlpull.v1.XmlSerializer;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Independent test UID survives target replacement. All installer consent is real system UI. */
public class UpdateUpgradeDriverInstrumentation extends Instrumentation {
    private String appPackage() { return getTargetContext().getPackageName(); }
    private static final Set<String> INSTALLERS = new HashSet<>(Arrays.asList(
        "com.android.packageinstaller", "com.google.android.packageinstaller",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.huawei.android.internal.app", "com.huawei.appmarket", "com.hihonor.appmarket",
        "com.hihonor.packageinstaller", "com.huawei.packageinstaller"));
    private File folder;
    private final JSONObject report = new JSONObject();
    private Bundle arguments;
    private long fromCode;
    private long toCode;
    private boolean restartedAfterScan;
    private final Set<String> scanStages = new LinkedHashSet<>();

    @Override public void onCreate(Bundle args) { super.onCreate(args); arguments = args == null ? new Bundle() : args; start(); }

    @Override public void onStart() {
        int code = 1;
        Bundle result = new Bundle();
        folder = new File(getTargetContext().getExternalFilesDir(null), "update-upgrade-driver");
        folder.mkdirs();
        try {
            report.put("transport", "CONTROLLED; no live GitHub publication").put("targetInstrumented", false);
            fromCode = Long.parseLong(arguments.getString("fromCode", "50"));
            toCode = Long.parseLong(arguments.getString("toCode", "51"));
            String toName = arguments.getString("toName", "0.9.18");
            if (toCode <= fromCode) throw new AssertionError("Only forward upgrades are permitted");
            report.put("fromCode", fromCode).put("toCode", toCode).put("toName", toName)
                .put("installedVersionBefore", version()).put("actualAppInstalledSuccessUi", false)
                .put("officialInstallClicked", false);
            boolean postOnly = "post-upgrade".equals(arguments.getString("scenario"));
            report.put("postUpgradeUiOnly", postOnly);
            if (version() != (postOnly ? toCode : fromCode)) throw new AssertionError("Unexpected installed version");
            if (!(postOnly ? toName : arguments.getString("fromName", "0.9.17")).equals(versionName()))
                throw new AssertionError("Unexpected installed source version name");
            // Every wakeup, including failed unlocks and screenshots, is enclosed by sleep 223.
            if (!postOnly) try {
                wake();
                shell("am start -W -n " + appPackage() + "/.MainActivity");
                navigateToUpdates();
                capture("ready-app-ui");
                // A previous installer consent can be waiting on Play Protect's final
                // successful-scan Install button. Complete that same official flow.
                if (report.optBoolean("playProtectSafeInstallClicked")) {
                    long completedBy = SystemClock.elapsedRealtime() + 12000;
                    while (version() != toCode && SystemClock.elapsedRealtime() < completedBy) SystemClock.sleep(200);
                }
                if (version() != toCode) {
                awaitClick(appPackage(), 15000, "تثبيت التحديث", "Install update");
                long deadline = SystemClock.elapsedRealtime() + 90000;
                boolean permissionHandled = false;
                while (true) {
                    AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
                    String pkg = packageOf(root);
                    if (handlePlayProtectScan(root)) {
                        continue;
                    } else if ("com.android.settings".equals(pkg)) {
                        if (permissionHandled) throw new AssertionError("Unknown-source permission did not persist");
                        capture("unknown-sources-before");
                        String text = allText(root).toLowerCase(Locale.ROOT);
                        if (!text.contains("mediaflow") || !(text.contains("install") || text.contains("تثبيت") || text.contains("مصدر")))
                            throw new AssertionError("Unexpected settings page; refusing unrelated permission action");
                        AccessibilityNodeInfo toggle = findSwitch(root);
                        if (toggle == null) throw new AssertionError("Official unknown-source permission switch unavailable");
                        if (!toggle.isChecked() && !clickNode(toggle)) throw new AssertionError("Permission switch could not be enabled");
                        SystemClock.sleep(400);
                        capture("unknown-sources-after");
                        permissionHandled = true;
                        report.put("unknownSourcesViaRealUi", true);
                        shell("input keyevent 4");
                    } else if (officialInstaller(pkg)) {
                        capture("official-install-confirmation");
                        if (clickExact(root, "Install", "INSTALL", "تثبيت", "التثبيت", "安装", "تحديث", "Update")) {
                            report.put("officialInstallerPackage", pkg).put("officialInstallClicked", true);
                            break;
                        }
                    }
                    if (SystemClock.elapsedRealtime() > deadline)
                        throw new AssertionError("Official installer confirmation unavailable; foreground=" + pkg);
                    SystemClock.sleep(250);
                }
                }
            } finally { shell("input keyevent 223"); }

            // Hashing, staging, replacement and PackageManager polling need no illuminated display.
            long deadline = SystemClock.elapsedRealtime() + 90000;
            long inspectOemAt = SystemClock.elapsedRealtime() + 12000;
            while (version() != toCode && SystemClock.elapsedRealtime() < deadline) {
                if (SystemClock.elapsedRealtime() >= inspectOemAt) {
                    try {
                        wake();
                        AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
                        capture("post-confirmation-oem-ui");
                        handlePlayProtectScan(root);
                    } finally { shell("input keyevent 223"); }
                    inspectOemAt = SystemClock.elapsedRealtime() + 12000;
                }
                SystemClock.sleep(500);
            }
            if (version() != toCode) {
                try {
                    wake();
                    capture("installer-blocker");
                    throw new AssertionError("Code " + toCode + " not installed. Inspect official installer evidence.");
                } finally { shell("input keyevent 223"); }
            }
            report.put("installedVersion", version()).put("installedVersionName", versionName())
                .put("replacementWaitPolicy", "SLEEP_223; bounded normal scan UI checks every 12 seconds");
            if (!toName.equals(versionName())) throw new AssertionError("Unexpected installed target version name");
            try {
                wake();
                if (report.optBoolean("playProtectPromptResolved") && !restartedAfterScan) restartAfterScan();
                else shell("am start -W -n " + appPackage() + "/.MainActivity");
                navigateToUpdates();
                deadline = SystemClock.elapsedRealtime() + 20000;
                while (true) {
                    AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
                    if (handlePlayProtectScan(root)) {
                        restartAfterScan();
                        navigateToUpdates();
                        deadline = SystemClock.elapsedRealtime() + 20000;
                        continue;
                    }
                    String text = allText(root);
                    if (appPackage().equals(packageOf(root)) && (text.contains("تم تثبيت التحديث بنجاح") || text.contains("Update installed successfully"))) break;
                    if (SystemClock.elapsedRealtime() > deadline) throw new AssertionError("Installed success state not visible in actual App updates UI");
                    SystemClock.sleep(250);
                }
                capture("installed-success-app-ui");
                report.put("actualAppInstalledSuccessUi", true);
            } finally { shell("input keyevent 223"); }
            report.put("passed", true);
            code = 0;
        } catch (Throwable failure) {
            try { report.put("passed", false).put("failure", failure.toString()); } catch (Exception ignored) { }
            result.putString("failure", failure.toString());
        } finally {
            try { shell("input keyevent 223"); report.put("finalSleep223Sent", true); }
            catch (Exception failure) { code = 1; result.putString("sleepFailure", failure.toString()); }
            try { report.put("installedVersion", version()).put("installedVersionName", versionName()); }
            catch (Exception failure) { code = 1; result.putString("identityFailure", failure.toString()); }
            try { report.put("passed", code == 0); } catch (Exception ignored) { }
            try { Files.write(new File(folder, "driver.json").toPath(), report.toString(2).getBytes(StandardCharsets.UTF_8)); }
            catch (Exception failure) { code = 1; result.putString("evidenceFailure", failure.toString()); }
        }
        result.putString("upgrade_driver", code == 0 ? "PASS" : "FAIL");
        finish(code, result);
    }

    private void wake() throws Exception {
        shell("input keyevent 224");
        KeyguardManager keyguard = getTargetContext().getSystemService(KeyguardManager.class);
        boolean secure = keyguard.isKeyguardSecure();
        report.put("keyguardSecure", secure);
        if (!secure) shell("wm dismiss-keyguard");
        SystemClock.sleep(350);
        if (!secure && keyguard.isKeyguardLocked()) {
            DisplayMetrics metrics = new DisplayMetrics();
            getTargetContext().getSystemService(WindowManager.class).getDefaultDisplay().getRealMetrics(metrics);
            int x = metrics.widthPixels / 2;
            shell("input swipe " + x + " " + (metrics.heightPixels * 4 / 5) + " " + x + " " + (metrics.heightPixels / 5) + " 350");
            report.put("nonsecureKeyguardSwipe", true);
        }
        long deadline = SystemClock.elapsedRealtime() + (secure ? 60000 : 5000);
        while (keyguard.isKeyguardLocked() && SystemClock.elapsedRealtime() < deadline)
            SystemClock.sleep(200); // The owner may unlock physically; no credential is read or entered.
        if (keyguard.isKeyguardLocked())
            throw new AssertionError(secure ? "UI_BLOCKER: secure keyguard remained locked after owner wait" :
                "UI_BLOCKER: nonsecure keyguard did not dismiss after wm dismiss-keyguard and one swipe");
    }

    /** Normal Google Play Protect consent only: never click a rejection or an install-anyway action. */
    private boolean handlePlayProtectScan(AccessibilityNodeInfo root) throws Exception {
        if (!"com.android.vending".equals(packageOf(root))) return false;
        String text = allText(root).toLowerCase(Locale.ROOT);
        if (!text.contains("mediaflow") || !(text.contains("فحص التطبيق") || text.contains("scan app") || safeScanResult(text))) return false;
        String stage = report.optBoolean("officialInstallClicked") ? "after-consent" : "before-consent";
        if (scanStages.contains(stage)) throw new AssertionError("Play Protect prompt returned after the bounded normal scan attempt: " + stage);
        if (!Arrays.asList(shell("pm list packages -s com.android.vending").split("\\s+")).contains("package:com.android.vending"))
            throw new AssertionError("Play Protect prompt is not from the system Google Play package");
        capture("play-protect-before-scan");
        scanStages.add(stage);
        boolean continuingScan = safeScanResult(text);
        if (continuingScan) confirmSafeScan(root);
        else if (!clickExact(root, "فحص التطبيق", "Scan app", "SCAN APP"))
            throw new AssertionError("MediaFlow Play Protect Scan app action unavailable");
        report.put("playProtectScanClicked", !continuingScan).put("playProtectScanContinuation", continuingScan).put("playProtectPackage", "com.android.vending")
            .put("playProtectPromptResolved", false).put("playProtectScanStages", new org.json.JSONArray(scanStages));
        long deadline = SystemClock.elapsedRealtime() + 60000;
        while (SystemClock.elapsedRealtime() < deadline) {
            root = getUiAutomation().getRootInActiveWindow();
            if ("com.android.vending".equals(packageOf(root)) && safeScanResult(allText(root).toLowerCase(Locale.ROOT)) &&
                    !report.optBoolean("playProtectSafeInstallClicked")) confirmSafeScan(root);
            if (root != null && !"com.android.vending".equals(packageOf(root))) {
                capture("play-protect-scan-resolved");
                report.put("playProtectPromptResolved", true);
                restartedAfterScan = false;
                return true;
            }
            SystemClock.sleep(300);
        }
        capture("play-protect-scan-blocker");
        throw new AssertionError("Normal Play Protect scan did not resolve within 60 seconds");
    }

    private boolean safeScanResult(String text) {
        return text.contains("mediaflow") && (text.contains("هذا التطبيق آمن") || text.contains("looks safe"));
    }

    private void confirmSafeScan(AccessibilityNodeInfo root) throws Exception {
        capture("play-protect-safe-result");
        if (!clickExact(root, "تثبيت", "Install", "INSTALL"))
            throw new AssertionError("Successful Play Protect scan Install action unavailable");
        report.put("playProtectSafeInstallClicked", true).put("officialInstallClicked", true)
            .put("officialInstallerPackage", "com.android.vending");
    }

    private void restartAfterScan() throws Exception {
        if (!restartedAfterScan) {
            shell("am start -W -n " + appPackage() + "/.MainActivity");
            restartedAfterScan = true;
            report.put("mainRestartedAfterScan", true);
        }
    }
    private long version() throws Exception {
        // Shell query avoids package-visibility and PackageManager cache effects in the independent UID.
        java.util.regex.Matcher match = java.util.regex.Pattern.compile("versionCode=(\\d+)").matcher(shell("dumpsys package " + appPackage()));
        if (!match.find()) throw new AssertionError("Installed target identity unavailable");
        return Long.parseLong(match.group(1));
    }
    private String versionName() throws Exception {
        java.util.regex.Matcher match = java.util.regex.Pattern.compile("(?m)^\\s*versionName=([^\\r\\n]+)").matcher(shell("dumpsys package " + appPackage()));
        if (!match.find()) throw new AssertionError("Installed target version name unavailable");
        return match.group(1).trim();
    }
    private boolean officialInstaller(String pkg) {
        if (!INSTALLERS.contains(pkg)) return false;
        try {
            // Shell has an unfiltered package view on API 30+; no broad <queries> is
            // added to either app. -s includes updated preinstalled system packages.
            return Arrays.asList(shell("pm list packages -s " + pkg).split("\\s+")).contains("package:" + pkg);
        } catch (Exception error) { return false; }
    }
    private void navigateToUpdates() throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 25000;
        boolean settingsClicked = false;
        while (SystemClock.elapsedRealtime() < deadline) {
            AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            if (handlePlayProtectScan(root)) {
                restartAfterScan();
                settingsClicked = false;
                deadline = SystemClock.elapsedRealtime() + 25000;
                continue;
            }
            if (appPackage().equals(packageOf(root))) {
                String text = allText(root);
                if (text.contains("الإصدار المثبت:") || text.contains("Installed version:")) return;
                if (clickExact(root, "تحديث التطبيق", "App updates")) { SystemClock.sleep(300); continue; }
                if (!settingsClicked) settingsClicked = clickExact(root, "الإعدادات", "Settings");
                else {
                    AccessibilityNodeInfo scroll = scrollable(root);
                    if (scroll != null) scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                }
            }
            SystemClock.sleep(300);
        }
        capture("navigation-blocker");
        throw new AssertionError("Arabic Settings -> App updates navigation unavailable");
    }
    private void awaitClick(String pkg, long timeout, String... labels) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        do {
            AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            if (pkg.equals(packageOf(root)) && clickExact(root, labels)) return;
            SystemClock.sleep(200);
        } while (SystemClock.elapsedRealtime() < deadline);
        capture("click-blocker");
        throw new AssertionError("Required UI action unavailable: " + Arrays.toString(labels));
    }
    private boolean clickExact(AccessibilityNodeInfo node, String... labels) {
        if (node == null) return false;
        String text = node.getText() == null ? "" : node.getText().toString().trim();
        String desc = node.getContentDescription() == null ? "" : node.getContentDescription().toString().trim();
        for (String label : labels) if ((label.equals(text) || label.equals(desc)) && clickNode(node)) return true;
        for (int i = 0; i < node.getChildCount(); i++) if (clickExact(node.getChild(i), labels)) return true;
        return false;
    }
    private boolean clickNode(AccessibilityNodeInfo node) {
        for (int i = 0; node != null && i < 8; i++, node = node.getParent())
            if (node.isEnabled() && node.isVisibleToUser() && node.isClickable())
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        return false;
    }
    private AccessibilityNodeInfo findSwitch(AccessibilityNodeInfo node) {
        if (node == null) return null;
        String klass = String.valueOf(node.getClassName());
        if (node.isCheckable() && node.isEnabled() && node.isVisibleToUser() && (klass.contains("Switch") || klass.contains("CheckBox"))) return node;
        for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo found = findSwitch(node.getChild(i)); if (found != null) return found; }
        return null;
    }
    private AccessibilityNodeInfo scrollable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isScrollable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo found = scrollable(node.getChild(i)); if (found != null) return found; }
        return null;
    }
    private String packageOf(AccessibilityNodeInfo node) { return node == null ? "" : String.valueOf(node.getPackageName()); }
    private String allText(AccessibilityNodeInfo node) {
        if (node == null) return "";
        StringBuilder text = new StringBuilder().append(node.getText()).append(' ').append(node.getContentDescription()).append('\n');
        for (int i = 0; i < node.getChildCount(); i++) text.append(allText(node.getChild(i)));
        return text.toString();
    }
    private String shell(String command) throws Exception {
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand(command));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
    private void capture(String name) throws Exception {
        Bitmap image = getUiAutomation().takeScreenshot();
        if (image != null) try (OutputStream out = new FileOutputStream(new File(folder, name + ".png"))) {
            image.compress(Bitmap.CompressFormat.PNG, 100, out);
        } finally { image.recycle(); }
        try (OutputStream out = new FileOutputStream(new File(folder, name + ".xml"))) {
            XmlSerializer xml = Xml.newSerializer(); xml.setOutput(out, "UTF-8"); xml.startDocument("UTF-8", true);
            xml.startTag(null, "hierarchy"); writeNode(xml, getUiAutomation().getRootInActiveWindow());
            xml.endTag(null, "hierarchy"); xml.endDocument();
        }
    }
    private void writeNode(XmlSerializer xml, AccessibilityNodeInfo node) throws Exception {
        if (node == null) return;
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
        xml.startTag(null, "node");
        xml.attribute(null, "package", packageOf(node)); xml.attribute(null, "class", String.valueOf(node.getClassName()));
        xml.attribute(null, "text", node.getText() == null ? "" : node.getText().toString());
        xml.attribute(null, "content-desc", node.getContentDescription() == null ? "" : node.getContentDescription().toString());
        xml.attribute(null, "resource-id", String.valueOf(node.getViewIdResourceName()));
        xml.attribute(null, "checked", String.valueOf(node.isChecked())); xml.attribute(null, "bounds", bounds.toShortString());
        for (int i = 0; i < node.getChildCount(); i++) writeNode(xml, node.getChild(i));
        xml.endTag(null, "node");
    }
}
