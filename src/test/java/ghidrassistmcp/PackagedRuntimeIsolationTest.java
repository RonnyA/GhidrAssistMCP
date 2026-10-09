package ghidrassistmcp;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/** Checks the distributed jar against the same older libraries Ghidra loads first. */
class PackagedRuntimeIsolationTest {
    @Test
    void packagedServerWorksWithHostLibrariesLoadedBeforeTheExtension() throws Exception {
        String classpath = System.getProperty("mcp.packagedTestClasspath");
        assertNotNull(classpath, "Gradle must provide the packaged runtime classpath");
        Path log = Files.createTempFile("gamcp-packaged-runtime-", ".log");
        Process process = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Djava.awt.headless=true", "-Dmcp.packagedJar=" + System.getProperty("mcp.packagedJar"),
            "-cp", classpath, PackagedRuntimeSmoke.class.getName())
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean completed = process.waitFor(30, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
        }
        String output = Files.readString(log, StandardCharsets.UTF_8);
        Files.deleteIfExists(log);
        assertTrue(completed, "Packaged runtime subprocess timed out:\n" + output);
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("PACKAGED_RUNTIME_OK"), output);
    }
}
