package forge.util.update;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Tiny helper process that swaps in the staged launcher jar after the app
 * exits, then relaunches it. Needed because the running jar file is locked on
 * some platforms (Windows) - so the app stages the new jar, spawns this from
 * the STAGED jar's classpath, and exits; this retries the copy until the old
 * jar unlocks, then starts the new build.
 *
 * Usage: java -cp &lt;staged jar&gt; forge.util.update.UpdateApplier
 *   &lt;targetJar&gt; &lt;stagedJar&gt; &lt;workDir&gt; [jvmArg...]
 */
public final class UpdateApplier {
    private static final int RETRIES = 120;
    private static final long RETRY_DELAY_MS = 500;

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: UpdateApplier <targetJar> <stagedJar> <workDir> [jvmArg...]");
            System.exit(2);
        }
        final File targetJar = new File(args[0]);
        final File stagedJar = new File(args[1]);
        final File workDir = new File(args[2]);

        IOException lastFailure = null;
        boolean applied = false;
        for (int attempt = 0; attempt < RETRIES; attempt++) {
            try {
                Files.copy(stagedJar.toPath(), targetJar.toPath(), StandardCopyOption.REPLACE_EXISTING);
                applied = true;
                break;
            } catch (final IOException ex) {
                lastFailure = ex; //old jar still locked by the exiting app - wait and retry
                Thread.sleep(RETRY_DELAY_MS);
            }
        }
        if (!applied) {
            System.err.println("Could not replace " + targetJar + ": " + lastFailure);
            System.exit(1);
        }

        stagedJar.delete();
        final File stagingDir = stagedJar.getParentFile();
        if (stagingDir != null && stagingDir.getName().equals(DeltaUpdater.STAGING_DIR_NAME)) {
            DeltaUpdater.deleteRecursively(stagingDir);
        }

        final String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        final List<String> command = new ArrayList<>();
        command.add(java);
        for (int i = 3; i < args.length; i++) {
            command.add(args[i]);
        }
        command.add("-jar");
        command.add(targetJar.getAbsolutePath());
        new ProcessBuilder(command).directory(workDir).inheritIO().start();
    }

    private UpdateApplier() {
    }
}
