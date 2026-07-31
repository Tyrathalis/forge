package forge.util;

import java.io.File;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Pins the restart relaunch command (07.31 incident follow-up). The old
 * Runtime.exec(String) implementation tokenized on whitespace with no quoting,
 * so a quoted java binary never exec'd and any install path with a space
 * (".../Forge Fork/forge-playable.jar") split mid-path - every desktop restart
 * exited without relaunching, silently. The command is now an argument list;
 * these tests pin that paths with spaces survive as single arguments.
 */
public class RestartUtilTest {

    @Test
    public void jarPathWithSpacesStaysOneArgument() {
        final File jar = new File("/home/user/Forge Fork/forge-playable.jar");
        final List<String> cmd = RestartUtil.buildRestartCommand(
                "/usr/lib/jvm/java-17", List.of("-Xmx4g", "-agentlib:jdwp=whatever"),
                "/home/user/Forge Fork/forge-playable.jar", "ignored.jar", jar);
        Assert.assertEquals(cmd, List.of(
                "/usr/lib/jvm/java-17" + File.separator + "bin" + File.separator + "java",
                "-Xmx4g", //agentlib filtered
                "-jar", jar.getAbsolutePath()));
    }

    @Test
    public void jarLaunchRecoversTrailingProgramArgs() {
        final File jar = new File("/opt/My Games/forge.jar");
        final List<String> cmd = RestartUtil.buildRestartCommand(
                "/java", List.of(), "/opt/My Games/forge.jar adventure fullscreen", "cp", jar);
        Assert.assertEquals(cmd.subList(cmd.size() - 2, cmd.size()), List.of("adventure", "fullscreen"));
        Assert.assertTrue(cmd.contains(jar.getAbsolutePath()));
    }

    @Test
    public void classesLaunchUsesClasspathAndMainClass() {
        final List<String> cmd = RestartUtil.buildRestartCommand(
                "/java", List.of("-Xmx2g"), "forge.app.Main adventure", "/some/class path/classes", null);
        Assert.assertEquals(cmd, List.of(
                "/java" + File.separator + "bin" + File.separator + "java",
                "-Xmx2g", "-cp", "/some/class path/classes", "forge.app.Main", "adventure"));
    }

    @Test
    public void unreconstructibleLaunchRefusesInsteadOfGarbage() {
        //jar launch but no readable code source: the split may have destroyed a
        //space path - null tells the caller not to exit expecting a restart
        Assert.assertNull(RestartUtil.buildRestartCommand(
                "/java", List.of(), "/opt/My Games/forge.jar", "cp", null));
        Assert.assertNull(RestartUtil.buildRestartCommand("/java", List.of(), "", "cp", null));
    }
}
