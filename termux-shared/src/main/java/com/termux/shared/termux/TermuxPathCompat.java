package com.termux.shared.termux;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;

import java.io.File;

/**
 * Maps the hardcoded Termux prefix {@code /data/data/com.termux} used by bootstrap binaries
 * onto the real app-data directory for the current Android user/profile
 * ({@code /data/user/<id>/com.termux} on work profiles and secondary users).
 */
public final class TermuxPathCompat {

    private static final String LOG_TAG = "TermuxPathCompat";

    public static final String ENV_REMAP_FROM = "TERMUX_PREFIX_REMAP_FROM";
    public static final String ENV_REMAP_TO = "TERMUX_PREFIX_REMAP_TO";
    public static final String ENV_LD_PRELOAD = "LD_PRELOAD";
    public static final String REMAP_LIBRARY_NAME = "libtermux-prefix-remap.so";

    private static volatile boolean sInitialized;
    private static String sPhysicalAppDataDir = TermuxConstants.TERMUX_INTERNAL_PRIVATE_APP_DATA_DIR_PATH;
    private static String sPhysicalFilesDir = TermuxConstants.TERMUX_FILES_DIR_PATH;
    private static String sRemapLibraryPath;
    private static boolean sNeedsRemap;

    private TermuxPathCompat() {}

    public synchronized static void init(@NonNull Context context) {
        File filesDir = context.getFilesDir();
        String filesPath = filesDir != null ? filesDir.getAbsolutePath() : TermuxConstants.TERMUX_FILES_DIR_PATH;
        // Primary user: /data/user/0/... is equivalent to /data/data/...
        String normalized = filesPath.replaceFirst("^/data/user/0/", "/data/data/");
        sPhysicalFilesDir = filesPath;
        if (normalized.endsWith("/files")) {
            sPhysicalAppDataDir = filesPath.substring(0, filesPath.length() - "/files".length());
        } else {
            sPhysicalAppDataDir = filesDir != null && filesDir.getParent() != null
                ? filesDir.getParent()
                : TermuxConstants.TERMUX_INTERNAL_PRIVATE_APP_DATA_DIR_PATH;
        }
        sNeedsRemap = !normalized.equals(TermuxConstants.TERMUX_FILES_DIR_PATH);

        File nativeLib = new File(context.getApplicationInfo().nativeLibraryDir, REMAP_LIBRARY_NAME);
        sRemapLibraryPath = nativeLib.isFile() ? nativeLib.getAbsolutePath() : null;

        sInitialized = true;
        if (sNeedsRemap) {
            Logger.logInfo(LOG_TAG, "Work profile / secondary user prefix remap enabled: "
                + TermuxConstants.TERMUX_INTERNAL_PRIVATE_APP_DATA_DIR_PATH + " -> " + sPhysicalAppDataDir
                + (sRemapLibraryPath != null ? " (preload " + sRemapLibraryPath + ")" : " (preload library missing)"));
        }
    }

    public static boolean isInitialized() {
        return sInitialized;
    }

    public static boolean needsRemap() {
        return sNeedsRemap;
    }

    @NonNull
    public static String getPhysicalAppDataDir() {
        return sPhysicalAppDataDir;
    }

    @NonNull
    public static String getPhysicalFilesDir() {
        return sPhysicalFilesDir;
    }

    /**
     * Convert a logical Termux path ({@code /data/data/com.termux/...}) to the physical
     * path for the current user. Paths that are not under the logical prefix are returned as-is.
     */
    @NonNull
    public static String toPhysical(@Nullable String logicalPath) {
        if (logicalPath == null)
            return "";
        if (!sNeedsRemap)
            return logicalPath;
        String logical = TermuxConstants.TERMUX_INTERNAL_PRIVATE_APP_DATA_DIR_PATH;
        if (logicalPath.equals(logical) || logicalPath.startsWith(logical + "/")) {
            return sPhysicalAppDataDir + logicalPath.substring(logical.length());
        }
        return logicalPath;
    }

    @NonNull
    public static File toPhysicalFile(@NonNull File logicalFile) {
        return new File(toPhysical(logicalFile.getAbsolutePath()));
    }

    @Nullable
    public static String getRemapLibraryPath() {
        return sRemapLibraryPath;
    }

    /** Put remap environment variables used by libtermux-prefix-remap.so into {@code environment}. */
    public static void putRemapEnvironment(@NonNull java.util.Map<String, String> environment) {
        if (!sNeedsRemap || sRemapLibraryPath == null)
            return;
        environment.put(ENV_REMAP_FROM, TermuxConstants.TERMUX_INTERNAL_PRIVATE_APP_DATA_DIR_PATH);
        environment.put(ENV_REMAP_TO, sPhysicalAppDataDir);
        String existing = environment.get(ENV_LD_PRELOAD);
        if (existing == null || existing.isEmpty())
            environment.put(ENV_LD_PRELOAD, sRemapLibraryPath);
        else if (!existing.contains(REMAP_LIBRARY_NAME))
            environment.put(ENV_LD_PRELOAD, sRemapLibraryPath + ":" + existing);
    }
}
