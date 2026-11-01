package forge.util;

import java.io.File;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Pins the web GUI's launch command (playable fork). The app starts the web server as a second JVM from its own jar,
 * so the command must carry the web jar's Add-Opens itself (a -cp launch ignores the manifest), keep paths with spaces
 * whole, and point the server at the app's res folder.
 */
public class WebUiLauncherTest {

    private static final File JAR = new File("/home/user/Forge Fork/forge-playable.jar");

    @Test
    public void runsTheWebMainClassFromTheAppJar() {
        final List<String> cmd = WebUiLauncher.buildCommand("/usr/lib/jvm/java-17", List.of("-Xmx4g"), "ignored.jar", JAR, null);
        Assert.assertEquals(cmd.get(0), "/usr/lib/jvm/java-17" + File.separator + "bin" + File.separator + "java");
        Assert.assertEquals(cmd.get(1), "-Xmx4g");
        Assert.assertEquals(cmd.subList(cmd.size() - 3, cmd.size()), List.of("-cp", JAR.getAbsolutePath(), WebUiLauncher.MAIN_CLASS));
    }

    @Test
    public void carriesTheWebJarsOpensAndDropsADebugAgent() {
        final List<String> cmd = WebUiLauncher.buildCommand("/java", List.of("-agentlib:jdwp=transport=dt_socket"), "cp", JAR, null);
        Assert.assertFalse(cmd.stream().anyMatch(a -> a.contains("-agentlib")));
        final int at = cmd.indexOf("java.base/java.nio=ALL-UNNAMED");
        Assert.assertTrue(at > 0);
        Assert.assertEquals(cmd.get(at - 1), "--add-opens");
    }

    @Test
    public void pointsTheServerAtTheAppsResFolder() {
        final List<String> cmd = WebUiLauncher.buildCommand("/java", List.of(), "cp", JAR, "/opt/My Forge/");
        Assert.assertTrue(cmd.contains("-Dforge.assets.dir=" + new File("/opt/My Forge/").getAbsolutePath()));
    }

    @Test
    public void classesLaunchUsesTheClassPath() {
        final List<String> cmd = WebUiLauncher.buildCommand("/java", List.of(), "/some/class path/classes", null, null);
        Assert.assertEquals(cmd.subList(cmd.size() - 3, cmd.size()), List.of("-cp", "/some/class path/classes", WebUiLauncher.MAIN_CLASS));
    }
}
