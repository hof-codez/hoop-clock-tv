package com.hof.hoopclock;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Checks GitHub for a newer build, downloads it, and opens the Android installer. */
public class Updater {
    static final String BASE = "https://github.com/hof-codez/hoop-clock-tv/releases/download/latest/";

    public interface Report { void send(String js); }

    private final Activity act;
    private final Report report;
    volatile JSONObject latest;
    volatile boolean busy = false;

    Updater(Activity a, Report r) { act = a; report = r; }

    long installedCode() {
        try {
            PackageInfo pi = act.getPackageManager().getPackageInfo(act.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) { return 0; }
    }

    String installedName() {
        try { return act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName; }
        catch (Exception e) { return ""; }
    }

    private static HttpURLConnection open(String u) throws Exception {
        String url = u;
        for (int i = 0; i < 6; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000); c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "HoopClockTV");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) { url = c.getHeaderField("Location"); c.disconnect(); continue; }
            if (code != 200) throw new Exception("HTTP " + code);
            return c;
        }
        throw new Exception("too many redirects");
    }

    /** Ask GitHub what the newest build is; tells the page if it is newer than this one. */
    void check(boolean announceNone) {
        new Thread(() -> {
            try {
                HttpURLConnection c = open(BASE + "version.json?t=" + System.currentTimeMillis());
                StringBuilder sb = new StringBuilder();
                try (InputStream in = c.getInputStream()) {
                    byte[] b = new byte[4096]; int n;
                    while ((n = in.read(b)) > 0) sb.append(new String(b, 0, n, "UTF-8"));
                }
                JSONObject v = new JSONObject(sb.toString());
                latest = v;
                long code = v.optLong("versionCode", 0);
                if (code > installedCode()) {
                    report.send("window.hoopUpdateAvailable&&window.hoopUpdateAvailable(" + JSONObject.quote(v.optString("versionName", "")) + ")");
                } else if (announceNone) {
                    report.send("window.hoopUpdateProgress&&window.hoopUpdateProgress(-1," + JSONObject.quote("You have the latest version (" + installedName() + ").") + ")");
                }
            } catch (Exception e) {
                if (announceNone) report.send("window.hoopUpdateProgress&&window.hoopUpdateProgress(-1," + JSONObject.quote("Could not check for updates: " + e.getMessage()) + ")");
            }
        }).start();
    }

    /** Download the new build and hand it to the installer. */
    void install() {
        if (busy) return;
        if (Build.VERSION.SDK_INT >= 26 && !act.getPackageManager().canRequestPackageInstalls()) {
            report.send("window.hoopUpdateProgress&&window.hoopUpdateProgress(-1," + JSONObject.quote("Allow Hoop Clock to install apps on the next screen, then press Back and choose Install update again.") + ")");
            act.runOnUiThread(() -> {
                try {
                    act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + act.getPackageName())));
                } catch (Exception e) {
                    try { act.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS)); } catch (Exception ignored) { }
                }
            });
            return;
        }
        busy = true;
        new Thread(() -> {
            try {
                File dir = new File(act.getCacheDir(), "updates");
                dir.mkdirs();
                File apk = new File(dir, "hoop-clock-tv.apk");
                HttpURLConnection c = open(BASE + "hoop-clock-tv.apk");
                long total = c.getContentLengthLong(), got = 0; int lastPct = -1;
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
                    byte[] b = new byte[1 << 16]; int n;
                    while ((n = in.read(b)) > 0) {
                        out.write(b, 0, n); got += n;
                        int pct = total > 0 ? (int) (got * 100 / total) : 0;
                        if (pct != lastPct) { lastPct = pct; report.send("window.hoopUpdateProgress&&window.hoopUpdateProgress(" + pct + ",'')"); }
                    }
                }
                report.send("window.hoopUpdateProgress&&window.hoopUpdateProgress(100," + JSONObject.quote("Download complete. Confirm the install on the next screen.") + ")");
                Uri uri = FileProvider.getUriForFile(act, act.getPackageName() + ".updates", apk);
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(uri, "application/vnd.android.package-archive");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                act.runOnUiThread(() -> act.startActivity(i));
            } catch (Exception e) {
                report.send("window.hoopUpdateProgress&&window.hoopUpdateProgress(-1," + JSONObject.quote("Update failed: " + e.getMessage()) + ")");
            } finally {
                busy = false;
            }
        }).start();
    }
}
