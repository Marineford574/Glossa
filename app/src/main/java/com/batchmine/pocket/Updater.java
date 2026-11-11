package com.batchmine.pocket;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import com.batchmine.pocket.core.Json;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

/**
 * Updates the app from the "latest" release of its GitHub project: finds out whether a newer build is there (the release
 * text says "build N"), downloads the file and hands it to Android's installer. The new version installs over the old one,
 * so words, books and dictionaries stay.
 */
public final class Updater {
    private Updater() { }

    public static final class Found {
        public int build; public String url; public String notes; public String repo;
    }

    /** The project the app was built in (set by the build on GitHub), or what was typed in Settings. */
    public static String repo(App app) {
        String typed = app.prefs().getString("update_repo", "").trim();
        if (typed.matches("[\\w.-]+/[\\w.-]+")) return typed;
        String baked = app.getString(R.string.update_repo).trim();
        return baked.matches("[\\w.-]+/[\\w.-]+") ? baked : "";
    }

    public static int myBuild(App app) {
        try { return app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionCode; } catch (Exception e) { return 0; }
    }

    public static String myName(App app) {
        try { return app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }

    /** Null: nothing newer. Throws IOException when GitHub cannot be reached. */
    public static Found check(App app) throws IOException {
        String repo = repo(app);
        if (repo.isEmpty()) throw new IOException("The GitHub project is not known: type it in Settings → Updates (like yourname/glossa)");
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.github.com/repos/" + repo + "/releases/tags/latest").openConnection();
        c.setConnectTimeout(8000); c.setReadTimeout(10000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "GlossaPocket");
        int code = c.getResponseCode();
        if (code == 404) throw new IOException("No release found in " + repo + " (is the project public, and has a build finished?)");
        if (code != 200) throw new IOException("GitHub answered " + code);
        String body;
        try (InputStream in = c.getInputStream()) { body = new String(readAll(in), java.nio.charset.StandardCharsets.UTF_8); }
        Map<String, Object> rel = Json.obj(Json.parse(body));
        Found f = new Found();
        f.repo = repo;
        f.notes = Json.str(rel.get("body"), "");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("build (\\d+)").matcher(f.notes);
        if (!m.find()) throw new IOException("The release has no build number");
        f.build = Integer.parseInt(m.group(1));
        for (Object a : Json.arr(rel.get("assets"))) {
            Map<String, Object> as = Json.obj(a);
            if (Json.str(as.get("name"), "").endsWith(".apk")) f.url = Json.str(as.get("browser_download_url"), null);
        }
        if (f.url == null) throw new IOException("The release has no app file");
        return f.build > myBuild(app) ? f : null;
    }

    /** Downloads the file (progress 0..1 on the calling thread) and returns it. */
    public static File download(App app, Found f, java.util.function.DoubleConsumer progress) throws IOException {
        File dir = new File(app.getCacheDir(), "update");
        dir.mkdirs();
        File out = new File(dir, "GlossaPocket-" + f.build + ".apk");
        File part = new File(dir, "part");
        HttpURLConnection c = (HttpURLConnection) new URL(f.url).openConnection();
        c.setConnectTimeout(10000); c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "GlossaPocket");
        c.setInstanceFollowRedirects(true);
        if (c.getResponseCode() != 200) throw new IOException("Download failed (" + c.getResponseCode() + ")");
        long total = c.getContentLengthLong(), got = 0;
        try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(part)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) { os.write(buf, 0, n); got += n; if (total > 0) progress.accept((double) got / total); }
        }
        if (part.length() < 100000) { part.delete(); throw new IOException("The downloaded file is too small to be the app"); }
        if (out.exists()) out.delete();
        if (!part.renameTo(out)) throw new IOException("Could not save the file");
        return out;
    }

    /** Opens Android's installer for the file. False: the phone first needs the "install apps from this app" switch. */
    public static boolean install(Activity a, File apk) {
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
            return false;
        }
        Uri u = new Uri.Builder().scheme("content").authority(FilesProvider.authority(a)).appendPath("update").appendPath(apk.getName()).build();
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        a.startActivity(i);
        return true;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }
}
