package com.winlator.core;

import android.content.Context;
import android.os.Build;
import android.os.StatFs;
import android.view.Display;
import android.view.WindowManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Read-only scan of the device the app is running on.
 *
 * Everything here comes from public Android APIs or world-readable /proc and /sys nodes, so it
 * works on an unrooted device and changes nothing. Anything that cannot be read on a given
 * device is reported as "n/a" instead of failing the scan.
 */
public abstract class DeviceScan {
    private static final String NA = "n/a";

    public static String buildReport(Context context) {
        StringBuilder out = new StringBuilder();
        appendDevice(out);
        appendSoC(out);
        appendCPU(out);
        appendMemory(out, context);
        appendGPU(out, context);
        appendDisplay(out, context);
        appendStorage(out, context);
        return out.toString();
    }

    public static void log(String report) {
        for (String line : report.split("\n")) android.util.Log.i("WinlatorDeviceScan", line);
    }

    private static void line(StringBuilder out, String key, String value) {
        out.append(String.format(Locale.ENGLISH, "%-11s: %s\n", key, value == null || value.isEmpty() ? NA : value));
    }

    private static void appendDevice(StringBuilder out) {
        String model = (Build.MANUFACTURER != null ? Build.MANUFACTURER : "")+" "+(Build.MODEL != null ? Build.MODEL : "");
        line(out, "Device", model.trim());
        line(out, "Build", Build.DEVICE+" / "+Build.BOARD+" / "+Build.HARDWARE);
        line(out, "Android", Build.VERSION.RELEASE+" (API "+Build.VERSION.SDK_INT+")");
        line(out, "ABIs", String.join(", ", Build.SUPPORTED_ABIS));
    }

    private static void appendSoC(StringBuilder out) {
        String soc = "";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            String manufacturer = Build.SOC_MANUFACTURER != null ? Build.SOC_MANUFACTURER : "";
            String model = Build.SOC_MODEL != null ? Build.SOC_MODEL : "";
            soc = (manufacturer+" "+model).trim();
        }

        // /proc/cpuinfo carries the marketing name on most SoCs that do not expose it through the SDK.
        String hardware = readProcValue("/proc/cpuinfo", "Hardware");
        String modelName = readProcValue("/proc/cpuinfo", "model name");
        if (soc.isEmpty()) soc = hardware != null ? hardware : (modelName != null ? modelName : "");
        else if (hardware != null && !soc.toLowerCase(Locale.ENGLISH).contains(hardware.toLowerCase(Locale.ENGLISH))) {
            soc = soc+" ("+hardware+")";
        }

        line(out, "SoC", soc);
    }

    private static void appendCPU(StringBuilder out) {
        int cores = Runtime.getRuntime().availableProcessors();
        line(out, "CPU", cores+" cores");

        // Cores are enumerated in cluster order, so equal consecutive maximum clocks form a cluster.
        ArrayList<String> clusters = new ArrayList<>();
        int previousClock = -1;
        int runLength = 0;
        for (int i = 0; i <= cores; i++) {
            int clock = i < cores ? CPUStatus.getMaxClockSpeed(i) : -1;
            if (clock == previousClock) {
                runLength++;
                continue;
            }
            if (previousClock > 0) {
                clusters.add(runLength+" x "+CPUStatus.formatClockSpeed(previousClock));
            }
            else if (previousClock == 0) {
                clusters.add(runLength+" x "+NA);
            }
            previousClock = clock;
            runLength = 1;
        }

        if (clusters.isEmpty()) line(out, "Max clocks", NA);
        else {
            boolean single = clusters.size() == 1;
            line(out, single ? "Max clock" : "Clusters", String.join(", ", clusters));
        }
    }

    private static void appendMemory(StringBuilder out, Context context) {
        String total = NA;
        try {
            android.app.ActivityManager activityManager = (android.app.ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
            android.app.ActivityManager.MemoryInfo memoryInfo = new android.app.ActivityManager.MemoryInfo();
            activityManager.getMemoryInfo(memoryInfo);
            if (memoryInfo.totalMem > 0) total = formatBytes(memoryInfo.totalMem);
        }
        catch (Exception e) {}
        line(out, "RAM total", total);
    }

    private static void appendGPU(StringBuilder out, Context context) {
        // GPUHelper builds a temporary EGL context on a worker thread, so this must not run on the UI thread.
        String vendor = NA, renderer = NA, version = NA, adreno = NA, vulkan = NA;
        try {
            vendor = GPUHelper.glGetVendor(context);
            renderer = GPUHelper.glGetRenderer(context);
            version = GPUHelper.glGetVersion(context);
        }
        catch (Throwable e) {}

        line(out, "GPU vendor", vendor);
        line(out, "GPU", renderer);
        line(out, "OpenGL", version);

        try {
            short modelId = GPUHelper.getAdrenoModelId(context);
            if (modelId > 0) adreno = String.valueOf(modelId);
        }
        catch (Throwable e) {}
        line(out, "Adreno id", adreno);

        try {
            int apiVersion = GPUHelper.vkGetApiVersion();
            if (apiVersion > 0) {
                vulkan = GPUHelper.vkVersionMajor(apiVersion)+"."+GPUHelper.vkVersionMinor(apiVersion)+"."+GPUHelper.vkVersionPatch(apiVersion);
            }
        }
        catch (Throwable e) {}
        line(out, "Vulkan", vulkan);
    }

    private static void appendDisplay(StringBuilder out, Context context) {
        String resolution = NA, refreshRate = NA;
        try {
            WindowManager windowManager = (WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
            Display display = windowManager.getDefaultDisplay();
            Display.Mode mode = display.getMode();
            if (mode != null) {
                float density = context.getResources().getDisplayMetrics().densityDpi;
                resolution = mode.getPhysicalWidth()+"x"+mode.getPhysicalHeight()+" @ "+density+" dpi";
            }
            refreshRate = String.format(Locale.ENGLISH, "%.1f Hz", display.getRefreshRate());
        }
        catch (Throwable e) {}
        line(out, "Display", resolution);
        line(out, "Refresh", refreshRate);
    }

    private static void appendStorage(StringBuilder out, Context context) {
        String storage = NA;
        try {
            File dataDir = context.getFilesDir();
            StatFs statFs = new StatFs(dataDir != null ? dataDir.getAbsolutePath() : "/data");
            long blockSize = statFs.getBlockSizeLong();
            storage = formatBytes(statFs.getAvailableBlocksLong() * blockSize)+" free of "+formatBytes(statFs.getBlockCountLong() * blockSize);
        }
        catch (Throwable e) {}
        line(out, "Storage", storage);
    }

    private static String formatBytes(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024L * 1024L) return String.format(Locale.ENGLISH, "%.0f KB", bytes / 1024.0f);
        if (bytes < 1024L * 1024L * 1024L) return String.format(Locale.ENGLISH, "%.0f MB", bytes / (1024.0f * 1024.0f));
        return String.format(Locale.ENGLISH, "%.1f GB", bytes / (1024.0f * 1024.0f * 1024.0f));
    }

    private static String readProcValue(String path, String key) {
        ArrayList<String> lines = FileUtils.readLines(new File(path));
        for (String line : lines) {
            int separator = line.indexOf(':');
            if (separator <= 0) continue;
            if (line.substring(0, separator).trim().equalsIgnoreCase(key)) {
                return line.substring(separator+1).trim();
            }
        }
        return null;
    }
}
