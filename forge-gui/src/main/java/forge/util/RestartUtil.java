package forge.util;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * Restarts a java app.
 *
 * Rebuilt on ProcessBuilder (07.31 incident follow-up): the original built one
 * command STRING for Runtime.exec(String), which tokenizes on whitespace and
 * honors no quoting - so the quoted java binary never exec'd on Linux, and any
 * install path with a space (".../Forge Fork/forge-playable.jar") split
 * mid-path. Every desktop restart just exited without relaunching, with the
 * failure swallowed in the shutdown hook. Argument lists never re-tokenize.
 */
public class RestartUtil {
    /**
     * Sun property pointing the main class and its arguments.
     * Might not be defined on non Hotspot VM implementations.
     */
    public static final String SUN_JAVA_COMMAND = "sun.java.command";

    /**
     * Registers a shutdown hook that relaunches the current Java application.
     * Returns false (and registers nothing) when no safe relaunch command can
     * be derived - the caller should then NOT exit expecting a restart.
     */
    public static boolean prepareForRestart() {
        try {
            final List<String> cmd = buildRestartCommand(
                    System.getProperty("java.home"),
                    ManagementFactory.getRuntimeMXBean().getInputArguments(),
                    System.getProperty(SUN_JAVA_COMMAND, ""),
                    System.getProperty("java.class.path", ""),
                    codeSourceJar());
            if (cmd == null) {
                return false;
            }
            // execute in a shutdown hook, to be sure that all the resources
            // have been disposed before restarting the application
            Runtime.getRuntime().addShutdownHook(new Thread() {
                @Override
                public void run() {
                    try {
                        new ProcessBuilder(cmd).inheritIO().start();
                    } catch (final IOException e) {
                        //nothing left to report to - the JVM is going down
                    }
                }
            });
            return true;
        } catch (final Exception ex) {
            return false;
        }
    }

    /** The jar this class was loaded from, or null for a classes/dev launch. */
    private static File codeSourceJar() {
        try {
            final File jar = new File(RestartUtil.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return jar.isFile() && jar.getName().endsWith(".jar") ? jar : null;
        } catch (final Exception ex) {
            return null;
        }
    }

    /**
     * The relaunch command as an argument list, or null when none is safe to
     * build. For a jar launch the jar path comes from the code source (the
     * one space-safe source - sun.java.command cannot be re-split once the
     * path contains spaces); trailing program arguments are recovered from
     * sun.java.command where they parse unambiguously.
     */
    static List<String> buildRestartCommand(String javaHome, List<String> vmArguments, String sunJavaCommand,
            String classPath, File codeSourceJar) {
        final List<String> cmd = new ArrayList<>();
        cmd.add(javaHome + File.separator + "bin" + File.separator + "java");
        for (final String arg : vmArguments) {
            // skip the agent argument: the old and new app would conflict on the same address
            if (!arg.contains("-agentlib")) {
                cmd.add(arg);
            }
        }
        if (codeSourceJar != null) {
            cmd.add("-jar");
            cmd.add(codeSourceJar.getAbsolutePath());
            //program args follow the jar path in sun.java.command; the path itself may
            //contain spaces, so parse only what follows its (path-final) filename
            final int nameAt = sunJavaCommand.lastIndexOf(codeSourceJar.getName());
            if (nameAt >= 0) {
                final String tail = sunJavaCommand.substring(nameAt + codeSourceJar.getName().length()).strip();
                if (!tail.isEmpty()) {
                    for (final String arg : tail.split(" ")) {
                        cmd.add(arg);
                    }
                }
            }
            return cmd;
        }
        if (sunJavaCommand.isBlank()) {
            return null;
        }
        final String[] mainCommand = sunJavaCommand.split(" ");
        if (mainCommand[0].endsWith(".jar") || mainCommand[0].contains("/") || mainCommand[0].contains("\\")) {
            //jar launch without a readable code source: the real path may contain
            //spaces this split just destroyed (a space path does not even keep its
            //.jar suffix in token 0) - refuse rather than relaunch garbage. Class
            //names are dotted identifiers, never paths.
            return null;
        }
        //classes/dev launch: class names never contain spaces, so the split is safe
        cmd.add("-cp");
        cmd.add(classPath);
        for (final String arg : mainCommand) {
            cmd.add(arg);
        }
        return cmd;
    }
}
