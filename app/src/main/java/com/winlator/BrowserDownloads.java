package com.winlator;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import androidx.core.content.ContextCompat;

import com.winlator.core.AppUtils;

import org.mozilla.geckoview.WebResponse;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Saves a download from the embedded browser into the public Download folder.
 *
 * That folder is not arbitrary: the container's D: drive is mapped to it
 * (see Container.DEFAULT_DRIVES), so anything downloaded here is immediately visible
 * inside the container as D:\ and can be run as an installer.
 *
 * One download at a time. The engine hands us a live stream that has to be drained, so a
 * second concurrent download would interleave two large transfers for no benefit.
 */
public final class BrowserDownloads {
    public static final int REQUEST_STORAGE = 4101;

    private static final AtomicBoolean busy = new AtomicBoolean(false);
    private static WebResponse pending;
    private static String pendingName;

    private BrowserDownloads() {}

    public static void handle(Activity activity, WebResponse response) {
        if (response == null || response.body == null) {
            AppUtils.showToast(activity, activity.getString(R.string.browser_download_failed, "the file"));
            return;
        }

        if (needsPermission() &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pending = response;
            pendingName = fileName(response);
            activity.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE);
            return;
        }

        save(activity, response, fileName(response));
    }

    /** Called back by the activity after the storage prompt closes. */
    public static void onPermissionResult(Activity activity, boolean granted) {
        WebResponse response = pending;
        String name = pendingName;
        pending = null;
        pendingName = null;
        if (response == null) return;

        if (!granted) {
            AppUtils.showToast(activity, activity.getString(R.string.browser_download_denied));
            return;
        }
        save(activity, response, name);
    }

    /**
     * On Android 10+ an app that is not in legacy storage mode has to write through MediaStore.
     * This build targets API 28, so it normally does run in legacy mode, but the check keeps it
     * correct if the target is ever raised.
     */
    private static boolean needsPermission() {
        if (Build.VERSION.SDK_INT < 29) return true;
        return Environment.isExternalStorageLegacy();
    }

    private static boolean useMediaStore() {
        return Build.VERSION.SDK_INT >= 29 && !Environment.isExternalStorageLegacy();
    }

    private static void save(final Activity activity, final WebResponse response, final String name) {
        if (!busy.compareAndSet(false, true)) {
            AppUtils.showToast(activity, activity.getString(R.string.browser_download_busy));
            return;
        }

        final String mime = mimeType(response, name);
        AppUtils.showToast(activity, activity.getString(R.string.browser_download_start, name));

        new Thread(() -> {
            boolean ok = false;
            try {
                InputStream in = response.body;
                Uri mediaUri = null;
                File file = null;
                OutputStream out;

                if (useMediaStore()) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    values.put(MediaStore.Downloads.MIME_TYPE, mime);
                    values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    values.put(MediaStore.Downloads.IS_PENDING, 1);

                    ContentResolver resolver = activity.getContentResolver();
                    mediaUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (mediaUri == null) throw new IllegalStateException("MediaStore refused the insert");
                    out = resolver.openOutputStream(mediaUri);
                    if (out == null) throw new IllegalStateException("MediaStore gave no stream");
                }
                else {
                    File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("no Download folder");
                    file = unique(dir, name);
                    out = new FileOutputStream(file);
                }

                try {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
                    out.flush();
                }
                finally {
                    try { out.close(); } catch (Exception ignored) {}
                    try { in.close(); } catch (Exception ignored) {}
                }

                if (mediaUri != null) {
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.Downloads.IS_PENDING, 0);
                    activity.getContentResolver().update(mediaUri, done, null, null);
                }
                else if (file != null) {
                    MediaScannerConnection.scanFile(activity, new String[]{file.getAbsolutePath()},
                                                    new String[]{mime}, null);
                }
                ok = true;
            }
            catch (Exception e) {
                ok = false;
            }

            final boolean success = ok;
            activity.runOnUiThread(() -> {
                AppUtils.showToast(activity, activity.getString(
                    success ? R.string.browser_download_done : R.string.browser_download_failed, name));
                busy.set(false);
            });
        }, "browser-download").start();
    }

    /** Prefers the server's filename, then the last path segment, then a placeholder. */
    private static String fileName(WebResponse response) {
        String disposition = header(response, "content-disposition");
        String name = dispositionName(disposition);
        if (name == null && response.uri != null) {
            try {
                name = Uri.parse(response.uri).getLastPathSegment();
            }
            catch (Exception ignored) {
            }
        }
        return sanitise(name);
    }

    private static String header(WebResponse response, String wanted) {
        if (response.headers == null) return null;
        for (Map.Entry<String, String> entry : response.headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().toLowerCase(Locale.ENGLISH).equals(wanted)) return entry.getValue();
        }
        return null;
    }

    private static String dispositionName(String value) {
        if (value == null) return null;
        String lower = value.toLowerCase(Locale.ENGLISH);

        int star = lower.indexOf("filename*=");
        if (star >= 0) {
            String part = value.substring(star + 10).trim();
            int quote = part.indexOf("''");
            if (quote >= 0) part = part.substring(quote + 2);
            int end = part.indexOf(';');
            return end >= 0 ? part.substring(0, end).trim() : part;
        }

        int plain = lower.indexOf("filename=");
        if (plain >= 0) {
            String part = value.substring(plain + 9).trim();
            int end = part.indexOf(';');
            if (end >= 0) part = part.substring(0, end).trim();
            if (part.length() > 1 && part.startsWith("\"") && part.endsWith("\"")) part = part.substring(1, part.length() - 1);
            return part;
        }
        return null;
    }

    private static String sanitise(String name) {
        if (name == null) return "download";
        StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            out.append(c < 32 || "/\\:*?\"<>|".indexOf(c) >= 0 ? '_' : c);
        }
        String clean = out.toString().trim();
        while (clean.startsWith(".")) clean = clean.substring(1);
        if (clean.isEmpty()) return "download";
        return clean.length() > 120 ? clean.substring(0, 120) : clean;
    }

    private static String mimeType(WebResponse response, String name) {
        String type = header(response, "content-type");
        if (type != null && !type.isEmpty()) {
            int cut = type.indexOf(';');
            return (cut >= 0 ? type.substring(0, cut) : type).trim();
        }

        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            String extension = name.substring(dot + 1).toLowerCase(Locale.ENGLISH);
            String guessed = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            if (guessed != null) return guessed;
        }
        return "application/octet-stream";
    }

    /** Never overwrites: "setup.exe" becomes "setup (1).exe". */
    private static File unique(File dir, String name) {
        File candidate = new File(dir, name);
        if (!candidate.exists()) return candidate;

        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";

        for (int i = 1; i < 1000; i++) {
            candidate = new File(dir, base + " (" + i + ")" + extension);
            if (!candidate.exists()) return candidate;
        }
        return new File(dir, System.currentTimeMillis() + extension);
    }
}
