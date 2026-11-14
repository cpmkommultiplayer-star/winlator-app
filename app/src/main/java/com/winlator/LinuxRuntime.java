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
 * Milestone 1 of the FEX/PRoot path: a real Linux ARM userland on the device.
 *
 * PRoot fakes chroot and bind mounts without root, so a full Arch Linux ARM tree can run
 * inside app storage. Nothing here touches the existing Wine/Box64 container path; this
 * runtime is installed and tested separately until it can host FEX and Wine.
 *
 * proot is the published static aarch64 binary (GPL-2.0-or-later), shipped unmodified
 * with its licence in NOTICE. The rootfs is downloaded from archlinuxarm.org on the
 * device; no Linux distribution files are bundled in this APK.
 */
public abstract class LinuxRuntime {
    public static final String ROOTFS_URL = "http://os.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz";
    public static final long ROOTFS_BYTES = 829367415L;
    public static final String PROOT_ASSET = "runtime/proot-aarch64";
    public static final String PROOT_SHA256 = "fa10b1a7818c2f5b1dcb5834450570c368c9ecf66d31521509621b95c4538a45";

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
