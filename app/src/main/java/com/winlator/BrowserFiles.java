package com.winlator;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.webkit.MimeTypeMap;

import androidx.core.content.FileProvider;

import com.winlator.core.AppUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The files the browser has put in the public Download folder.
 *
 * That folder is the container's D: drive (Container.DEFAULT_DRIVES), which is why downloads go
 * there and nowhere else: an installer fetched in the browser can be run inside Wine without
 * copying it.
 */
public final class BrowserFiles {
    private static final int LIMIT = 200;

    private BrowserFiles() {}

    /** Files in Download, newest first. */
    public static List<File> files() {
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File[] all = dir.listFiles();
        List<File> result = new ArrayList<>();
        if (all == null) return result;

        for (File file : all) {
            if (file.isFile() && !file.getName().startsWith(".")) result.add(file);
        }

        Collections.sort(result, (left, right) -> Long.compare(right.lastModified(), left.lastModified()));
        return result.size() > LIMIT ? new ArrayList<>(result.subList(0, LIMIT)) : result;
    }

    /** Hands a downloaded file to whichever app can open it. */
    public static void open(Activity activity, File file) {
        try {
            Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".FileProvider", file);
            Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mimeFromName(file.getName()))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(intent);
        }
        catch (Exception e) {
            AppUtils.showToast(activity, activity.getString(R.string.browser_open_failed));
        }
    }

    private static String mimeFromName(String name) {
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            String guessed = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.ENGLISH));
            if (guessed != null) return guessed;
        }
        return "application/octet-stream";
    }
}
