package com.termux.shared.termux;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;

import java.io.File;

/**
 * Maps the hardcoded Termux prefix {@code /data/data/com.termux} used by bootstrap binaries
 * onto this app's real data directory ({@code /data/data/com.termux.work} or
 * {@code /data/user/<id>/com.termux.work} on work profiles).
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
        sPhysicalFilesDir = filesPath;
        if (filesPath.endsWith("/files")) {
            sPhysicalAppDataDir = filesPath.substring(0, filesPath.length() - "/files".length());
        } else {
            sPhysicalAppDataDir = filesDir != null && filesDir.getParent() != null
                ? filesDir.getParent()
                : TermuxConstants.TERMUX_INTERNAL_PRIVATE_APP_DATA_DIR_PATH;
        }
        // Work profile / secondary users: native chdir/exec cannot use /data/data/<pkg>
        // (that path belongs to user 0). Force /data/user/<id>/<pkg>.
        int userId = android.os.Process.myUid() / 100000;
        if (userId != 0) {
            String pkg = context.getPackageName();
            sPhysicalAppDataDir = "/data/user/" + userId + "/" + pkg;
            sPhysicalFilesDir = sPhysicalAppDataDir + "/files";
        } else {
            sPhysicalAppDataDir = sPhysicalAppDataDir.replaceFirst("^/data/user/0/", "/data/data/");
            if (sPhysicalFilesDir.startsWith("/data/user/0/"))
                sPhysicalFilesDir = "/data/data/" + sPhysicalFilesDir.substring("/data/user/0/".length());
        }
        String normalizedPhysical = sPhysicalAppDataDir.replaceFirst("^/data/user/0/", "/data/data/");
        // Remap whenever this is not the official bootstrap prefix (forked applicationId and/or work profile).
        sNeedsRemap = !normalizedPhysical.equals(TermuxConstants.TERMUX_BOOTSTRAP_APP_DATA_DIR_PATH);

        File nativeLib = new File(context.getApplicationInfo().nativeLibraryDir, REMAP_LIBRARY_NAME);
        sRemapLibraryPath = nativeLib.isFile() ? nativeLib.getAbsolutePath() : null;

        sInitialized = true;
        if (sNeedsRemap) {
            Logger.logInfo(LOG_TAG, "Prefix remap enabled: "
                + TermuxConstants.TERMUX_BOOTSTRAP_APP_DATA_DIR_PATH + " -> " + sPhysicalAppDataDir
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

    @NonNull
    public static String toPhysical(@Nullable String logicalPath) {
        if (logicalPath == null)
            return "";
        if (!sNeedsRemap)
            return logicalPath;
        String internal = TermuxConstants.TERMUX_INTERNAL_PRIVATE_APP_DATA_DIR_PATH;
        if (logicalPath.equals(internal) || logicalPath.startsWith(internal + "/")) {
            return sPhysicalAppDataDir + logicalPath.substring(internal.length());
        }
        String bootstrap = TermuxConstants.TERMUX_BOOTSTRAP_APP_DATA_DIR_PATH;
        if (logicalPath.equals(bootstrap) || logicalPath.startsWith(bootstrap + "/")) {
            return sPhysicalAppDataDir + logicalPath.substring(bootstrap.length());
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
        if (!sNeedsRemap)
            return;
        File inPrefix = new File(sPhysicalFilesDir + "/usr/lib/" + REMAP_LIBRARY_NAME);
        String lib = inPrefix.isFile() ? inPrefix.getAbsolutePath() : sRemapLibraryPath;
        if (lib == null)
            return;
        environment.put(ENV_REMAP_FROM, TermuxConstants.TERMUX_BOOTSTRAP_APP_DATA_DIR_PATH);
        environment.put(ENV_REMAP_TO, sPhysicalAppDataDir);
        String existing = environment.get(ENV_LD_PRELOAD);
        if (existing == null || existing.isEmpty())
            environment.put(ENV_LD_PRELOAD, lib);
        else if (!existing.contains(REMAP_LIBRARY_NAME))
            environment.put(ENV_LD_PRELOAD, lib + ":" + existing);
    }
}
