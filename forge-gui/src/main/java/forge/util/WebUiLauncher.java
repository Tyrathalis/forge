package forge.util;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import forge.gui.GuiBase;
import forge.localinstance.properties.ForgeConstants;

/**
 * Starts the web GUI (forge-gui-web, main class {@value #MAIN_CLASS}) from the app the player is already running.
 *
 * Playable fork: a -Pweb build puts the web module in the same jar as the libGDX app, so the updater carries both.
 * The two GUIs cannot share a JVM, because each installs its own GuiBase interface, so the web server runs as a
 * second process from this same jar, pointed at the same res folder. Closing the web server's console window stops
 * it; quitting the app leaves it running. Built like {@link RestartUtil}: an argument list, never one command string.
 *
 * The server runs from a copy of the jar in the cache folder, never from the installed jar. Windows locks a running
 * jar, and the updater replaces the installed one after the app exits (UpdateApplier), so a server left running from
 * it would make every update fail.
 */
public final class WebUiLauncher {
    public static final String MAIN_CLASS = "forge.web.WebMain";

    /** What the web jar's manifest opens. A -cp launch does not read a manifest's Add-Opens, so they go on the command line. */
    private static final String[] OPENS = {"java.base/java.util", "java.base/java.lang", "java.base/java.lang.reflect",
            "java.base/java.text", "java.base/java.math", "java.base/java.util.concurrent", "java.base/java.net", "java.base/java.nio"};

    private static Process running;

    private WebUiLauncher() {}

    /** Whether this build carries the web GUI and can start it: a desktop -Pweb build. */
    public static boolean isAvailable() {
        return !GuiBase.isAndroid() && WebUiLauncher.class.getResource("/" + MAIN_CLASS.replace('.', '/') + ".class") != null;
    }

    /** Whether a web server this app started is still running. */
    public static synchronized boolean isRunning() {
        return running != null && running.isAlive();
    }

    /**
     * Starts the web server unless one this app started is still running. Its output goes to forge-web.log in the
     * user folder. Returns null when it started, else why not.
     */
    public static synchronized String launch(final String assetsDir) {
        if (isRunning()) {
            return "The Web UI is already running. Its window shows the links.";
        }
        try {
            final File jar = codeSourceJar();
            final List<String> cmd = buildCommand(System.getProperty("java.home"),
                    ManagementFactory.getRuntimeMXBean().getInputArguments(),
                    System.getProperty("java.class.path", ""), jar == null ? null : serverCopy(jar), assetsDir);
            final File log = new File(ForgeConstants.USER_DIR, "forge-web.log");
            running = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log).start();
            return null;
        } catch (final Exception e) {
            return "Could not start the Web UI: " + e.getMessage();
        }
    }

    /**
     * A copy of the app jar for the server to run from, named for the jar's size and time, so each build gets its own.
     * Older copies are deleted when nothing holds them (a server still running from one keeps it on Windows).
     */
    private static File serverCopy(final File jar) throws IOException {
        final File dir = new File(ForgeConstants.CACHE_DIR, "web");
        final File copy = new File(dir, "forge-web-" + jar.length() + "-" + jar.lastModified() + ".jar");
        if (!copy.isFile()) {
            Files.createDirectories(dir.toPath());
            final File part = new File(dir, copy.getName() + ".part");
            Files.copy(jar.toPath(), part.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Files.move(part.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        final File[] old = dir.listFiles((d, name) -> name.startsWith("forge-web-") && !name.equals(copy.getName()));
        if (old != null) {
            for (final File f : old) {
                f.delete();
            }
        }
        return copy;
    }

    /** The jar this class was loaded from, or null for a classes/dev launch. */
    private static File codeSourceJar() {
        try {
            final File jar = new File(WebUiLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return jar.isFile() && jar.getName().endsWith(".jar") ? jar : null;
        } catch (final Exception ex) {
            return null;
        }
    }

    /**
     * The web server's command line. The app's own JVM arguments are kept (memory, -D settings), except a debug agent
     * and anything naming another main class. The classpath is the app's jar when it was launched from one, which a
     * -jar launch leaves out of java.class.path on some launchers.
     */
    static List<String> buildCommand(final String javaHome, final List<String> vmArguments, final String classPath,
            final File codeSourceJar, final String assetsDir) {
        final List<String> cmd = new ArrayList<>();
        cmd.add(javaHome + File.separator + "bin" + File.separator + "java");
        for (final String arg : vmArguments) {
            if (!arg.contains("-agentlib") && !arg.startsWith("-Dsun.java.command")) {
                cmd.add(arg);
            }
        }
        for (final String open : OPENS) {
            cmd.add("--add-opens");
            cmd.add(open + "=ALL-UNNAMED");
        }
        cmd.add("-Dio.netty.tryReflectionSetAccessible=true");
        cmd.add("-Dfile.encoding=UTF-8");
        if (assetsDir != null && !assetsDir.isEmpty()) {
            cmd.add("-Dforge.assets.dir=" + new File(assetsDir).getAbsolutePath());
        }
        cmd.add("-cp");
        cmd.add(codeSourceJar != null ? codeSourceJar.getAbsolutePath() : classPath);
        cmd.add(MAIN_CLASS);
        return cmd;
    }
}
