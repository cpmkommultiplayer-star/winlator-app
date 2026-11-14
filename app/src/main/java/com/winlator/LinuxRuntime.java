package com.winlator;

import android.content.Context;

import com.winlator.core.FileUtils;
import com.winlator.core.StreamUtils;
import com.winlator.core.TarCompressorUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * The Linux ARM userland that Windows binaries will run inside.
 *
 * PRoot fakes chroot and bind mounts without root, so a full Arch Linux ARM tree runs in
 * app storage. box64 is bound in as the x86_64 translator and Wine is bound at /opt/wine.
 * Nothing here touches the existing Wine/Box64 container path; that path keeps working
 * exactly as before while this one is proved out.
 */
public abstract class LinuxRuntime {
    public static final String ROOTFS_URL = "http://os.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz";
    public static final long ROOTFS_BYTES = 829367415L;
    public static final String PROOT_ASSET = "runtime/proot-aarch64";
    public static final String PROOT_SHA256 = "fa10b1a7818c2f5b1dcb5834450570c368c9ecf66d31521509621b95c4538a45";
    public static final String BOX64_ASSET = "runtime/box64-aarch64";
    public static final String BOX64_SHA256 = "33d37281f1326c583af41ea39dfd62720c2756c32c58acf53c248cc9fe249b2c";
    public static final String WINE_URL = "https://github.com/Kron4ek/Wine-Builds/releases/download/11.22/wine-11.22-amd64.tar.xz";

    public interface Progress {
        void onStage(String stage, long done, long total);
    }

    public static File dir(Context context) {
        return new File(context.getFilesDir(), "linux");
    }

    public static File rootfsDir(Context context) {
        return new File(dir(context), "rootfs");
    }

    public static File prootFile(Context context) {
        return new File(dir(context), "proot");
    }

    public static File rootfsArchive(Context context) {
        return new File(dir(context), "rootfs.tar.gz");
    }

    public static File box64File(Context context) {
        return new File(dir(context), "box64");
    }

    public static File wineDir(Context context) {
        return new File(dir(context), "wine");
    }

    public static File wineArchive(Context context) {
        return new File(dir(context), "wine.tar.xz");
    }

    public static boolean isWineInstalled(Context context) {
        return new File(wineDir(context), "bin/wine").isFile();
    }

    public static boolean isInstalled(Context context) {
        return new File(rootfsDir(context), "bin/sh").isFile() && prootFile(context).isFile();
    }

    public static boolean installProot(Context context) {
        File target = prootFile(context);
        if (!dir(context).isDirectory() && !dir(context).mkdirs()) return false;
        if (target.isFile() && target.length() > 0) return true;
        try (InputStream in = context.getAssets().open(PROOT_ASSET);
             OutputStream out = new FileOutputStream(target)) {
            StreamUtils.copy(in, out);
        }
        catch (Exception e) {
            return false;
        }
        FileUtils.chmod(target, 0755);
        return true;
    }

    public static boolean installBox64(Context context) {
        File target = box64File(context);
        if (!dir(context).isDirectory() && !dir(context).mkdirs()) return false;
        if (target.isFile() && target.length() > 0) {
            FileUtils.chmod(target, 0755);
            return true;
        }
        try (InputStream in = context.getAssets().open(BOX64_ASSET);
             OutputStream out = new FileOutputStream(target)) {
            StreamUtils.copy(in, out);
        }
        catch (Exception e) {
            return false;
        }
        FileUtils.chmod(target, 0755);
        return true;
    }

    /** Downloads the x86_64 Wine build that box64 will translate. */
    public static boolean downloadWine(Context context, Progress progress) {
        File target = wineArchive(context);
        if (target.isFile() && target.length() > 1024 * 1024) return true;
        if (!dir(context).isDirectory() && !dir(context).mkdirs()) return false;

        File part = new File(dir(context), "wine.tar.xz.part");
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection)new URL(WINE_URL).openConnection();
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(true);

            long total = connection.getContentLengthLong();
            try (InputStream in = new BufferedInputStream(connection.getInputStream(), StreamUtils.BUFFER_SIZE);
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(part), StreamUtils.BUFFER_SIZE)) {
                byte[] buffer = new byte[StreamUtils.BUFFER_SIZE];
                long done = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    done += read;
                    if (progress != null) progress.onStage("WINE", done, total);
                }
            }

            if (part.length() <= 1024 * 1024) return false;
            if (target.isFile()) target.delete();
            return part.renameTo(target);
        }
        catch (Exception e) {
            return false;
        }
        finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** The tarball holds one top-level directory; flatten it to linux/wine. */
    public static boolean extractWine(Context context, Progress progress) {
        File archive = wineArchive(context);
        if (!archive.isFile()) return false;
        File staging = new File(dir(context), "wine-staging");
        FileUtils.delete(staging);
        if (!staging.mkdirs()) return false;

        long[] counter = {0};
        boolean ok = TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, archive, staging, (file, size) -> {
            counter[0] += size;
            if (progress != null) progress.onStage("WINE", counter[0], archive.length());
            return file;
        });
        if (!ok) return false;

        File[] roots = staging.listFiles();
        if (roots == null || roots.length == 0) return false;
        File source = roots[0];
        for (File candidate : roots) {
            if (new File(candidate, "bin/wine").isFile()) {
                source = candidate;
                break;
            }
        }

        File destination = wineDir(context);
        if (!new File(source, "bin/wine").isFile()) return false;
        FileUtils.delete(destination);
        return source.renameTo(destination);
    }

    public static boolean downloadRootfs(Context context, Progress progress) {
        File target = rootfsArchive(context);
        if (target.isFile() && target.length() == ROOTFS_BYTES) return true;
        if (!dir(context).isDirectory() && !dir(context).mkdirs()) return false;

        File part = new File(dir(context), "rootfs.tar.gz.part");
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection)new URL(ROOTFS_URL).openConnection();
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(true);

            long total = connection.getContentLengthLong();
            if (total <= 0) total = ROOTFS_BYTES;

            try (InputStream in = new BufferedInputStream(connection.getInputStream(), StreamUtils.BUFFER_SIZE);
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(part), StreamUtils.BUFFER_SIZE)) {
                byte[] buffer = new byte[StreamUtils.BUFFER_SIZE];
                long done = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    done += read;
                    if (progress != null) progress.onStage("DOWNLOAD", done, total);
                }
            }

            if (part.length() <= 0) return false;
            if (target.isFile()) target.delete();
            return part.renameTo(target);
        }
        catch (Exception e) {
            return false;
        }
        finally {
            if (connection != null) connection.disconnect();
        }
    }

    public static boolean extractRootfs(Context context, Progress progress) {
        File archive = rootfsArchive(context);
        if (!archive.isFile()) return false;
        File destination = rootfsDir(context);
        if (!destination.isDirectory() && !destination.mkdirs()) return false;

        long[] counter = {0};
        boolean ok = TarCompressorUtils.extract(TarCompressorUtils.Type.GZIP, archive, destination, (file, size) -> {
            counter[0] += size;
            if (progress != null) progress.onStage("EXTRACT", counter[0], archive.length());
            return file;
        });

        if (ok && progress != null) progress.onStage("EXTRACT", archive.length(), archive.length());
        return ok;
    }

    /** Runs one command inside the Linux tree and returns its combined output. */
    public static String run(Context context, String command) {
        StringBuilder output = new StringBuilder();
        if (!isInstalled(context)) return "runtime not installed";

        File tmpDir = new File(dir(context), "tmp");
        tmpDir.mkdirs();

        List<String> args = new ArrayList<>();
        args.add(prootFile(context).getPath());
        args.add("--link2symlink");
        args.add("-0");
        args.add("-r");
        args.add(rootfsDir(context).getPath());
        args.add("-b");
        args.add("/dev");
        args.add("-b");
        args.add("/proc");
        args.add("-b");
        args.add("/sys");
        args.add("-w");
        args.add("/root");

        File box64 = box64File(context);
        if (box64.isFile()) {
            args.add("-b");
            args.add(box64.getPath()+":/usr/local/bin/box64");
        }
        File wine = wineDir(context);
        if (wine.isDirectory()) {
            args.add("-b");
            args.add(wine.getPath()+":/opt/wine");
        }

        args.add("/usr/bin/env");
        args.add("-i");
        args.add("HOME=/root");
        args.add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        args.add("TERM=xterm");
        args.add("/bin/sh");
        args.add("-c");
        args.add(command);

        try {
            ProcessBuilder builder = new ProcessBuilder(args);
            builder.redirectErrorStream(true);
            builder.environment().put("PROOT_TMP_DIR", tmpDir.getPath());
            builder.environment().put("PROOT_NO_SECCOMP", "1");

            Process process = builder.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                    if (output.length() > 20000) break;
                }
            }
            process.waitFor();
        }
        catch (Exception e) {
            output.append("ERROR: ").append(e);
        }

        return output.toString();
    }
}
