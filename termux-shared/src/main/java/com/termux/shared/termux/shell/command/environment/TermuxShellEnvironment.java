package com.termux.shared.termux.shell.command.environment;

import android.content.Context;

import androidx.annotation.NonNull;

import com.termux.shared.errors.Error;
import com.termux.shared.file.FileUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.environment.AndroidShellEnvironment;
import com.termux.shared.shell.command.environment.ShellEnvironmentUtils;
import com.termux.shared.shell.command.environment.ShellCommandShellEnvironment;
import com.termux.shared.termux.TermuxBootstrap;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxPathCompat;
import com.termux.shared.termux.shell.TermuxShellUtils;

import java.nio.charset.Charset;
import java.util.HashMap;

/**
 * Environment for Termux.
 */
public class TermuxShellEnvironment extends AndroidShellEnvironment {

    private static final String LOG_TAG = "TermuxShellEnvironment";

    /** Environment variable for the termux {@link TermuxConstants#TERMUX_PREFIX_DIR_PATH}. */
    public static final String ENV_PREFIX = "PREFIX";

    public TermuxShellEnvironment() {
        super();
        shellCommandShellEnvironment = new TermuxShellCommandShellEnvironment();
    }


    /** Init {@link TermuxShellEnvironment} constants and caches. */
    public synchronized static void init(@NonNull Context currentPackageContext) {
        TermuxAppShellEnvironment.setTermuxAppEnvironment(currentPackageContext);
    }

    /** Init {@link TermuxShellEnvironment} constants and caches. */
    public synchronized static void writeEnvironmentToFile(@NonNull Context currentPackageContext) {
        HashMap<String, String> environmentMap = new TermuxShellEnvironment().getEnvironment(currentPackageContext, false);
        String environmentString = ShellEnvironmentUtils.convertEnvironmentToDotEnvFile(environmentMap);

        // Write environment string to temp file and then move to final location since otherwise
        // writing may happen while file is being sourced/read
        Error error = FileUtils.writeTextToFile("termux.env.tmp", TermuxPathCompat.toPhysical(TermuxConstants.TERMUX_ENV_TEMP_FILE_PATH),
            Charset.defaultCharset(), environmentString, false);
        if (error != null) {
            Logger.logErrorExtended(LOG_TAG, error.toString());
            return;
        }

        error = FileUtils.moveRegularFile("termux.env.tmp", TermuxPathCompat.toPhysical(TermuxConstants.TERMUX_ENV_TEMP_FILE_PATH), TermuxPathCompat.toPhysical(TermuxConstants.TERMUX_ENV_FILE_PATH), true);
        if (error != null) {
            Logger.logErrorExtended(LOG_TAG, error.toString());
        }
    }

    /** Get shell environment for Termux. */
    @NonNull
    @Override
    public HashMap<String, String> getEnvironment(@NonNull Context currentPackageContext, boolean isFailSafe) {

        // Termux environment builds upon the Android environment
        HashMap<String, String> environment = super.getEnvironment(currentPackageContext, isFailSafe);

        HashMap<String, String> termuxAppEnvironment = TermuxAppShellEnvironment.getEnvironment(currentPackageContext);
        if (termuxAppEnvironment != null)
            environment.putAll(termuxAppEnvironment);

        HashMap<String, String> termuxApiAppEnvironment = TermuxAPIShellEnvironment.getEnvironment(currentPackageContext);
        if (termuxApiAppEnvironment != null)
            environment.putAll(termuxApiAppEnvironment);

        environment.put(ENV_HOME, TermuxPathCompat.toPhysical(TermuxConstants.TERMUX_HOME_DIR_PATH));
        environment.put(ENV_PREFIX, TermuxPathCompat.toPhysical(TermuxConstants.TERMUX_PREFIX_DIR_PATH));
        TermuxPathCompat.putRemapEnvironment(environment);

        // If failsafe is not enabled, then we keep default PATH and TMPDIR so that system binaries can be used
        if (!isFailSafe) {
            environment.put(ENV_TMPDIR, TermuxPathCompat.toPhysical(TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH));
            if (TermuxBootstrap.isAppPackageVariantAPTAndroid5()) {
                // Termux in android 5/6 era shipped busybox binaries in applets directory
                String bin = TermuxPathCompat.toPhysical(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH);
                environment.put(ENV_PATH, bin + ":" + bin + "/applets");
                environment.put(ENV_LD_LIBRARY_PATH, TermuxPathCompat.toPhysical(TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH));
            } else {
                environment.put(ENV_PATH, TermuxPathCompat.toPhysical(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH));
                if (TermuxPathCompat.needsRemap()) {
                    // DT_RUNPATH is baked to /data/data/com.termux; the system linker will not
                    // find libs there on a work profile. Point it at the real lib dir.
                    environment.put(ENV_LD_LIBRARY_PATH, TermuxPathCompat.toPhysical(TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH));
                } else {
                    environment.remove(ENV_LD_LIBRARY_PATH);
                }
            }
        }

        return environment;
    }


    @NonNull
    @Override
    public String getDefaultWorkingDirectoryPath() {
        return TermuxPathCompat.toPhysical(TermuxConstants.TERMUX_HOME_DIR_PATH);
    }

    @NonNull
    @Override
    public String getDefaultBinPath() {
        return TermuxPathCompat.toPhysical(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH);
    }

    @NonNull
    @Override
    public String[] setupShellCommandArguments(@NonNull String executable, String[] arguments) {
        return TermuxShellUtils.setupShellCommandArguments(executable, arguments);
    }

}
