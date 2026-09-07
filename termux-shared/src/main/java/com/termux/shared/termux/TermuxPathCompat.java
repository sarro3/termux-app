package com.termux.shared.termux;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Maps the hardcoded Termux prefix {@code /data/data/com.termux} used by bootstrap binaries
 * onto this app's real data directory.
 * <p>
 * Work-profile as the "main" user: applicationId {@code tx.work} is 7 chars so
 * {@code /data/user/&lt;NN&gt;/tx.work} is the same length as {@code /data/data/com.termux}
 * (21). Bootstrap ELF strings can be patched in place. Extra slashes pad shorter paths
 * (primary user) because the kernel collapses {@code //}.
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
    /** Same-length stand-in for {@link TermuxConstants#TERMUX_BOOTSTRAP_APP_DATA_DIR_PATH}. */
    private static String sPaddedAppDataDir = TermuxConstants.TERMUX_BOOTSTRAP_APP_DATA_DIR_PATH;
    private static String sRemapLibraryPath;
    private static boolean sNeedsRemap;
    private static boolean sCanPatchInPlace;

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
        sPaddedAppDataDir = sameLengthAlias(sPhysicalAppDataDir, TermuxConstants.TERMUX_BOOTSTRAP_APP_DATA_DIR_PATH);
        sCanPatchInPlace = sPaddedAppDataDir != null
            && sPaddedAppDataDir.length() == TermuxConstants.TERMUX_BOOTSTRAP_APP_DATA_DIR_PATH.length();
        if (sPaddedAppDataDir == null)
            sPaddedAppDataDir = sPhysicalAppDataDir;

        String normalizedPhysical = sPhysicalAppDataDir.replaceFirst("^/data/user/0/", "/data/data/");
        sNeedsRemap = !normalizedPhysical.equals(TermuxConstants.TERMUX_BOOTSTRAP_APP_DATA_DIR_PATH);

        File nativeLib = new File(context.getApplicationInfo().nativeLibraryDir, REMAP_LIBRARY_NAME);
        sRemapLibraryPath = nativeLib.isFile() ? nativeLib.getAbsolutePath() : null;

        sInitialized = true;
        if (sNeedsRemap) {
            Logger.logInfo(LOG_TAG, "Prefix remap: "
                + TermuxConstants.TERMUX_BOOTSTRAP_APP_DATA_DIR_PATH + " -> physical " + sPhysicalAppDataDir
                + " padded " + sPaddedAppDataDir
                + (sCanPatchInPlace ? " (in-place ELF patch)" : " (LD_PRELOAD only)"));
        }
    }

    /**
     * Insert extra {@code /} so {@code physical} is the same length as {@code logical}.
     * Returns null if physical is longer (cannot patch ELF in place).
     */
    @Nullable
    public static String sameLengthAlias(@NonNull String physical, @NonNull String logical) {
        if (physical.length() == logical.length())
            return physical;
        if (physical.length() > logical.length())
            return null;
        int extra = logical.length() - physical.length();
        int insertAt = physical.startsWith("/data") ? 5 : 1;
        StringBuilder sb = new StringBuilder(physical);
        for (int i = 0; i < extra; i++)
            sb.insert(insertAt, '/');
        return sb.toString();
    }

    public static boolean isInitialized() {
        return sInitialized;
    }

    public static boolean needsRemap() {
        return sNeedsRemap;
    }

    public static boolean canPatchInPlace() {
        return sCanPatchInPlace;
    }

    @NonNull
    public static String getPhysicalAppDataDir() {
        return sPhysicalAppDataDir;
    }

    @NonNull
    public static String getPaddedAppDataDir() {
        return sPaddedAppDataDir;
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

    /** Replace bootstrap prefix bytes in every file under {@code dir} (ELF and text). */
    public static int patchBootstrapPrefixInTree(@Nullable File dir) {
        if (dir == null || !sCanPatchInPlace)
            return 0;
        byte[] from = TermuxConstants.TERMUX_BOOTSTRAP_APP_DATA_DIR_PATH.getBytes(StandardCharsets.US_ASCII);
        byte[] to = sPaddedAppDataDir.getBytes(StandardCharsets.US_ASCII);
        if (from.length != to.length)
            return 0;
        return patchTree(dir, from, to);
    }

    private static int patchTree(File dir, byte[] from, byte[] to) {
        File[] children = dir.listFiles();
        if (children == null)
            return 0;
        int n = 0;
        for (File child : children) {
            if (child.isDirectory()) {
                n += patchTree(child, from, to);
                continue;
            }
            if (!child.isFile() || child.length() < from.length || child.length() > 64L * 1024 * 1024)
                continue;
            try {
                if (patchFile(child, from, to))
                    n++;
            } catch (Exception ignored) {
            }
        }
        return n;
    }

    private static boolean patchFile(File file, byte[] from, byte[] to) throws java.io.IOException {
        byte[] data;
        try (FileInputStream in = new FileInputStream(file)) {
            data = new byte[(int) file.length()];
            int off = 0;
            while (off < data.length) {
                int r = in.read(data, off, data.length - off);
                if (r < 0)
                    break;
                off += r;
            }
        }
        boolean changed = false;
        int i = 0;
        while (i <= data.length - from.length) {
            if (matchAt(data, i, from)) {
                int after = i + from.length;
                if (after == data.length || data[after] == 0 || data[after] == '/') {
                    System.arraycopy(to, 0, data, i, to.length);
                    changed = true;
                    i += to.length;
                    continue;
                }
            }
            i++;
        }
        if (!changed)
            return false;
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            out.write(data);
        }
        return true;
    }

    private static boolean matchAt(byte[] data, int off, byte[] needle) {
        for (int j = 0; j < needle.length; j++) {
            if (data[off + j] != needle[j])
                return false;
        }
        return true;
    }

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
