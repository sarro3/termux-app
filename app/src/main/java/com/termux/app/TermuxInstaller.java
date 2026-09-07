package com.termux.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.system.Os;
import android.util.Pair;
import android.view.WindowManager;

import com.termux.R;
import com.termux.shared.file.FileUtils;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.file.TermuxFileUtils;
import com.termux.shared.interact.MessageDialogUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.markdown.MarkdownUtils;
import com.termux.shared.errors.Error;
import com.termux.shared.android.PackageUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.termux.shared.termux.TermuxPathCompat;

import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH;

/**
 * Install the Termux bootstrap packages if necessary by following the below steps:
 * <p/>
 * (1) If $PREFIX already exist, assume that it is correct and be done. Note that this relies on that we do not create a
 * broken $PREFIX directory below.
 * <p/>
 * (2) A progress dialog is shown with "Installing..." message and a spinner.
 * <p/>
 * (3) A staging directory, $STAGING_PREFIX, is cleared if left over from broken installation below.
 * <p/>
 * (4) The zip file is loaded from a shared library.
 * <p/>
 * (5) The zip, containing entries relative to the $PREFIX, is is downloaded and extracted by a zip input stream
 * continuously encountering zip file entries:
 * <p/>
 * (5.1) If the zip entry encountered is SYMLINKS.txt, go through it and remember all symlinks to setup.
 * <p/>
 * (5.2) For every other zip entry, extract it into $STAGING_PREFIX and set execute permissions if necessary.
 */
final class TermuxInstaller {

    private static final String LOG_TAG = "TermuxInstaller";

    /** Performs bootstrap setup if necessary. */
    static void setupBootstrapIfNeeded(final Activity activity, final Runnable whenDone) {
        String bootstrapErrorMessage;
        Error filesDirectoryAccessibleError;

        // This will also call Context.getFilesDir(), which should ensure that termux files directory
        // is created if it does not already exist
        filesDirectoryAccessibleError = TermuxFileUtils.isTermuxFilesDirectoryAccessible(activity, true, true);
        boolean isFilesDirectoryAccessible = filesDirectoryAccessibleError == null;

        // Work profile / secondary users store app data under /data/user/<id>/ instead of
        // /data/data/. TermuxPathCompat remaps the hardcoded $PREFIX used by bootstrap binaries.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !PackageUtils.isCurrentUserThePrimaryUser(activity)) {
            Logger.logInfo(LOG_TAG, "Running as a secondary user or work profile; enabling prefix remapping to "
                + TermuxPathCompat.getPhysicalAppDataDir());
        }

        final String prefixDirPath = TermuxPathCompat.toPhysical(TERMUX_PREFIX_DIR_PATH);
        final String stagingPrefixDirPath = TermuxPathCompat.toPhysical(TERMUX_STAGING_PREFIX_DIR_PATH);
        final File prefixDir = TermuxPathCompat.toPhysicalFile(TERMUX_PREFIX_DIR);
        final File stagingPrefixDir = TermuxPathCompat.toPhysicalFile(TERMUX_STAGING_PREFIX_DIR);

        if (!isFilesDirectoryAccessible) {
            bootstrapErrorMessage = Error.getMinimalErrorString(filesDirectoryAccessibleError);
            //noinspection SdCardPath
            if (PackageUtils.isAppInstalledOnExternalStorage(activity) &&
                !TermuxConstants.TERMUX_FILES_DIR_PATH.equals(activity.getFilesDir().getAbsolutePath().replaceAll("^/data/user/0/", "/data/data/"))) {
                bootstrapErrorMessage += "\n\n" + activity.getString(R.string.bootstrap_error_installed_on_portable_sd,
                    MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            }

            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.showMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage, null);
            return;
        }

        // If prefix directory exists, even if its a symlink to a valid directory and symlink is not broken/dangling
        if (FileUtils.directoryFileExists(prefixDirPath, true)) {
            if (TermuxFileUtils.isTermuxPrefixDirectoryEmpty()) {
                Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + prefixDirPath + "\" exists but is empty or only contains specific unimportant files.");
            } else {
                if (TermuxPathCompat.needsRemap()) {
                    Logger.logInfo(LOG_TAG, "Rewriting hardcoded bootstrap prefix under existing prefix dir.");
                    rewriteHardcodedPrefixUnder(prefixDir);
                    installWorkProfileGlue(activity, prefixDir);
                }
                whenDone.run();
                return;
            }
        } else if (FileUtils.fileExists(prefixDirPath, false)) {
            Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + prefixDirPath + "\" does not exist but another file exists at its destination.");
        }

        final ProgressDialog progress = ProgressDialog.show(activity, null, activity.getString(R.string.bootstrap_installer_body), true, false);
        new Thread() {
            @Override
            public void run() {
                try {
                    Logger.logInfo(LOG_TAG, "Installing " + TermuxConstants.TERMUX_APP_NAME + " bootstrap packages.");

                    Error error;

                    // Delete prefix staging directory or any file at its destination
                    error = FileUtils.deleteFile("termux prefix staging directory", stagingPrefixDirPath, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Delete prefix directory or any file at its destination
                    error = FileUtils.deleteFile("termux prefix directory", prefixDirPath, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Create prefix staging directory if it does not already exist and set required permissions
                    error = TermuxFileUtils.isTermuxPrefixStagingDirectoryAccessible(true, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Create prefix directory if it does not already exist and set required permissions
                    error = TermuxFileUtils.isTermuxPrefixDirectoryAccessible(true, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Extracting bootstrap zip to prefix staging directory \"" + stagingPrefixDirPath + "\".");

                    final byte[] buffer = new byte[8096];
                    final List<Pair<String, String>> symlinks = new ArrayList<>(50);

                    final byte[] zipBytes = loadZipBytes();
                    try (ZipInputStream zipInput = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
                        ZipEntry zipEntry;
                        while ((zipEntry = zipInput.getNextEntry()) != null) {
                            if (zipEntry.getName().equals("SYMLINKS.txt")) {
                                BufferedReader symlinksReader = new BufferedReader(new InputStreamReader(zipInput));
                                String line;
                                while ((line = symlinksReader.readLine()) != null) {
                                    String[] parts = line.split("←");
                                    if (parts.length != 2)
                                        throw new RuntimeException("Malformed symlink line: " + line);
                                    String oldPath = TermuxPathCompat.toPhysical(parts[0]);
                                    String newPath = stagingPrefixDirPath + "/" + parts[1];
                                    symlinks.add(Pair.create(oldPath, newPath));

                                    error = ensureDirectoryExists(new File(newPath).getParentFile());
                                    if (error != null) {
                                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                                        return;
                                    }
                                }
                            } else {
                                String zipEntryName = zipEntry.getName();
                                File targetFile = new File(stagingPrefixDirPath, zipEntryName);
                                boolean isDirectory = zipEntry.isDirectory();

                                error = ensureDirectoryExists(isDirectory ? targetFile : targetFile.getParentFile());
                                if (error != null) {
                                    showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                                    return;
                                }

                                if (!isDirectory) {
                                    try (FileOutputStream outStream = new FileOutputStream(targetFile)) {
                                        int readBytes;
                                        while ((readBytes = zipInput.read(buffer)) != -1)
                                            outStream.write(buffer, 0, readBytes);
                                    }
                                    if (zipEntryName.startsWith("bin/") || zipEntryName.startsWith("libexec") ||
                                        zipEntryName.startsWith("lib/apt/apt-helper") || zipEntryName.startsWith("lib/apt/methods")) {
                                        //noinspection OctalInteger
                                        Os.chmod(targetFile.getAbsolutePath(), 0700);
                                    }
                                }
                            }
                        }
                    }

                    if (symlinks.isEmpty())
                        throw new RuntimeException("No SYMLINKS.txt encountered");
                    for (Pair<String, String> symlink : symlinks) {
                        Os.symlink(symlink.first, symlink.second);
                    }

                    rewriteHardcodedPrefixUnder(stagingPrefixDir);

                    Logger.logInfo(LOG_TAG, "Moving termux prefix staging to prefix directory.");

                    if (!stagingPrefixDir.renameTo(prefixDir)) {
                        throw new RuntimeException("Moving termux prefix staging to prefix directory failed");
                    }

                    Error homeError = FileUtils.createDirectoryFile(TermuxPathCompat.toPhysical(TermuxConstants.TERMUX_HOME_DIR_PATH));
                    if (homeError != null)
                        Logger.logError(LOG_TAG, homeError.toString());

                    Logger.logInfo(LOG_TAG, "Bootstrap packages installed successfully.");

                    // Recreate env file since termux prefix was wiped earlier
                    TermuxShellEnvironment.writeEnvironmentToFile(activity);

                    activity.runOnUiThread(whenDone);

                } catch (final Exception e) {
                    showBootstrapErrorDialog(activity, whenDone, Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)));

                } finally {
                    activity.runOnUiThread(() -> {
                        try {
                            progress.dismiss();
                        } catch (RuntimeException e) {
                            // Activity already dismissed - ignore.
                        }
                    });
                }
            }
        }.start();
    }

    public static void showBootstrapErrorDialog(Activity activity, Runnable whenDone, String message) {
        Logger.logErrorExtended(LOG_TAG, "Bootstrap Error:\n" + message);

        // Send a notification with the exception so that the user knows why bootstrap setup failed
        sendBootstrapCrashReportNotification(activity, message);

        activity.runOnUiThread(() -> {
            try {
                new AlertDialog.Builder(activity).setTitle(R.string.bootstrap_error_title).setMessage(R.string.bootstrap_error_body)
                    .setNegativeButton(R.string.bootstrap_error_abort, (dialog, which) -> {
                        dialog.dismiss();
                        activity.finish();
                    })
                    .setPositiveButton(R.string.bootstrap_error_try_again, (dialog, which) -> {
                        dialog.dismiss();
                        FileUtils.deleteFile("termux prefix directory", TermuxPathCompat.toPhysical(TERMUX_PREFIX_DIR_PATH), true);
                        TermuxInstaller.setupBootstrapIfNeeded(activity, whenDone);
                    }).show();
            } catch (WindowManager.BadTokenException e1) {
                // Activity already dismissed - ignore.
            }
        });
    }

    private static void sendBootstrapCrashReportNotification(Activity activity, String message) {
        final String title = TermuxConstants.TERMUX_APP_NAME + " Bootstrap Error";

        // Add info of all install Termux plugin apps as well since their target sdk or installation
        // on external/portable sd card can affect Termux app files directory access or exec.
        TermuxCrashUtils.sendCrashReportNotification(activity, LOG_TAG,
            title, null, "## " + title + "\n\n" + message + "\n\n" +
                TermuxUtils.getTermuxDebugMarkdownString(activity),
            true, false, TermuxUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES, true);
    }

    static void setupStorageSymlinks(final Context context) {
        final String LOG_TAG = "termux-storage";
        final String title = TermuxConstants.TERMUX_APP_NAME + " Setup Storage Error";

        Logger.logInfo(LOG_TAG, "Setting up storage symlinks.");

        new Thread() {
            public void run() {
                try {
                    Error error;
                    File storageDir = TermuxPathCompat.toPhysicalFile(TermuxConstants.TERMUX_STORAGE_HOME_DIR);

                    error = FileUtils.clearDirectory("~/storage", storageDir.getAbsolutePath());
                    if (error != null) {
                        Logger.logErrorAndShowToast(context, LOG_TAG, error.getMessage());
                        Logger.logErrorExtended(LOG_TAG, "Setup Storage Error\n" + error.toString());
                        TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                            "## " + title + "\n\n" + Error.getErrorMarkdownString(error),
                            true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/shared, ~/storage/downloads, ~/storage/dcim, ~/storage/pictures, ~/storage/music and ~/storage/movies for directories in \"" + Environment.getExternalStorageDirectory().getAbsolutePath() + "\".");

                    // Get primary storage root "/storage/emulated/0" symlink
                    File sharedDir = Environment.getExternalStorageDirectory();
                    Os.symlink(sharedDir.getAbsolutePath(), new File(storageDir, "shared").getAbsolutePath());

                    File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
                    Os.symlink(documentsDir.getAbsolutePath(), new File(storageDir, "documents").getAbsolutePath());

                    File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    Os.symlink(downloadsDir.getAbsolutePath(), new File(storageDir, "downloads").getAbsolutePath());

                    File dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
                    Os.symlink(dcimDir.getAbsolutePath(), new File(storageDir, "dcim").getAbsolutePath());

                    File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                    Os.symlink(picturesDir.getAbsolutePath(), new File(storageDir, "pictures").getAbsolutePath());

                    File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
                    Os.symlink(musicDir.getAbsolutePath(), new File(storageDir, "music").getAbsolutePath());

                    File moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
                    Os.symlink(moviesDir.getAbsolutePath(), new File(storageDir, "movies").getAbsolutePath());

                    File podcastsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS);
                    Os.symlink(podcastsDir.getAbsolutePath(), new File(storageDir, "podcasts").getAbsolutePath());

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        File audiobooksDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_AUDIOBOOKS);
                        Os.symlink(audiobooksDir.getAbsolutePath(), new File(storageDir, "audiobooks").getAbsolutePath());
                    }

                    // Dir 0 should ideally be for primary storage
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/app/ContextImpl.java;l=818
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/os/Environment.java;l=219
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/os/Environment.java;l=181
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/services/core/java/com/android/server/StorageManagerService.java;l=3796
                    // https://cs.android.com/android/platform/superproject/+/android-7.0.0_r36:frameworks/base/services/core/java/com/android/server/MountService.java;l=3053

                    // Create "Android/data/com.termux" symlinks
                    File[] dirs = context.getExternalFilesDirs(null);
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "external-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    // Create "Android/media/com.termux" symlinks
                    dirs = context.getExternalMediaDirs();
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "media-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    Logger.logInfo(LOG_TAG, "Storage symlinks created successfully.");
                } catch (Exception e) {
                    Logger.logErrorAndShowToast(context, LOG_TAG, e.getMessage());
                    Logger.logStackTraceWithMessage(LOG_TAG, "Setup Storage Error: Error setting up link", e);
                    TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                        "## " + title + "\n\n" + Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)),
                        true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                }
            }
        }.start();
    }

    /**
     * Copy the remap library into $PREFIX/lib and replace {@code bin/login} so bash is not
     * started as a login shell (compiled SYSCONFDIR is {@code /data/data/com.termux/files/usr/etc}).
     */
    private static void installWorkProfileGlue(Context context, File prefixDir) {
        if (!TermuxPathCompat.needsRemap() || prefixDir == null)
            return;
        try {
            File libDir = new File(prefixDir, "lib");
            libDir.mkdirs();
            File src = new File(context.getApplicationInfo().nativeLibraryDir, TermuxPathCompat.REMAP_LIBRARY_NAME);
            File dst = new File(libDir, TermuxPathCompat.REMAP_LIBRARY_NAME);
            if (src.isFile()) {
                copyFile(src, dst);
                Os.chmod(dst.getAbsolutePath(), 0755);
            }

            File etcTermux = new File(prefixDir, "etc/termux");
            etcTermux.mkdirs();
            File rc = new File(etcTermux, "work-rc.sh");
            String prefix = prefixDir.getAbsolutePath();
            String home = TermuxPathCompat.getPhysicalFilesDir() + "/home";
            String rcBody = "if [ -f \"" + prefix + "/etc/profile\" ]; then . \"" + prefix + "/etc/profile\"; fi\n"
                + "if [ -f \"$HOME/.bashrc\" ]; then . \"$HOME/.bashrc\"; fi\n";
            writeTextFile(rc, rcBody);
            Os.chmod(rc.getAbsolutePath(), 0700);

            File login = new File(prefixDir, "bin/login");
            if (login.exists())
                login.delete();
            String loginBody = "#!/system/bin/sh\n"
                + "PREFIX=\"" + prefix + "\"\n"
                + "HOME=\"" + home + "\"\n"
                + "export PREFIX HOME\n"
                + "export PATH=\"$PREFIX/bin:$PATH\"\n"
                + "export TMPDIR=\"$PREFIX/tmp\"\n"
                + "export LD_LIBRARY_PATH=\"$PREFIX/lib\"\n"
                + "REMAP=\"$PREFIX/lib/" + TermuxPathCompat.REMAP_LIBRARY_NAME + "\"\n"
                + "if [ -f \"$REMAP\" ]; then\n"
                + "  export TERMUX_PREFIX_REMAP_FROM=\"" + TermuxConstants.TERMUX_BOOTSTRAP_APP_DATA_DIR_PATH + "\"\n"
                + "  export TERMUX_PREFIX_REMAP_TO=\"" + TermuxPathCompat.getPhysicalAppDataDir() + "\"\n"
                + "  export LD_PRELOAD=\"$REMAP${LD_PRELOAD:+:$LD_PRELOAD}\"\n"
                + "fi\n"
                + "cd \"$HOME\" 2>/dev/null || true\n"
                + "exec \"$PREFIX/bin/bash\" --noprofile --rcfile \"" + rc.getAbsolutePath() + "\"\n";
            writeTextFile(login, loginBody);
            Os.chmod(login.getAbsolutePath(), 0700);
            Logger.logInfo(LOG_TAG, "Installed work-profile login glue under " + prefix);
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to install work-profile login glue: " + e.getMessage());
        }
    }

    private static void copyFile(File src, File dst) throws java.io.IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0)
                out.write(buf, 0, n);
        }
    }

    private static void writeTextFile(File file, String body) throws java.io.IOException {
        File parent = file.getParentFile();
        if (parent != null)
            parent.mkdirs();
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            out.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    /**
     * Bootstrap scripts (login, second-stage, profile) hardcode
     * {@code /data/data/com.termux}. Kernel shebang lookup and toybox
     * {@code chmod}/{@code mkdir} ignore LD_PRELOAD, so rewrite text files
     * to the physical app data dir.
     */
    private static void rewriteHardcodedPrefixUnder(File dir) {
        if (dir == null || !dir.isDirectory())
            return;
        String from = TermuxConstants.TERMUX_BOOTSTRAP_APP_DATA_DIR_PATH;
        String to = TermuxPathCompat.getPhysicalAppDataDir();
        if (from.equals(to))
            return;
        File[] children = dir.listFiles();
        if (children == null)
            return;
        for (File child : children) {
            if (child.isDirectory()) {
                rewriteHardcodedPrefixUnder(child);
                continue;
            }
            if (!child.isFile() || child.length() < from.length() || child.length() > 4 * 1024 * 1024)
                continue;
            try {
                byte[] data = readAllBytesCompat(child);
                if (data.length >= 4 && data[0] == 0x7F && data[1] == 'E' && data[2] == 'L' && data[3] == 'F')
                    continue;
                boolean hasNul = false;
                for (byte b : data) {
                    if (b == 0) {
                        hasNul = true;
                        break;
                    }
                }
                if (hasNul)
                    continue;
                String text = new String(data, java.nio.charset.StandardCharsets.ISO_8859_1);
                if (!text.contains(from))
                    continue;
                String rewritten = text.replace(from, to);
                try (FileOutputStream fos = new FileOutputStream(child, false)) {
                    fos.write(rewritten.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                }
            } catch (Exception ignored) {
                // Best-effort; setupShellCommandArguments also remaps shebangs at exec time.
            }
        }
    }

    private static byte[] readAllBytesCompat(File file) throws java.io.IOException {
        long len = file.length();
        if (len > Integer.MAX_VALUE)
            throw new java.io.IOException("file too large");
        byte[] data = new byte[(int) len];
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            int off = 0;
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n < 0)
                    break;
                off += n;
            }
            if (off != data.length) {
                byte[] trimmed = new byte[off];
                System.arraycopy(data, 0, trimmed, 0, off);
                return trimmed;
            }
            return data;
        }
    }

    private static Error ensureDirectoryExists(File directory) {
        return FileUtils.createDirectoryFile(directory.getAbsolutePath());
    }

    public static byte[] loadZipBytes() {
        // Only load the shared library when necessary to save memory usage.
        System.loadLibrary("termux-bootstrap");
        return getZip();
    }

    public static native byte[] getZip();

}
